package com.dadacore.lore.identity.login;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.identity.account.MembershipRepository;
import com.dadacore.lore.identity.account.UserAccount;
import com.dadacore.lore.identity.account.UserAccountRepository;
import com.dadacore.lore.identity.organization.Organization;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.security.PasswordHasher;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import org.springframework.stereotype.Service;

/**
 * Checks a sign-in attempt.
 *
 * <p>Not transactional on purpose: a failure must be counted even though the request ends with an
 * error. Every answer to a wrong address or a wrong password is the same, so that the sign-in form
 * does not reveal which accounts exist.
 */
@Service
class LoginService {

    private final OrganizationRepository organizations;
    private final UserAccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher hasher;
    private final LoginThrottle throttle;
    private final Clock clock;

    LoginService(OrganizationRepository organizations, UserAccountRepository accounts,
            MembershipRepository memberships, PasswordHasher hasher, LoginThrottle throttle, Clock clock) {
        this.organizations = organizations;
        this.accounts = accounts;
        this.memberships = memberships;
        this.hasher = hasher;
        this.throttle = throttle;
        this.clock = clock;
    }

    LoginResult attempt(String rawEmail, String password, String ip) {
        Organization organization = organizations.installation()
                .orElseThrow(() -> ApiException.conflict("setup_required"));
        OrganizationSettings settings = organizations.settings(organization.id());

        Checks checks = Checks.start();
        String email = checks.text("email", rawEmail, 254);
        if (password == null || password.isEmpty()) {
            checks.fail("password", "required");
        }
        checks.orThrow();
        String address = email.toLowerCase(java.util.Locale.ROOT);
        return throttle.oneAtATime(address, () -> check(organization, settings, address, password, ip));
    }

    /** Within the turn of the address: the block, the password, then the failure counted. */
    private LoginResult check(Organization organization, OrganizationSettings settings, String email,
            String password, String ip) {
        Optional<Instant> lockedUntil = throttle.lockedUntil(email, ip);
        if (lockedUntil.isPresent()) {
            return new LoginResult.Locked(throttle.minutesUntil(lockedUntil.get()));
        }

        Optional<UserAccount> account = accounts.findByEmail(email);
        boolean passwordMatches;
        if (account.isPresent()) {
            passwordMatches = hasher.matches(password, account.get().passwordHash());
        } else {
            hasher.matchDecoy(password);
            passwordMatches = false;
        }

        if (passwordMatches) {
            UserAccount user = account.get();
            Optional<OrganizationRole> role = memberships.role(organization.id(), user.id());
            // Only revealed to someone who knows the password.
            if (!user.isActive() || role.isEmpty()) {
                return new LoginResult.Disabled();
            }
            throttle.clear(email);
            accounts.recordLogin(user.id(), clock.instant());
            return new LoginResult.Success(user.id(), organization.id(), settings.sessionTimeoutMinutes());
        }

        UUID accountId = account.map(UserAccount::id).orElse(null);
        LoginThrottle.Failure failure = throttle.recordFailure(organization.id(), settings, null, accountId, email, ip);
        if (failure.locked()) {
            return new LoginResult.Locked(settings.loginLockoutMinutes());
        }
        return new LoginResult.Invalid(failure.remainingAttempts(), settings.loginLockoutMinutes());
    }

    sealed interface LoginResult {

        record Success(UUID userId, UUID organizationId, int sessionTimeoutMinutes) implements LoginResult {
        }

        record Invalid(int remainingAttempts, int lockoutMinutes) implements LoginResult {
        }

        record Locked(long retryAfterMinutes) implements LoginResult {
        }

        record Disabled() implements LoginResult {
        }
    }
}
