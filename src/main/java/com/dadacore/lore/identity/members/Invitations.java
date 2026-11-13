package com.dadacore.lore.identity.members;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.OrganizationRole;
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
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitation links to join the organization, passed on by the person who creates them: no mail
 * server is needed. A link is used once; it expires after the delay set in Security (7 days by
 * default).
 *
 * <p>An optional address reserves the link to that person and lets administrators follow it as
 * "Invitation pending". The spaces to join will be added with the spaces themselves.
 */
@Service
class Invitations {

    private final JdbcClient jdbc;
    private final OrganizationRepository organizations;
    private final UserAccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher hasher;
    private final AuditLog audit;
    private final Clock clock;

    Invitations(JdbcClient jdbc, OrganizationRepository organizations, UserAccountRepository accounts,
            MembershipRepository memberships, PasswordHasher hasher, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.accounts = accounts;
        this.memberships = memberships;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param email     the address the link is reserved to, if any
     * @param createdBy name of the person who created the link
     */
    record InvitationView(UUID id, String role, @Nullable String email, @Nullable String createdBy,
            Instant createdAt, Instant expiresAt) {
    }

    record CreatedInvitation(InvitationView invitation, String url) {
    }

    /** @param email optional: reserves the link to this address */
    record NewInvitation(String role, @Nullable String email) {
    }

    record InvitationDetails(String organizationName, @Nullable String invitedBy, String role,
            @Nullable String email, Instant expiresAt, int passwordMinLength) {
    }

    record JoinRequest(String token, String displayName, String email, String password) {
    }

    record Joined(UUID userId, UUID organizationId, int sessionTimeoutMinutes) {
    }

    List<InvitationView> pending(Viewer viewer) {
        viewer.requireAdmin();
        return jdbc.sql("""
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
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
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
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "invitation.created")
                .resource("invitation", id)
                .ip(viewer.ip())
                .detail("role", role.name())
                .detail("email", email)
                .build());
        InvitationView view = new InvitationView(id, role.name(), email, viewer.displayName(), now, expiresAt);
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

    InvitationDetails inspect(String token) {
        Valid valid = find(token, false).orElseThrow(Invitations::invalidLink);
        Organization organization = organizations.find(valid.organizationId()).orElseThrow(Invitations::invalidLink);
        return new InvitationDetails(organization.name(), valid.createdByName(), valid.role(), valid.email(),
                valid.expiresAt(), organizations.settings(organization.id()).passwordMinLength());
    }

    /**
     * Creates the account and its membership, in one transaction where the invitation is locked:
     * two clicks at the same time create a single account.
     *
     * <p>A former member (removed from the organization, whose account stays so that their name
     * remains in the history) comes back with their account, but only through an invitation
     * reserved to their address: an open link would let whoever holds it take over the account.
     */
    @Transactional
    Joined accept(JoinRequest request, String ip) {
        Valid valid = find(request.token(), true).orElseThrow(Invitations::invalidLink);
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
        UserAccount account;
        if (former.isPresent()) {
            account = former.get();
            accounts.updateProfile(account.id(), displayName, account.locale());
            accounts.updatePassword(account.id(), hasher.hash(password));
            accounts.updateStatus(account.id(), AccountStatus.ACTIVE);
        } else {
            try {
                account = accounts.create(email, displayName, hasher.hash(password), null);
            } catch (DuplicateKeyException e) {
                Checks.start().fail("email", "email_taken").orThrow();
                return null;
            }
        }
        OrganizationRole role = OrganizationRole.valueOf(valid.role());
        memberships.add(valid.organizationId(), account.id(), role);
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
                .detail("returning", former.isPresent())
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

    private static ApiException invalidLink() {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
    }

    private record Valid(UUID id, UUID organizationId, String role, String email, Instant expiresAt,
            String createdByName) {
    }
}
