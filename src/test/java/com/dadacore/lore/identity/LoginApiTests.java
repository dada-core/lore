package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * A3: sign in, and the protections around it.
 */
class LoginApiTests extends ApiTest {

    @Test
    void rightPasswordOpensASession() {
        setUpOrganization();
        Browser browser = browser();

        Response login = browser.post("/api/v1/auth/login", body("email", " Wilfried.Kouakou@Dada.Group ", "password", PASSWORD));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("user.email")).isEqualTo(OWNER_EMAIL);
        assertThat(browser.get("/api/v1/account").status()).isEqualTo(200);
        assertThat(jdbc.sql("select last_login_at is not null from user_account").query(Boolean.class).single()).isTrue();
    }

    @Test
    void wrongPasswordAndUnknownAddressGetTheSameAnswer() {
        setUpOrganization();

        Response wrongPassword = browser().post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "wrong-password"));
        Response unknownAddress = browser().post("/api/v1/auth/login", body("email", "nobody@dada.group", "password", "wrong-password"));

        for (Response response : new Response[] {wrongPassword, unknownAddress}) {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("invalid_credentials");
            assertThat(response.number("remainingAttempts")).isEqualTo(4);
            assertThat(response.number("lockoutMinutes")).isEqualTo(15);
        }
        assertThat(auditActions()).containsExactly("organization.created", "auth.login_failed", "auth.login_failed");
    }

    @Test
    void fiveFailuresBlockTheAddressFifteenMinutes() {
        setUpOrganization();
        Browser attacker = browser();
        for (int attempt = 1; attempt <= 4; attempt++) {
            Response response = attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-" + attempt));
            assertThat(response.number("remainingAttempts")).isEqualTo(5 - attempt);
        }

        Response fifth = attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-5"));
        assertThat(fifth.status()).isEqualTo(429);
        assertThat(fifth.code()).isEqualTo("login_locked");
        assertThat(fifth.number("retryAfterMinutes")).isEqualTo(15);

        Response evenWithTheRightPassword = attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD));
        assertThat(evenWithTheRightPassword.status()).isEqualTo(429);
        assertThat(auditActions()).contains("auth.locked");
    }

    @Test
    void aBlockFromOneMachineDoesNotLockTheOwnerOut() {
        setUpOrganization();
        Browser attacker = browser().from("203.0.113.7");
        for (int attempt = 1; attempt <= 5; attempt++) {
            attacker.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-" + attempt));
        }

        Response owner = browser().from("198.51.100.20")
                .post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD));

        assertThat(owner.status()).isEqualTo(200);
    }

    @Test
    void theBlockEnds() {
        setUpOrganization();
        Browser browser = browser();
        for (int attempt = 1; attempt <= 5; attempt++) {
            browser.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", "guess-" + attempt));
        }
        jdbc.sql("update login_throttle set locked_until = now() - interval '1 second'").update();

        assertThat(browser.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD)).status())
                .isEqualTo(200);
    }

    @Test
    void aDisabledAccountIsOnlyReportedWithTheRightPassword() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", "herve.coulibaly@dada.group");
        owner.post("/api/v1/admin/members/" + userId("herve.coulibaly@dada.group") + "/disable");

        Response wrong = browser().post("/api/v1/auth/login",
                body("email", "herve.coulibaly@dada.group", "password", "wrong-password"));
        Response right = browser().post("/api/v1/auth/login",
                body("email", "herve.coulibaly@dada.group", "password", PASSWORD));

        assertThat(wrong.code()).isEqualTo("invalid_credentials");
        assertThat(right.status()).isEqualTo(403);
        assertThat(right.code()).isEqualTo("account_disabled");
    }

    @Test
    void signingOutDeletesTheSessionOnTheServer() {
        Browser owner = setUpOrganization();
        assertThat(sessionCount(OWNER_EMAIL)).isEqualTo(1);

        assertThat(owner.post("/api/v1/auth/logout").status()).isEqualTo(204);

        assertThat(sessionCount(OWNER_EMAIL)).isZero();
        assertThat(owner.get("/api/v1/account").status()).isEqualTo(401);
    }

    @Test
    void signingInAgainReplacesTheSessionIdentifier() {
        setUpOrganization();
        Browser browser = signIn(OWNER_EMAIL, PASSWORD);
        String first = browser.cookie("LORE_SESSION");
        int sessions = sessionCount(OWNER_EMAIL);

        browser.post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD));

        assertThat(browser.cookie("LORE_SESSION")).isNotEqualTo(first);
        assertThat(sessionCount(OWNER_EMAIL)).isEqualTo(sessions);
    }

    @Test
    void requestsWithoutCsrfTokenAreRefused() {
        setUpOrganization();

        Response response = browser().withoutCsrfToken()
                .post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", PASSWORD));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("csrf");
    }

    @Test
    void theApiAnswersInProblemFormat() {
        Browser owner = setUpOrganization();

        Response anonymous = browser().get("/api/v1/account");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("unauthenticated");
        assertThat(anonymous.contentType()).startsWith("application/problem+json");

        Response unknown = owner.get("/api/v1/nothing-here");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("not_found");

        Response malformedId = owner.post("/api/v1/admin/members/not-a-uuid/disable");
        assertThat(malformedId.status()).isEqualTo(404);
    }

    @Test
    void securityHeadersAreSent() {
        Response response = browser().get("/api/v1/session");

        assertThat(response.headers().firstValue("Content-Security-Policy").orElse(""))
                .contains("default-src 'self'", "frame-ancestors 'none'");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }
}
