package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * A2: configure Lore at first start.
 */
class SetupApiTests extends ApiTest {

    @Test
    void freshInstallationAsksForTheSetup() {
        Response session = browser().get("/api/v1/session");
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.flag("setupRequired")).isTrue();
        assertThat(session.at("organization").isNull()).isTrue();
        assertThat(session.at("user").isNull()).isTrue();

        Response setup = browser().get("/api/v1/setup");
        assertThat(setup.flag("required")).isTrue();
        assertThat(setup.flag("publicUrlFromEnvironment")).isFalse();
    }

    @Test
    void stepOneChecksTheSetupCodeFromTheLogs() {
        Browser visitor = browser();
        Response wrong = visitor.post("/api/v1/setup/verify-code", body("setupCode", "AAAA-BBBB-CCCC"));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.fieldErrors()).containsExactly("setupCode:invalid_setup_code");

        Response right = visitor.post("/api/v1/setup/verify-code", body("setupCode", " test-SETUP-code "));
        assertThat(right.status()).isEqualTo(204);
    }

    @Test
    void setupCreatesTheOrganizationAndItsOwnerAndSignsIn() {
        Browser owner = setUpOrganization();

        Response session = owner.get("/api/v1/session");
        assertThat(session.flag("setupRequired")).isFalse();
        assertThat(session.text("organization.name")).isEqualTo("DadaGroup");
        assertThat(session.text("user.displayName")).isEqualTo(OWNER_NAME);
        assertThat(session.text("user.role")).isEqualTo("OWNER");
        assertThat(session.text("user.locale")).isEqualTo("fr");

        assertThat(jdbc.sql("select slug from organization").query(String.class).single()).isEqualTo("dadagroup");
        assertThat(jdbc.sql("select time_zone from organization_settings").query(String.class).single())
                .isEqualTo("GMT");
        assertThat(jdbc.sql("select password_hash from user_account").query(String.class).single())
                .startsWith("$argon2id$");
        assertThat(auditActions()).containsExactly("organization.created");
    }

    @Test
    void everyFieldIsCheckedAndNothingIsSavedOnError() {
        Response response = browser().post("/api/v1/setup", body(
                "setupCode", "wrong",
                "organizationName", " ",
                "displayName", OWNER_NAME,
                "email", "not-an-address",
                "password", "too-short"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.fieldErrors()).containsExactlyInAnyOrder(
                "setupCode:invalid_setup_code",
                "organizationName:required",
                "email:invalid_email",
                "password:too_short");
        assertThat(jdbc.sql("select count(*) from organization").query(Integer.class).single()).isZero();
    }

    @Test
    void aSecondSetupIsRefused() {
        setUpOrganization();

        Browser latecomer = browser();
        Response again = latecomer.post("/api/v1/setup", body(
                "setupCode", SETUP_CODE,
                "organizationName", "Autre",
                "displayName", "Christ Koffi",
                "email", "christ.koffi@dada.group",
                "password", PASSWORD));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("setup_done");
        assertThat(latecomer.post("/api/v1/setup/verify-code", body("setupCode", SETUP_CODE)).code())
                .isEqualTo("setup_done");

        Response session = latecomer.get("/api/v1/session");
        assertThat(session.flag("setupRequired")).isFalse();
        assertThat(session.text("organization.name")).isEqualTo("DadaGroup");
        assertThat(session.at("user").isNull()).isTrue();
    }
}
