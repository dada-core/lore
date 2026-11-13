package com.dadacore.lore.identity.members;

import java.time.Instant;
import java.util.UUID;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.account.AccountStatus;
import com.dadacore.lore.identity.account.MemberRow;
import com.dadacore.lore.identity.account.MembershipRepository;
import com.dadacore.lore.identity.account.UserAccountRepository;
import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.shared.PageQuery;
import com.dadacore.lore.shared.PageResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration, Members: roles, deactivation, removal and reset links.
 *
 * <p>Rules: only owners grant, withdraw or act on the owner role; the organization always keeps an
 * active owner; nobody deactivates or removes their own account from this screen.
 */
@Service
class MembersService {

    private final MembershipRepository memberships;
    private final UserAccountRepository accounts;
    private final PasswordResets resets;
    private final LoginSessions sessions;
    private final AuditLog audit;

    MembersService(MembershipRepository memberships, UserAccountRepository accounts, PasswordResets resets,
            LoginSessions sessions, AuditLog audit) {
        this.memberships = memberships;
        this.accounts = accounts;
        this.resets = resets;
        this.sessions = sessions;
        this.audit = audit;
    }

    /**
     * @param lastLoginAt null when the person has never signed in
     * @param manageable  true when the viewer may change this member (the interface shows the menu)
     */
    record MemberView(UUID id, String displayName, String email, String role, String status,
            @Nullable Instant lastLoginAt, Instant joinedAt, boolean self, boolean manageable) {
    }

    PageResponse<MemberView> list(Viewer viewer, String search, PageQuery page) {
        viewer.requireAdmin();
        PageResponse<MemberRow> rows = memberships.members(viewer.organizationId(), search, page);
        return new PageResponse<>(rows.items().stream().map(row -> view(viewer, row)).toList(),
                rows.page(), rows.size(), rows.total());
    }

    @Transactional
    MemberView changeRole(Viewer viewer, UUID memberId, String requestedRole) {
        MemberRow member = member(viewer, memberId);
        Checks checks = Checks.start();
        OrganizationRole role = checks.oneOf("role", requestedRole, OrganizationRole.class);
        checks.orThrow();
        if (role == member.role()) {
            return view(viewer, member);
        }
        if ((role == OrganizationRole.OWNER || member.role() == OrganizationRole.OWNER) && !viewer.isOwner()) {
            throw ApiException.forbidden("owner_only");
        }
        requireAnotherOwner(viewer, member);
        memberships.changeRole(viewer.organizationId(), memberId, role);
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "member.role_changed")
                .resource("account", memberId)
                .ip(viewer.ip())
                .detail("name", member.displayName())
                .detail("before", member.role().name())
                .detail("after", role.name())
                .build());
        return view(viewer, member(viewer, memberId));
    }

    /** The account can no longer sign in, and all its sessions are closed. */
    @Transactional
    MemberView disable(Viewer viewer, UUID memberId) {
        MemberRow member = manageable(viewer, memberId);
        if (member.status() == AccountStatus.DISABLED) {
            return view(viewer, member);
        }
        requireAnotherOwner(viewer, member);
        accounts.updateStatus(memberId, AccountStatus.DISABLED);
        resets.revokeAll(memberId);
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "member.disabled")
                .resource("account", memberId)
                .ip(viewer.ip())
                .detail("name", member.displayName())
                .build());
        sessions.closeAll(memberId, null);
        return view(viewer, member(viewer, memberId));
    }

    @Transactional
    MemberView enable(Viewer viewer, UUID memberId) {
        MemberRow member = manageable(viewer, memberId);
        if (member.status() == AccountStatus.ACTIVE) {
            return view(viewer, member);
        }
        accounts.updateStatus(memberId, AccountStatus.ACTIVE);
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "member.enabled")
                .resource("account", memberId)
                .ip(viewer.ip())
                .detail("name", member.displayName())
                .build());
        return view(viewer, member(viewer, memberId));
    }

    /**
     * Removes the membership and closes the sessions. The account itself remains, so that its name
     * stays attached to the versions it imported.
     */
    @Transactional
    void remove(Viewer viewer, UUID memberId) {
        MemberRow member = manageable(viewer, memberId);
        requireAnotherOwner(viewer, member);
        memberships.remove(viewer.organizationId(), memberId);
        resets.revokeAll(memberId);
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "member.removed")
                .resource("account", memberId)
                .ip(viewer.ip())
                .detail("name", member.displayName())
                .detail("email", member.email())
                .build());
        sessions.closeAll(memberId, null);
    }

    @Transactional
    PasswordResets.CreatedLink resetLink(Viewer viewer, UUID memberId, String baseUrl) {
        MemberRow member = manageable(viewer, memberId);
        if (member.status() != AccountStatus.ACTIVE) {
            throw ApiException.conflict("account_disabled");
        }
        return resets.create(viewer.organizationId(), accounts.find(memberId).orElseThrow(ApiException::notFound),
                viewer.userId(), viewer.ip(), baseUrl);
    }

    private MemberRow member(Viewer viewer, UUID memberId) {
        viewer.requireAdmin();
        return memberships.member(viewer.organizationId(), memberId).orElseThrow(ApiException::notFound);
    }

    private MemberRow manageable(Viewer viewer, UUID memberId) {
        MemberRow member = member(viewer, memberId);
        if (member.userId().equals(viewer.userId())) {
            throw ApiException.conflict("own_account");
        }
        if (member.role() == OrganizationRole.OWNER && !viewer.isOwner()) {
            throw ApiException.forbidden("owner_only");
        }
        return member;
    }

    private void requireAnotherOwner(Viewer viewer, MemberRow member) {
        if (member.role() == OrganizationRole.OWNER && member.status() == AccountStatus.ACTIVE
                && memberships.lockActiveOwners(viewer.organizationId()) <= 1) {
            throw ApiException.conflict("last_owner");
        }
    }

    private static MemberView view(Viewer viewer, MemberRow row) {
        boolean self = row.userId().equals(viewer.userId());
        boolean manageable = viewer.isAdmin() && !self && (row.role() != OrganizationRole.OWNER || viewer.isOwner());
        return new MemberView(row.userId(), row.displayName(), row.email(), row.role().name(), row.status().name(),
                row.lastLoginAt(), row.joinedAt(), self, manageable);
    }
}
