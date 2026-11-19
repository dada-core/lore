package com.dadacore.lore.identity.members;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.InvitationSpaces;
import com.dadacore.lore.identity.InvitedSpace;
import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.identity.SpaceInvitation;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.account.AccountStatus;
import com.dadacore.lore.identity.account.MembershipRepository;
import com.dadacore.lore.identity.account.UserAccount;
import com.dadacore.lore.identity.account.UserAccountRepository;
import com.dadacore.lore.identity.organization.Organization;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.security.PasswordHasher;
import com.dadacore.lore.identity.security.Tokens;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.shared.Secrets;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitation links to join the organization, passed on by the person who creates them: no mail
 * server is needed. A link is used once; it expires after the delay set in Security (7 days by
 * default).
 *
 * <p>An optional address reserves the link to that person and lets administrators follow it as
 * "Invitation pending". The invitation can also add the person to spaces, with a role in each
 * ("Spaces to join").
 */
@Service
class Invitations {

    private final JdbcClient jdbc;
    private final OrganizationRepository organizations;
    private final UserAccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher hasher;
    private final AuditLog audit;
    private final InvitationSpaces invitationSpaces;
    private final TransactionTemplate transaction;
    private final Clock clock;

    Invitations(JdbcClient jdbc, OrganizationRepository organizations, UserAccountRepository accounts,
            MembershipRepository memberships, PasswordHasher hasher, AuditLog audit,
            InvitationSpaces invitationSpaces, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.accounts = accounts;
        this.memberships = memberships;
        this.hasher = hasher;
        this.audit = audit;
        this.invitationSpaces = invitationSpaces;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * @param email     the address the link is reserved to, if any
     * @param createdBy name of the person who created the link
     * @param spaces    the spaces the person will join, with their role
     */
    record InvitationView(UUID id, String role, @Nullable String email, @Nullable String createdBy,
            Instant createdAt, Instant expiresAt, List<InvitedSpace> spaces) {

        InvitationView withSpaces(List<InvitedSpace> joined) {
            return new InvitationView(id, role, email, createdBy, createdAt, expiresAt, joined);
        }
    }

    record CreatedInvitation(InvitationView invitation, String url) {

        /** The link holds the token: it stays out of the logs. */
        @Override
        public String toString() {
            return "CreatedInvitation[invitation=" + invitation + ", url=" + Secrets.hide(url) + "]";
        }
    }

    /**
     * @param email  optional: reserves the link to this address
     * @param spaces optional: the spaces to join, with a role in each
     */
    record NewInvitation(String role, @Nullable String email, @Nullable List<SpaceInvitation> spaces) {
    }

    record InvitationDetails(String organizationName, @Nullable String invitedBy, String role,
            @Nullable String email, Instant expiresAt, int passwordMinLength, List<InvitedSpace> spaces) {
    }

    record JoinRequest(String token, String displayName, String email, String password) {

        /** The token and the password stay out of the logs. */
        @Override
        public String toString() {
            return "JoinRequest[token=" + Secrets.hide(token) + ", displayName=" + displayName + ", email=" + email
                    + ", password=" + Secrets.hide(password) + "]";
        }
    }

    record Joined(UUID userId, UUID organizationId, int sessionTimeoutMinutes) {
    }

    List<InvitationView> pending(Viewer viewer) {
        viewer.requireAdmin();
        List<InvitationView> invitations = jdbc.sql("""
                select i.id, i.role, i.email, c.display_name as created_by, i.created_at, i.expires_at
                from invitation i left join user_account c on c.id = i.created_by
                where i.organization_id = ? and i.used_at is null and i.revoked_at is null and i.expires_at > ?
                order by i.created_at desc
                """)
                .params(viewer.organizationId(), Timestamp.from(clock.instant()))
                .query((rs, row) -> new InvitationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("role"),
                        rs.getString("email"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        List.of()))
                .list();
        Map<UUID, List<InvitedSpace>> spaces = invitationSpaces.spaces(
                invitations.stream().map(InvitationView::id).toList());
        return invitations.stream()
                .map(invitation -> invitation.withSpaces(spaces.getOrDefault(invitation.id(), List.of())))
                .toList();
    }

    @Transactional
    CreatedInvitation create(Viewer viewer, NewInvitation request, String baseUrl) {
        viewer.requireAdmin();
        Checks checks = Checks.start();
        OrganizationRole role = checks.oneOf("role", request.role(), OrganizationRole.class);
        if (role == OrganizationRole.OWNER) {
            checks.fail("role", "invalid_value");
        }
        String email = null;
        if (request.email() != null && !request.email().isBlank()) {
            email = checks.email("email", request.email());
        }
        checks.orThrow();
        if (email != null) {
            Optional<UserAccount> existing = accounts.findByEmail(email);
            if (existing.isPresent() && memberships.role(viewer.organizationId(), existing.get().id()).isPresent()) {
                Checks.start().fail("email", "already_member").orThrow();
            }
        }
        OrganizationSettings settings = organizations.settings(viewer.organizationId());
        String token = Tokens.generate();
        // PostgreSQL keeps microseconds: the dates returned now are the ones read later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(Duration.ofHours(settings.invitationValidityHours()));
        UUID id = jdbc.sql("""
                insert into invitation (organization_id, token_hash, role, email, created_by, expires_at)
                values (?, ?, ?, ?, ?, ?) returning id
                """)
                .params(viewer.organizationId(), Tokens.hash(token), role.name(), email, viewer.userId(),
                        Timestamp.from(expiresAt))
                .query(UUID.class)
                .single();
        List<InvitedSpace> spaces = request.spaces() == null || request.spaces().isEmpty()
                ? List.of()
                : invitationSpaces.attach(viewer, id, request.spaces());
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "invitation.created")
                .resource("invitation", id)
                .ip(viewer.ip())
                .detail("role", role.name())
                .detail("email", email)
                .detail("spaces", names(spaces))
                .build());
        InvitationView view = new InvitationView(id, role.name(), email, viewer.displayName(), now, expiresAt,
                spaces);
        return new CreatedInvitation(view, baseUrl + "/join#" + token);
    }

    @Transactional
    void revoke(Viewer viewer, UUID id) {
        viewer.requireAdmin();
        int updated = jdbc.sql("""
                update invitation set revoked_at = now()
                where id = ? and organization_id = ? and used_at is null and revoked_at is null
                """)
                .params(id, viewer.organizationId())
                .update();
        if (updated == 0) {
            throw ApiException.notFound();
        }
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "invitation.revoked")
                .resource("invitation", id)
                .ip(viewer.ip())
                .build());
    }

    /**
     * Revokes the links a person created that are still pending: they no longer have the right to
     * create them (no longer administrator, disabled or removed). Done in the caller's transaction.
     *
     * @return the number of links revoked
     */
    int revokeCreatedBy(UUID organizationId, UUID creatorId) {
        return jdbc.sql("""
                update invitation set revoked_at = now()
                where organization_id = ? and created_by = ? and used_at is null and revoked_at is null and expires_at > ?
                """)
                .params(organizationId, creatorId, Timestamp.from(clock.instant()))
                .update();
    }

    InvitationDetails inspect(String token) {
        Valid valid = find(token, false).orElseThrow(Invitations::invalidLink);
        Organization organization = organizations.find(valid.organizationId()).orElseThrow(Invitations::invalidLink);
        List<InvitedSpace> spaces = invitationSpaces.spaces(List.of(valid.id())).getOrDefault(valid.id(), List.of());
        return new InvitationDetails(organization.name(), valid.createdByName(), valid.role(), valid.email(),
                valid.expiresAt(), organizations.settings(organization.id()).passwordMinLength(), spaces);
    }

    /**
     * Creates the account and its membership, in one transaction where the invitation is locked:
     * two clicks at the same time create a single account.
     *
     * <p>A former member (removed from the organization, whose account stays so that their name
     * remains in the history) comes back with their account, but only through an invitation
     * reserved to their address: an open link would let whoever holds it take over the account.
     */
    Joined accept(JoinRequest request, String ip) {
        // Hashed before the transaction, once the link and the fields have been checked a first time:
        // the computation may wait for its turn (PasswordHasher), and the transaction would hold a
        // connection and the lock of the invitation meanwhile.
        String hash = hasher.hash(joining(find(request.token(), false).orElseThrow(Invitations::invalidLink), request)
                .password());
        return transaction.execute(status -> join(request, hash, ip));
    }

    private Joined join(JoinRequest request, String hash, String ip) {
        Joining joining = joining(find(request.token(), true).orElseThrow(Invitations::invalidLink), request);
        Valid valid = joining.invitation();
        OrganizationSettings settings = joining.settings();
        String displayName = joining.displayName();
        String email = joining.email();
        Optional<UserAccount> former = joining.former();
        UserAccount account;
        if (former.isPresent()) {
            account = former.get();
            accounts.updateProfile(account.id(), displayName, account.locale());
            accounts.updatePassword(account.id(), hash);
            accounts.updateStatus(account.id(), AccountStatus.ACTIVE);
        } else {
            try {
                account = accounts.create(email, displayName, hash, null);
            } catch (DuplicateKeyException e) {
                Checks.start().fail("email", "email_taken").orThrow();
                return null;
            }
        }
        return joined(valid, settings, account, displayName, former.isPresent(), ip);
    }

    /** The request, checked against the invitation: the field errors are thrown. */
    private Joining joining(Valid valid, JoinRequest request) {
        OrganizationSettings settings = organizations.settings(valid.organizationId());
        Checks checks = Checks.start();
        String displayName = checks.text("displayName", request.displayName(), 120);
        String email = checks.email("email", request.email());
        String password = checks.password("password", request.password(), settings.passwordMinLength());
        if (email != null && valid.email() != null && !valid.email().equals(email)) {
            checks.fail("email", "invitation_email_mismatch");
        }
        Optional<UserAccount> former = Optional.empty();
        if (email != null && !checks.hasErrors()) {
            Optional<UserAccount> existing = accounts.findByEmail(email);
            if (existing.isPresent() && memberships.role(valid.organizationId(), existing.get().id()).isPresent()) {
                checks.fail("email", "email_taken");
            } else if (existing.isPresent() && valid.email() == null) {
                checks.fail("email", "former_member");
            } else {
                former = existing;
            }
        }
        checks.orThrow();
        return new Joining(valid, settings, displayName, email, password, former);
    }

    private record Joining(Valid invitation, OrganizationSettings settings, String displayName, String email,
            String password, Optional<UserAccount> former) {
    }

    private Joined joined(Valid valid, OrganizationSettings settings, UserAccount account, String displayName,
            boolean returning, String ip) {
        OrganizationRole role = OrganizationRole.valueOf(valid.role());
        memberships.add(valid.organizationId(), account.id(), role);
        List<InvitedSpace> spaces = invitationSpaces.join(valid.id(), valid.organizationId(), account.id());
        accounts.recordLogin(account.id(), clock.instant());
        jdbc.sql("update invitation set used_at = now(), used_by = ? where id = ?")
                .params(account.id(), valid.id())
                .update();
        audit.record(AuditEntry.of(valid.organizationId(), account.id(), "member.joined")
                .resource("account", account.id())
                .ip(ip)
                .detail("name", displayName)
                .detail("role", role.name())
                .detail("invitedBy", valid.createdByName())
                .detail("returning", returning)
                .detail("spaces", names(spaces))
                .build());
        return new Joined(account.id(), valid.organizationId(), settings.sessionTimeoutMinutes());
    }

    private Optional<Valid> find(String token, boolean lock) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select i.id, i.organization_id, i.role, i.email, i.expires_at, c.display_name as created_by_name
                from invitation i left join user_account c on c.id = i.created_by
                where i.token_hash = ? and i.used_at is null and i.revoked_at is null and i.expires_at > ?
                """ + (lock ? " for update of i" : ""))
                .params(Tokens.hash(token), Timestamp.from(clock.instant()))
                .query((rs, row) -> new Valid(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("role"),
                        rs.getString("email"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("created_by_name")))
                .optional();
    }

    /** The names of the spaces, for the audit log; null when there are none. */
    private static @Nullable List<String> names(List<InvitedSpace> spaces) {
        return spaces.isEmpty() ? null : spaces.stream().map(InvitedSpace::name).toList();
    }

    private static ApiException invalidLink() {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
    }

    private record Valid(UUID id, UUID organizationId, String role, String email, Instant expiresAt,
            String createdByName) {
    }
}
