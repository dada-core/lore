package com.dadacore.lore.identity.account;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.login.LoginThrottle;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.identity.security.OpenSession;
import com.dadacore.lore.identity.security.PasswordHasher;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.shared.Secrets;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * My account: profile, preferences, password and open sessions.
 *
 * <p>The current password asked to change the address or the password counts on the sign-in
 * counters ({@link LoginThrottle}): wrong ones are written in the audit log, and too many block it
 * as they block the sign-in. These checks are therefore made outside any transaction, so that a
 * refused request still counts; the changes are then saved in one.
 */
@Service
class AccountService {

    private final UserAccountRepository accounts;
    private final OrganizationRepository organizations;
    private final PasswordHasher hasher;
    private final LoginThrottle throttle;
    private final LoginSessions sessions;
    private final AuditLog audit;
    private final TransactionTemplate transaction;

    AccountService(UserAccountRepository accounts, OrganizationRepository organizations,
            PasswordHasher hasher, LoginThrottle throttle, LoginSessions sessions, AuditLog audit,
            TransactionTemplate transaction) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.hasher = hasher;
        this.throttle = throttle;
        this.sessions = sessions;
        this.audit = audit;
        this.transaction = transaction;
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

        /** The password stays out of the logs. */
        @Override
        public String toString() {
            return "EmailChange[email=" + email + ", currentPassword=" + Secrets.hide(currentPassword) + "]";
        }
    }

    record PasswordChange(String currentPassword, String newPassword, boolean signOutOtherSessions) {

        /** The passwords stay out of the logs. */
        @Override
        public String toString() {
            return "PasswordChange[currentPassword=" + Secrets.hide(currentPassword) + ", newPassword="
                    + Secrets.hide(newPassword) + ", signOutOtherSessions=" + signOutOtherSessions + "]";
        }
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

    /** {@code 429 login_locked} when the address is blocked, now or by this wrong password. */
    AccountView changeEmail(Viewer viewer, EmailChange change) {
        UserAccount account = accounts.find(viewer.userId()).orElseThrow(ApiException::unauthenticated);
        Checks checks = Checks.start();
        String email = checks.email("email", change.email());
        if (!currentPasswordMatches(viewer, account, organizations.settings(viewer.organizationId()), change.currentPassword())) {
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
        transaction.executeWithoutResult(status -> {
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
        });
        return get(viewer);
    }

    /**
     * {@code 429 login_locked} when the address is blocked, now or by this wrong password.
     *
     * @return the number of other sessions closed
     */
    int changePassword(Viewer viewer, PasswordChange change) {
        UserAccount account = accounts.find(viewer.userId()).orElseThrow(ApiException::unauthenticated);
        OrganizationSettings settings = organizations.settings(viewer.organizationId());
        Checks checks = Checks.start();
        if (!currentPasswordMatches(viewer, account, settings, change.currentPassword())) {
            checks.fail("currentPassword", "wrong_password");
        }
        String password = checks.password("newPassword", change.newPassword(), settings.passwordMinLength());
        checks.orThrow();
        String hash = hasher.hash(password);
        Integer closed = transaction.execute(status -> {
            accounts.updatePassword(account.id(), hash);
            audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "auth.password_changed")
                    .resource("account", account.id())
                    .ip(viewer.ip())
                    .build());
            return change.signOutOtherSessions() ? sessions.closeAll(account.id(), viewer.sessionId()) : 0;
        });
        // A new password: the guesses made against the former one no longer count.
        throttle.clear(account.email());
        return closed == null ? 0 : closed;
    }

    /**
     * Checks the current password the way the sign-in checks a password, on the same counters (the
     * address and the IP of the request) and on one of the session: a session left open, or stolen,
     * gives no more guesses than the sign-in form, from one machine or from many. A block of the
     * address from everywhere, which anyone can cause by failing on purpose, neither refuses it nor
     * answers a wrong one with a block: the person signed in can still change their password. An
     * empty password is refused without being counted.
     *
     * @throws ApiException {@code 429 login_locked} when the address is blocked from this IP, or the
     *                      session by its wrong current passwords, now or by this one
     */
    private boolean currentPasswordMatches(Viewer viewer, UserAccount account, OrganizationSettings settings,
            @Nullable String password) {
        return throttle.oneAtATime(account.email(), () -> checkCurrentPassword(viewer, account, settings, password));
    }

    /** Within the turn of the address, as at sign-in. */
    private boolean checkCurrentPassword(Viewer viewer, UserAccount account, OrganizationSettings settings,
            @Nullable String password) {
        Optional<Instant> lockedUntil = throttle.lockedForSessionUntil(account.email(), viewer.ip(), viewer.sessionId());
        if (lockedUntil.isPresent()) {
            throw LoginThrottle.locked(throttle.minutesUntil(lockedUntil.get()));
        }
        if (password == null || password.isEmpty()) {
            return false;
        }
        if (hasher.matches(password, account.passwordHash())) {
            throttle.clearForSession(account.email(), viewer.ip(), viewer.sessionId());
            return true;
        }
        LoginThrottle.Failure failure = throttle.recordFailure(viewer.organizationId(), settings, viewer.userId(),
                account.id(), account.email(), viewer.ip(), viewer.sessionId());
        if (failure.pairLocked() || failure.sessionLocked()) {
            throw LoginThrottle.locked(settings.loginLockoutMinutes());
        }
        return false;
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
