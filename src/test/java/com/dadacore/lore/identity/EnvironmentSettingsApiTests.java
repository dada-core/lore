package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Settings also set by the environment (point 12 of the user journeys): the variable takes
 * precedence. LORE_PUBLIC_URL makes the address read-only; LORE_SESSION_TIMEOUT is a
 * ceiling that the administration can only lower.
 */
@TestPropertySource(properties = {
        "lore.public-url=http://lore.example.com",
        "lore.session-timeout=1h"})
class EnvironmentSettingsApiTests extends ApiTest {

    @Test
    void theEnvironmentDecidesThePublicAddressAndCapsTheSessionTimeout() {
        Browser owner = setUpOrganization();

        Response settings = owner.get("/api/v1/admin/organization");
        assertThat(settings.text("publicUrl")).isEqualTo("http://lore.example.com");
        assertThat(settings.flag("publicUrlFromEnvironment")).isTrue();
        assertThat(settings.number("sessionTimeoutMaxMinutes")).isEqualTo(60);
        assertThat(settings.number("sessionTimeoutMinutes")).isEqualTo(60);
        assertThat(browser().get("/api/v1/setup").text("publicUrl")).isEqualTo("http://lore.example.com");

        Response refused = owner.patch("/api/v1/admin/organization",
                body("publicUrl", "https://wiki.example.com", "sessionTimeoutMinutes", 120));
        assertThat(refused.fieldErrors()).containsExactlyInAnyOrder(
                "publicUrl:set_by_environment", "sessionTimeoutMinutes:out_of_range");

        Response invitation = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));
        assertThat(invitation.text("url")).startsWith("http://lore.example.com/join#");

        // The 8 hours stored by default are capped at the hour of the environment.
        signIn(OWNER_EMAIL, PASSWORD);
        assertThat(lastSessionTimeoutSeconds()).isEqualTo(3600);

        // Lowering stays possible.
        assertThat(owner.patch("/api/v1/admin/organization", body("sessionTimeoutMinutes", 30)).status()).isEqualTo(200);
        signIn(OWNER_EMAIL, PASSWORD);
        assertThat(lastSessionTimeoutSeconds()).isEqualTo(1800);
    }

    private int lastSessionTimeoutSeconds() {
        return jdbc.sql("select max_inactive_interval from spring_session order by creation_time desc limit 1")
                .query(Integer.class).single();
    }
}
