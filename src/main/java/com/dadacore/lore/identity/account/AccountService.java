package com.dadacore.lore.identity.account;

import java.util.Locale;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.identity.security.OpenSession;
import com.dadacore.lore.identity.security.PasswordHasher;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * My account: profile, preferences, password and open sessions.
 */
@Service
class AccountService {

    private final UserAccountRepository accounts;
    private final OrganizationRepository organizations;
    private final PasswordHasher hasher;
    private final LoginSessions sessions;
    private final AuditLog audit;

    AccountService(UserAccountRepository accounts, OrganizationRepository organizations,
            PasswordHasher hasher, LoginSessions sessions, AuditLog audit) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.hasher = hasher;
        this.sessions = sessions;
        this.audit = audit;
    }

    /**
     * @param locale            the person's choice, or null to follow the organization
     * @param interfaceLocale   the language actually used by the interface
     * @param passwordMinLength minimum length of a new password, set by the organization
     */
    record AccountView(String displayName, String email, @Nullable String locale, String interfaceLocale,
            String role, int passwordMinLength) {
    }

    /** Fields left out keep their value; an empty language follows the organization. */
    record ProfileUpdate(@Nullable String displayName, @Nullable String locale) {
    }

    record EmailChange(String email, String currentPassword) {
    }

    record PasswordChange(String currentPassword, String newPassword, boolean signOutOtherSessions) {
    }

    AccountView get(Viewer viewer) {
        UserAccount account = accounts.find(viewer.userId()).orElseThrow(ApiException::unauthenticated);
        OrganizationSettings settings = organizations.settings(viewer.organizationId());
        String interfaceLocale = account.locale() != null ? account.locale() : settings.defaultLocale();
        return new AccountView(account.displayName(), account.email(), account.locale(), interfaceLocale,
                viewer.role().name(), settings.passwordMinLength());
    }

    /** Fields left out of the request keep their value; an empty language follows the organization. */
    @Transactional
    AccountView updateProfile(Viewer viewer, ProfileUpdate update) {
        UserAccount account = accounts.find(viewer.userId()).orElseThrow(ApiException::unauthenticated);
        Checks checks = Checks.start();
        String displayName = update.displayName() == null
                ? account.displayName()
                : checks.text("displayName", update.displayName(), 120);
        String locale = account.locale();
        if (update.locale() != null) {
            locale = update.locale().isBlank() ? null : update.locale().strip().toLowerCase(Locale.ROOT);
            if (locale != null && !locale.equals("fr") && !locale.equals("en")) {
                checks.fail("locale", "invalid_value");
            }
        }
        checks.orThrow();
        accounts.updateProfile(account.id(), displayName, locale);
        return get(viewer);
    }

    @Transactional
    AccountView changeEmail(Viewer viewer, EmailChange change) {
        UserAccount account = accounts.find(viewer.userId()).orElseThrow(ApiException::unauthenticated);
        Checks checks = Checks.start();
        String email = checks.email("email", change.email());
        if (!hasher.matches(change.currentPassword(), account.passwordHash())) {
            checks.fail("currentPassword", "wrong_password");
        }
        checks.orThrow();
        if (email.equals(account.email())) {
            return get(viewer);
        }
        // Checked first for a clear message; the unique index settles a race.
        if (accounts.emailTaken(email)) {
            Checks.start().fail("email", "email_taken").orThrow();
        }
        try {
            accounts.updateEmail(account.id(), email);
        } catch (DuplicateKeyException e) {
            Checks.start().fail("email", "email_taken").orThrow();
        }
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "auth.email_changed")
                .resource("account", account.id())
                .ip(viewer.ip())
                .detail("before", account.email())
                .detail("after", email)
                .build());
        return get(viewer);
    }

    /** @return the number of other sessions closed */
    @Transactional
    int changePassword(Viewer viewer, PasswordChange change) {
        UserAccount account = accounts.find(viewer.userId()).orElseThrow(ApiException::unauthenticated);
        int minLength = organizations.settings(viewer.organizationId()).passwordMinLength();
        Checks checks = Checks.start();
        if (!hasher.matches(change.currentPassword(), account.passwordHash())) {
            checks.fail("currentPassword", "wrong_password");
        }
        String password = checks.password("newPassword", change.newPassword(), minLength);
        checks.orThrow();
        accounts.updatePassword(account.id(), hasher.hash(password));
        audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "auth.password_changed")
                .resource("account", account.id())
                .ip(viewer.ip())
                .build());
        return change.signOutOtherSessions() ? sessions.closeAll(account.id(), viewer.sessionId()) : 0;
    }

    java.util.List<OpenSession> sessions(Viewer viewer) {
        return sessions.list(viewer.userId(), viewer.sessionId());
    }

    void closeSession(Viewer viewer, String handle) {
        if (!sessions.closeOne(viewer.userId(), handle)) {
            throw ApiException.notFound();
        }
    }

    int closeOtherSessions(Viewer viewer) {
        return sessions.closeAll(viewer.userId(), viewer.sessionId());
    }
}
