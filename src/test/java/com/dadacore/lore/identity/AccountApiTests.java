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
