package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * F5, Security and sign-in: every setting takes effect, not only its value. Saving a setting is
 * covered by {@link OrganizationSettingsApiTests}; here, what it changes for the next sign-ins,
 * sessions, links and passwords.
 */
class SecuritySettingsApiTests extends ApiTest {

    private static final String HERVE = "herve.coulibaly@dada.group";
    /** 15 characters: refused once the minimum is 16. */
    private static final String FIFTEEN_CHARACTERS = "fifteen-chars!!";

    @Test
    void theAttemptLimitAndTheLockoutDurationApplyToTheNextSignIns() {
        Browser owner = setUpOrganization();
        assertThat(owner.patch("/api/v1/admin/organization", body("loginMaxAttempts", 3, "loginLockoutMinutes", 60)).status())
                .isEqualTo(200);

        Browser attacker = browser();
        Response first = attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-1"));
        assertThat(first.number("remainingAttempts")).isEqualTo(2);
        assertThat(first.number("lockoutMinutes")).isEqualTo(60);
        attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-2"));
        Response third = attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-3"));

        assertThat(third.status()).isEqualTo(429);
        assertThat(third.number("retryAfterMinutes")).isEqualTo(60);
        // Compared with the clock of the application, which set it (the database may run on another clock).
        Instant lockedUntil = jdbc.sql("select max(locked_until) from login_throttle").query(java.sql.Timestamp.class).single().toInstant();
        assertThat(Duration.between(Instant.now(), lockedUntil)).isBetween(Duration.ofMinutes(59), Duration.ofMinutes(60));
        assertThat(attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD)).status())
                .isEqualTo(429);
    }

    @Test
    void theSessionTimeoutAppliesToTheSessionsOpenedAfterwards() {
        Browser owner = setUpOrganization();
        owner.patch("/api/v1/admin/organization", body("sessionTimeoutMinutes", 30));

        Browser browser = signIn(OWNER_EMAIL, PASSWORD);
        assertThat(lastSessionTimeoutSeconds()).isEqualTo(30 * 60);

        // 29 minutes without activity: the session is still open, and this request renews it.
        idleFor(Duration.ofMinutes(29));
        assertThat(browser.get("/api/v1/account").status()).isEqualTo(200);
        // 31 minutes: it has expired.
        idleFor(Duration.ofMinutes(31));
        Response expired = browser.get("/api/v1/account");
        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.code()).isEqualTo("unauthenticated");
    }

    @Test
    void invitationLinksLastTheConfiguredTime() {
        Browser owner = setUpOrganization();
        owner.patch("/api/v1/admin/organization", body("invitationValidityHours", 48));

        Response invitation = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));

        Duration validity = Duration.between(Instant.now(), Instant.parse(invitation.text("invitation.expiresAt")));
        assertThat(validity).isBetween(Duration.ofHours(48).minusMinutes(5), Duration.ofHours(48));
        // The link says the same date when it is created, opened, and listed among pending invitations.
        Response details = browser().post("/api/v1/invitations/inspect", body("token", token(invitation.text("url"))));
        assertThat(details.text("expiresAt")).isEqualTo(invitation.text("invitation.expiresAt"));
        assertThat(owner.get("/api/v1/admin/invitations").text("0.expiresAt")).isEqualTo(invitation.text("invitation.expiresAt"));
    }

    @Test
    void theMinimumLengthAppliesWhereverAPasswordIsChosen() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        owner.patch("/api/v1/admin/organization", body("passwordMinLength", 16));

        // Joining.
        Response invitation = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));
        String token = token(invitation.text("url"));
        assertThat(browser().post("/api/v1/invitations/inspect", body("token", token)).number("passwordMinLength")).isEqualTo(16);
        Response join = browser().post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Eli Brou", "email", "eli.brou@dada.group",
                "password", FIFTEEN_CHARACTERS));
        assertThat(join.fieldErrors()).containsExactly("password:too_short");

        // Resetting.
        Response link = owner.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link");
        String resetToken = token(link.text("url"));
        assertThat(browser().post("/api/v1/password-reset/inspect", body("token", resetToken)).number("passwordMinLength"))
                .isEqualTo(16);
        assertThat(browser().post("/api/v1/password-reset/complete", body("token", resetToken, "password", FIFTEEN_CHARACTERS))
                .fieldErrors()).containsExactly("password:too_short");

        // Changing one's own password, from My account (the interface shows the minimum).
        assertThat(owner.get("/api/v1/account").number("passwordMinLength")).isEqualTo(16);
        Response change = owner.put("/api/v1/account/password", body(
                "currentPassword", PASSWORD, "newPassword", FIFTEEN_CHARACTERS, "signOutOtherSessions", false));
        assertThat(change.fieldErrors()).containsExactly("newPassword:too_short");
        // Existing passwords keep working: the minimum applies to new ones.
        assertThat(signIn(HERVE, PASSWORD).get("/api/v1/account").status()).isEqualTo(200);
    }

    @Test
    void theLanguageAndTimeZoneApplyToEveryoneWhoHasNotChosen() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        herve.patch("/api/v1/account", body("locale", "fr"));

        owner.patch("/api/v1/admin/organization", body("defaultLocale", "en", "timeZone", "Europe/Paris"));

        Response session = owner.get("/api/v1/session");
        assertThat(session.text("user.locale")).isEqualTo("en");
        assertThat(session.text("organization.defaultLocale")).isEqualTo("en");
        assertThat(session.text("organization.timeZone")).isEqualTo("Europe/Paris");
        // A person who chose a language keeps it.
        assertThat(herve.get("/api/v1/session").text("user.locale")).isEqualTo("fr");
    }

    private int lastSessionTimeoutSeconds() {
        return jdbc.sql("select max_inactive_interval from spring_session order by creation_time desc limit 1")
                .query(Integer.class).single();
    }

    /** Moves the last activity of every session back in time, as if nobody had used them. */
    private void idleFor(Duration duration) {
        long lastAccess = Instant.now().minus(duration).toEpochMilli();
        jdbc.sql("update spring_session set last_access_time = ?, expiry_time = ? + max_inactive_interval * 1000::bigint")
                .params(lastAccess, lastAccess)
                .update();
    }
}
