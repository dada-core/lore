package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * A6: manage one's own account.
 */
class AccountApiTests extends ApiTest {

    @Test
    void nameAndLanguageAreSaved() {
        Browser owner = setUpOrganization();

        Response updated = owner.patch("/api/v1/account", body("displayName", "  Wilfried K.  ", "locale", "en"));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("displayName")).isEqualTo("Wilfried K.");
        assertThat(updated.text("interfaceLocale")).isEqualTo("en");
        Response session = owner.get("/api/v1/session");
        assertThat(session.text("user.displayName")).isEqualTo("Wilfried K.");
        assertThat(session.text("user.locale")).isEqualTo("en");

        Response followOrganization = owner.patch("/api/v1/account", body("locale", ""));
        assertThat(followOrganization.at("locale").isNull()).isTrue();
        assertThat(followOrganization.text("interfaceLocale")).isEqualTo("fr");
    }

    @Test
    void changingTheAddressRequiresThePassword() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Eli Brou", "eli.brou@dada.group");

        Response wrongPassword = owner.put("/api/v1/account/email",
                body("email", "wilfried@dada.group", "currentPassword", "not-my-password"));
        assertThat(wrongPassword.fieldErrors()).containsExactly("currentPassword:wrong_password");

        Response taken = owner.put("/api/v1/account/email",
                body("email", "Eli.Brou@dada.group", "currentPassword", PASSWORD));
        assertThat(taken.fieldErrors()).containsExactly("email:email_taken");

        Response changed = owner.put("/api/v1/account/email", body("email", "wilfried@dada.group", "currentPassword", PASSWORD));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(browser().post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(browser().post("/api/v1/auth/login", body("email", "wilfried@dada.group", "password", PASSWORD)).status()).isEqualTo(200);
        assertThat(auditActions()).contains("auth.email_changed");
    }

    @Test
    void changingThePasswordCanCloseTheOtherSessions() {
        Browser owner = setUpOrganization();
        Browser laptop = signIn(OWNER_EMAIL, PASSWORD);

        Response wrong = owner.put("/api/v1/account/password",
                body("currentPassword", "nope", "newPassword", "short", "signOutOtherSessions", true));
        assertThat(wrong.fieldErrors()).containsExactlyInAnyOrder("currentPassword:wrong_password", "newPassword:too_short");
        assertThat(wrong.at("errors.1.params.min").asInt()).isEqualTo(12);

        Response changed = owner.put("/api/v1/account/password",
                body("currentPassword", PASSWORD, "newPassword", "another-long-passphrase", "signOutOtherSessions", true));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.number("closedSessions")).isEqualTo(1);
        assertThat(laptop.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(owner.get("/api/v1/account").status()).isEqualTo(200);
    }

    @Test
    void theCurrentPasswordIsCountedLikeASignIn() {
        Browser owner = setUpOrganization();
        for (int attempt = 1; attempt <= 4; attempt++) {
            Response wrong = attempt % 2 == 0
                    ? owner.put("/api/v1/account/email", body("email", "wilfried@dada.group", "currentPassword", "guess-" + attempt))
                    : owner.put("/api/v1/account/password", body("currentPassword", "guess-" + attempt,
                            "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
            assertThat(wrong.fieldErrors()).as(wrong.toString()).containsExactly("currentPassword:wrong_password");
        }

        Response fifth = owner.put("/api/v1/account/password", body("currentPassword", "guess-5",
                "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
        assertThat(fifth.status()).as(fifth.toString()).isEqualTo(429);
        assertThat(fifth.code()).isEqualTo("login_locked");
        assertThat(fifth.number("retryAfterMinutes")).isEqualTo(15);

        Response evenWithTheRightPassword = owner.put("/api/v1/account/email",
                body("email", "wilfried@dada.group", "currentPassword", PASSWORD));
        assertThat(evenWithTheRightPassword.status()).as(evenWithTheRightPassword.toString()).isEqualTo(429);
        assertThat(evenWithTheRightPassword.code()).isEqualTo("login_locked");
        assertThat(owner.get("/api/v1/account").text("email")).isEqualTo(OWNER_EMAIL);
        // The same counters as the sign-in: from this machine, signing in is blocked as well.
        assertThat(browser().post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD)).status())
                .isEqualTo(429);
        assertThat(auditActions()).containsSequence("auth.login_failed", "auth.login_failed", "auth.login_failed",
                "auth.login_failed", "auth.login_failed", "auth.locked");
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'auth.login_failed' and actor_id = ?")
                .param(userId(OWNER_EMAIL)).query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void aBlockFromEverywhereDoesNotKeepTheSignedInPersonFromChangingTheirPassword() {
        Browser owner = setUpOrganization();
        // An attack spread over many machines: the address is blocked from everywhere.
        for (int attempt = 1; attempt <= 20; attempt++) {
            browser().from("203.0.113." + attempt)
                    .post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-" + attempt));
        }
        assertThat(browser().from("198.51.100.20").post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD))
                .status()).isEqualTo(429);

        // Signed in already, the owner proves they know the password and changes it.
        Response changed = owner.put("/api/v1/account/password", body("currentPassword", PASSWORD,
                "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);

        // A new password: the guesses against the former one no longer block the address.
        assertThat(browser().from("198.51.100.20").post("/api/v1/auth/login",
                body("email", OWNER_EMAIL, "password", "another-long-passphrase")).status()).isEqualTo(200);
    }

    @Test
    void aStolenSessionGetsNoMoreGuessesFromManyMachines() {
        Browser owner = setUpOrganization();
        for (int attempt = 1; attempt <= 4; attempt++) {
            Response wrong = owner.from("203.0.113." + attempt).put("/api/v1/account/password", body("currentPassword",
                    "guess-" + attempt, "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
            assertThat(wrong.fieldErrors()).as(wrong.toString()).containsExactly("currentPassword:wrong_password");
        }

        // A fifth machine: the session itself is blocked, whatever the address it comes from.
        Response fifth = owner.from("203.0.113.5").put("/api/v1/account/password", body("currentPassword", "guess-5",
                "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
        assertThat(fifth.status()).as(fifth.toString()).isEqualTo(429);
        assertThat(owner.from("203.0.113.6").put("/api/v1/account/email",
                body("email", "wilfried@dada.group", "currentPassword", PASSWORD)).status()).isEqualTo(429);

        // Signing in, from another machine, is still open: five failures are far from the block from everywhere.
        assertThat(browser().from("198.51.100.20").post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD))
                .status()).isEqualTo(200);
    }

    @Test
    void aTypoDuringABlockFromEverywhereIsNotABlock() {
        Browser owner = setUpOrganization();
        for (int attempt = 1; attempt <= 20; attempt++) {
            browser().from("203.0.113." + attempt)
                    .post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-" + attempt));
        }

        String blockEnd = "select locked_until::text from login_throttle where key like 'address:%'";
        String before = jdbc.sql(blockEnd).query(String.class).single();

        Response typo = owner.put("/api/v1/account/password", body("currentPassword", "typo-" + PASSWORD,
                "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
        assertThat(typo.fieldErrors()).as(typo.toString()).containsExactly("currentPassword:wrong_password");
        // The block from everywhere is neither pushed back nor written again in the person's name.
        assertThat(jdbc.sql(blockEnd).query(String.class).single()).isEqualTo(before);
        assertThat(auditActions().stream().filter("auth.locked"::equals)).hasSize(1);
        Response changed = owner.put("/api/v1/account/password", body("currentPassword", PASSWORD,
                "newPassword", "another-long-passphrase", "signOutOtherSessions", false));
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
    }

    @Test
    void theRightCurrentPasswordDoesNotLiftABlockFromEverywhere() {
        Browser owner = setUpOrganization();
        for (int attempt = 1; attempt <= 20; attempt++) {
            browser().from("203.0.113." + attempt)
                    .post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-" + attempt));
        }

        // The address is changed without a new password: the attack goes on against the same one.
        Response changed = owner.put("/api/v1/account/email", body("email", OWNER_EMAIL, "currentPassword", PASSWORD));
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(browser().from("198.51.100.20").post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD))
                .status()).isEqualTo(429);
    }

    @Test
    void openSessionsCanBeListedAndClosed() {
        Browser owner = setUpOrganization();
        Browser other = browser().userAgent(Browser.FIREFOX_UBUNTU);
        other.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD));

        Response sessions = owner.get("/api/v1/account/sessions");
        assertThat(sessions.json().size()).isEqualTo(2);
        assertThat(sessions.flag("0.current")).isTrue();
        assertThat(sessions.text("0.browser")).isEqualTo("Chrome");
        assertThat(sessions.text("0.system")).isEqualTo("Windows");
        assertThat(sessions.text("1.browser")).isEqualTo("Firefox");
        assertThat(sessions.text("1.system")).isEqualTo("Ubuntu");
        assertThat(sessions.text("1.id")).hasSize(24).doesNotContain(other.cookie("LORE_SESSION"));

        assertThat(owner.delete("/api/v1/account/sessions/" + sessions.text("1.id")).status()).isEqualTo(204);
        assertThat(other.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(owner.delete("/api/v1/account/sessions/000000000000000000000000").status()).isEqualTo(404);
    }

    @Test
    void everyOtherSessionCanBeClosedAtOnce() {
        Browser owner = setUpOrganization();
        Browser second = signIn(OWNER_EMAIL, PASSWORD);
        Browser third = signIn(OWNER_EMAIL, PASSWORD);

        Response closed = owner.post("/api/v1/account/sessions/sign-out-others");

        assertThat(closed.number("closedSessions")).isEqualTo(2);
        assertThat(second.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(third.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(owner.get("/api/v1/account").status()).isEqualTo(200);
    }
}
