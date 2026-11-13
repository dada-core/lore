package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * F5, accounts and security part: settings of the organization.
 */
class OrganizationSettingsApiTests extends ApiTest {

    @Test
    void defaultsMatchTheDocumentation() {
        Browser owner = setUpOrganization();

        Response settings = owner.get("/api/v1/admin/organization");

        assertThat(settings.text("name")).isEqualTo("DadaGroup");
        assertThat(settings.text("defaultLocale")).isEqualTo("fr");
        assertThat(settings.text("timeZone")).isEqualTo("GMT");
        assertThat(settings.text("publicUrl")).isEqualTo("http://localhost:" + port);
        assertThat(settings.flag("publicUrlFromEnvironment")).isFalse();
        assertThat(settings.number("passwordMinLength")).isEqualTo(12);
        assertThat(settings.number("loginMaxAttempts")).isEqualTo(5);
        assertThat(settings.number("loginLockoutMinutes")).isEqualTo(15);
        assertThat(settings.number("sessionTimeoutMinutes")).isEqualTo(480);
        assertThat(settings.number("sessionTimeoutMaxMinutes")).isEqualTo(480);
        assertThat(settings.number("invitationValidityHours")).isEqualTo(168);
    }

    @Test
    void changesApplyAndAreAudited() {
        Browser owner = setUpOrganization();

        Response updated = owner.patch("/api/v1/admin/organization", body(
                "name", "DadaGroup Réseaux",
                "publicUrl", "https://lore.dada.group/",
                "passwordMinLength", 16,
                "loginLockoutMinutes", 60));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("publicUrl")).isEqualTo("https://lore.dada.group");
        assertThat(owner.get("/api/v1/session").text("organization.name")).isEqualTo("DadaGroup Réseaux");
        Response invitation = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));
        assertThat(invitation.text("url")).startsWith("https://lore.dada.group/join#");
        Response shortPassword = owner.put("/api/v1/account/password",
                body("currentPassword", PASSWORD, "newPassword", "only-15-chars!!", "signOutOtherSessions", false));
        assertThat(shortPassword.fieldErrors()).containsExactly("newPassword:too_short");

        String details = jdbc.sql("select details::text from audit_event where action = 'organization.settings_changed'")
                .query(String.class).single();
        assertThat(details).contains("passwordMinLength", "\"before\": 12", "\"after\": 16", "loginLockoutMinutes");
    }

    @Test
    void valuesOutsideTheLimitsAreRefused() {
        Browser owner = setUpOrganization();

        Response response = owner.patch("/api/v1/admin/organization", body(
                "sessionTimeoutMinutes", 24 * 60,
                "loginMaxAttempts", 1,
                "timeZone", "Mars/Olympus",
                "publicUrl", "ftp://dada.group"));

        assertThat(response.fieldErrors()).containsExactlyInAnyOrder(
                "sessionTimeoutMinutes:out_of_range",
                "loginMaxAttempts:out_of_range",
                "timeZone:invalid_value",
                "publicUrl:invalid_url");
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'organization.settings_changed'")
                .query(Integer.class).single()).isZero();
    }
}
