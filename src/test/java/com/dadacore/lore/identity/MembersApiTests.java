package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * F4: administer the members of the organization.
 */
class MembersApiTests extends ApiTest {

    private static final String AMENAN = "amenan.souaga@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String ELI = "eli.brou@dada.group";
    private static final String CHRIST = "christ.koffi@dada.group";

    @Test
    void membersAreListedByNameAndSearchedWithoutAccents() {
        Browser owner = setUpOrganization();
        join(owner, "ADMIN", "Amenan Souaga", AMENAN);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);

        Response all = owner.get("/api/v1/admin/members");
        assertThat(all.number("total")).isEqualTo(3);
        assertThat(all.text("items.0.displayName")).isEqualTo("Amenan Souaga");
        assertThat(all.text("items.0.role")).isEqualTo("ADMIN");
        assertThat(all.text("items.0.status")).isEqualTo("ACTIVE");
        assertThat(all.text("items.1.displayName")).isEqualTo("Hervé Coulibaly");
        assertThat(all.flag("items.2.self")).isTrue();
        assertThat(all.flag("items.2.manageable")).isFalse();

        Response search = owner.get("/api/v1/admin/members?q=herve");
        assertThat(search.number("total")).isEqualTo(1);
        assertThat(search.text("items.0.email")).isEqualTo(HERVE);

