package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.dadacore.lore.shared.CliCommand;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A5: recover access to an account, without a mail server.
 */
class PasswordResetApiTests extends ApiTest {

    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String NEW_PASSWORD = "a-brand-new-passphrase";

    @Autowired
    private List<CliCommand> commands;

    @Test
    void anAdministratorGeneratesALinkAndTheMemberChoosesANewPassword() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);

        Response link = owner.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link");
        assertThat(link.status()).isEqualTo(200);
        assertThat(link.text("url")).startsWith("http://localhost:" + port + "/reset-password#");
        String token = token(link.text("url"));

        Browser herve = browser();
        Response details = herve.post("/api/v1/password-reset/inspect", body("token", token));
        assertThat(details.text("displayName")).isEqualTo("Hervé Coulibaly");
        assertThat(details.text("email")).isEqualTo(HERVE);
        assertThat(details.text("createdBy")).isEqualTo(OWNER_NAME);
        assertThat(details.number("passwordMinLength")).isEqualTo(12);

        Response tooShort = herve.post("/api/v1/password-reset/complete", body("token", token, "password", "short"));
        assertThat(tooShort.fieldErrors()).containsExactly("password:too_short");

        Response done = herve.post("/api/v1/password-reset/complete", body("token", token, "password", NEW_PASSWORD));
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.text("user.email")).isEqualTo(HERVE);
        assertThat(herve.get("/api/v1/account").status()).isEqualTo(200);

        assertThat(browser().post("/api/v1/auth/login", body("email", HERVE, "password", PASSWORD)).status()).isEqualTo(401);
        assertThat(browser().post("/api/v1/auth/login", body("email", HERVE, "password", NEW_PASSWORD)).status()).isEqualTo(200);
        assertThat(auditActions()).contains("member.reset_link_created", "auth.password_reset");
    }

    @Test
    void theNewPasswordClosesEveryOtherSession() {
        Browser owner = setUpOrganization();
        Browser oldDevice = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String token = token(owner.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link").text("url"));

        browser().post("/api/v1/password-reset/complete", body("token", token, "password", NEW_PASSWORD));

        assertThat(oldDevice.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(sessionCount(HERVE)).isEqualTo(1);
    }

    @Test
    void aLinkWorksOnceAndANewLinkCancelsThePreviousOne() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String path = "/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link";
        String first = token(owner.post(path).text("url"));
        String second = token(owner.post(path).text("url"));

        Response replaced = browser().post("/api/v1/password-reset/inspect", body("token", first));
        assertThat(replaced.status()).isEqualTo(404);
        assertThat(replaced.code()).isEqualTo("invalid_link");

        assertThat(browser().post("/api/v1/password-reset/complete", body("token", second, "password", NEW_PASSWORD)).status())
                .isEqualTo(200);
        assertThat(browser().post("/api/v1/password-reset/complete", body("token", second, "password", NEW_PASSWORD)).code())
                .isEqualTo("invalid_link");
    }

    @Test
    void anExpiredLinkIsRefused() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String token = token(owner.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link").text("url"));
        jdbc.sql("update password_reset_token set expires_at = now() - interval '1 minute'").update();

        assertThat(browser().post("/api/v1/password-reset/inspect", body("token", token)).code()).isEqualTo("invalid_link");
    }

    @Test
    void theNewPasswordLiftsASignInBlock() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        for (int attempt = 1; attempt <= 5; attempt++) {
            browser().post("/api/v1/auth/login", body("email", HERVE, "password", "guess-" + attempt));
        }
        String token = token(owner.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link").text("url"));

        browser().post("/api/v1/password-reset/complete", body("token", token, "password", NEW_PASSWORD));

        assertThat(browser().post("/api/v1/auth/login", body("email", HERVE, "password", NEW_PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void onlyAdministratorsGenerateLinks() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        UUID ownerId = userId(OWNER_EMAIL);

        Response response = herve.post("/api/v1/admin/members/" + ownerId + "/password-reset-link");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("admin_only");
    }

    @Test
    void theServerCommandGivesALinkToTheOwner() {
        setUpOrganization();
        CliCommand resetPassword = commands.stream().filter(c -> c.name().equals("reset-password")).findFirst().orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = resetPassword.run(new String[] {"Wilfried.Kouakou@dada.group"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("Wilfried Kouakou", "/reset-password#");
        String link = printed.lines().filter(line -> line.contains("/reset-password#")).findFirst().orElseThrow().strip();
        Response details = browser().post("/api/v1/password-reset/inspect", body("token", token(link)));
        assertThat(details.status()).isEqualTo(200);
        assertThat(details.at("createdBy").isNull()).isTrue();

        assertThat(resetPassword.run(new String[] {"nobody@dada.group"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)))
                .isEqualTo(1);
    }
}