        Response paged = owner.get("/api/v1/admin/members?size=1&page=1");
        assertThat(paged.json().get("items").size()).isEqualTo(1);
        assertThat(paged.text("items.0.displayName")).isEqualTo("Hervé Coulibaly");
    }

    @Test
    void onlyOwnersGrantTheOwnerRoleAndOneOwnerAlwaysRemains() {
        Browser owner = setUpOrganization();
        Browser amenan = join(owner, "ADMIN", "Amenan Souaga", AMENAN);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        UUID ownerId = userId(OWNER_EMAIL);

        Response byAdmin = amenan.patch("/api/v1/admin/members/" + userId(HERVE), body("role", "OWNER"));
        assertThat(byAdmin.status()).isEqualTo(403);
        assertThat(byAdmin.code()).isEqualTo("owner_only");

        Response lastOwner = owner.patch("/api/v1/admin/members/" + ownerId, body("role", "ADMIN"));
        assertThat(lastOwner.status()).isEqualTo(409);
        assertThat(lastOwner.code()).isEqualTo("last_owner");

        Response promoted = owner.patch("/api/v1/admin/members/" + userId(AMENAN), body("role", "OWNER"));
        assertThat(promoted.text("role")).isEqualTo("OWNER");
        assertThat(owner.patch("/api/v1/admin/members/" + ownerId, body("role", "ADMIN")).text("role")).isEqualTo("ADMIN");

        String details = jdbc.sql("select details::text from audit_event where action = 'member.role_changed' order by id limit 1")
                .query(String.class).single();
        assertThat(details).contains("\"before\": \"ADMIN\"", "\"after\": \"OWNER\"", "Amenan Souaga");
    }

    @Test
    void aDisabledAccountLosesItsSessionsAndCanBeEnabledAgain() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        UUID herveId = userId(HERVE);

        Response disabled = owner.post("/api/v1/admin/members/" + herveId + "/disable");
        assertThat(disabled.text("status")).isEqualTo("DISABLED");
        assertThat(herve.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(browser().post("/api/v1/auth/login", body("email", HERVE, "password", PASSWORD)).code())
                .isEqualTo("account_disabled");

        assertThat(owner.post("/api/v1/admin/members/" + herveId + "/enable").text("status")).isEqualTo("ACTIVE");
        assertThat(browser().post("/api/v1/auth/login", body("email", HERVE, "password", PASSWORD)).status()).isEqualTo(200);
        assertThat(auditActions()).contains("member.disabled", "member.enabled");
    }

    @Test
    void aRemovedMemberLosesAccessButTheAccountRemains() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        UUID herveId = userId(HERVE);

        assertThat(owner.delete("/api/v1/admin/members/" + herveId).status()).isEqualTo(204);

        assertThat(herve.get("/api/v1/account").status()).isEqualTo(401);
        assertThat(owner.get("/api/v1/admin/members").number("total")).isEqualTo(1);
        assertThat(jdbc.sql("select display_name from user_account where id = ?").param(herveId).query(String.class).single())
                .isEqualTo("Hervé Coulibaly");
        assertThat(browser().post("/api/v1/auth/login", body("email", HERVE, "password", PASSWORD)).code())
                .isEqualTo("account_disabled");
    }

    @Test
    void administratorsCannotActOnOwnersNorOnThemselves() {
        Browser owner = setUpOrganization();
        Browser amenan = join(owner, "ADMIN", "Amenan Souaga", AMENAN);

        Response onOwner = amenan.post("/api/v1/admin/members/" + userId(OWNER_EMAIL) + "/disable");
        assertThat(onOwner.status()).isEqualTo(403);
        assertThat(onOwner.code()).isEqualTo("owner_only");

        Response onSelf = amenan.post("/api/v1/admin/members/" + userId(AMENAN) + "/disable");
        assertThat(onSelf.status()).isEqualTo(409);
        assertThat(onSelf.code()).isEqualTo("own_account");
    }

    @Test
    void membersCannotOpenTheAdministration() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);

        for (Response response : new Response[] {
                herve.get("/api/v1/admin/members"),
                herve.get("/api/v1/admin/organization"),
                herve.get("/api/v1/admin/invitations"),
                herve.post("/api/v1/admin/invitations", body("role", "MEMBER"))}) {
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.code()).isEqualTo("admin_only");
        }
    }

    @Test
    void aRoleChangeAppliesToTheNextRequest() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        assertThat(herve.get("/api/v1/admin/members").status()).isEqualTo(403);

        owner.patch("/api/v1/admin/members/" + userId(HERVE), body("role", "ADMIN"));

        assertThat(herve.get("/api/v1/admin/members").status()).isEqualTo(200);
    }

    @Test
    void anAdministratorMadeMemberLosesTheInvitationsTheyCreated() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "ADMIN", "Eli Brou", ELI);
        Browser amenan = join(owner, "ADMIN", "Amenan Souaga", AMENAN);
        String eliInvitation = token(eli.post("/api/v1/admin/invitations", body("role", "ADMIN")).text("url"));
        String amenanInvitation = token(amenan.post("/api/v1/admin/invitations", body("role", "ADMIN")).text("url"));

        assertThat(owner.patch("/api/v1/admin/members/" + userId(ELI), body("role", "MEMBER")).text("role"))
                .isEqualTo("MEMBER");

        Response accepted = browser().post("/api/v1/invitations/accept", body(
                "token", eliInvitation, "displayName", "Elfried Yaba", "email", "elfried.yaba@dada.group", "password", PASSWORD));
        assertThat(accepted.status()).isEqualTo(404);
        assertThat(accepted.code()).isEqualTo("invalid_link");
        assertThat(browser().post("/api/v1/invitations/inspect", body("token", amenanInvitation)).status())
                .as("the links of another administrator stay valid").isEqualTo(200);
        assertThat(jdbc.sql("select details::text from audit_event where action = 'member.role_changed'")
                .query(String.class).single()).contains("\"revokedLinks\": 1");
    }

    @Test
    void anAdministratorMadeMemberLosesTheResetLinksTheyCreated() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "ADMIN", "Eli Brou", ELI);
        Browser amenan = join(owner, "ADMIN", "Amenan Souaga", AMENAN);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        join(owner, "MEMBER", "Christ Koffi", CHRIST);
        String eliLink = token(eli.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link").text("url"));
        String amenanLink = token(amenan.post("/api/v1/admin/members/" + userId(CHRIST) + "/password-reset-link").text("url"));

        owner.patch("/api/v1/admin/members/" + userId(ELI), body("role", "MEMBER"));

        Response reset = browser().post("/api/v1/password-reset/complete", body("token", eliLink, "password", "a-brand-new-passphrase"));
        assertThat(reset.status()).isEqualTo(404);
        assertThat(reset.code()).isEqualTo("invalid_link");
        assertThat(browser().post("/api/v1/password-reset/inspect", body("token", amenanLink)).status())
                .as("the links of another administrator stay valid").isEqualTo(200);
    }

    @Test
    void anAdministratorDisabledOrRemovedLosesTheLinksTheyCreated() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "ADMIN", "Eli Brou", ELI);
        Browser amenan = join(owner, "ADMIN", "Amenan Souaga", AMENAN);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String eliInvitation = token(eli.post("/api/v1/admin/invitations", body("role", "MEMBER")).text("url"));
        String eliLink = token(eli.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link").text("url"));
        String amenanInvitation = token(amenan.post("/api/v1/admin/invitations", body("role", "MEMBER")).text("url"));

        owner.post("/api/v1/admin/members/" + userId(ELI) + "/disable");

        assertThat(browser().post("/api/v1/invitations/inspect", body("token", eliInvitation)).code()).isEqualTo("invalid_link");
        assertThat(browser().post("/api/v1/password-reset/inspect", body("token", eliLink)).code()).isEqualTo("invalid_link");
        assertThat(jdbc.sql("select details::text from audit_event where action = 'member.disabled'")
                .query(String.class).single()).contains("\"revokedLinks\": 2");

        owner.delete("/api/v1/admin/members/" + userId(AMENAN));

        assertThat(browser().post("/api/v1/invitations/inspect", body("token", amenanInvitation)).code()).isEqualTo("invalid_link");
        assertThat(jdbc.sql("select details::text from audit_event where action = 'member.removed'")
                .query(String.class).single()).contains("\"revokedLinks\": 1");
    }
}
