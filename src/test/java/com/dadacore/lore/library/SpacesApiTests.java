package com.dadacore.lore.library;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * F5: create a space, change its settings, archive it and delete it.
 */
class SpacesApiTests extends ApiTest {

    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";

    @Test
    void anyMemberCreatesASpaceAndBecomesItsMaintainer() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);

        Response created = herve.post("/api/v1/spaces",
                body("name", "Réseau & Sécurité", "description", "  Pare-feu, VPN et certificats  "));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.text("slug")).isEqualTo("reseau-securite");
        assertThat(created.text("description")).isEqualTo("Pare-feu, VPN et certificats");
        assertThat(created.text("color")).isEqualTo("#13705E");
        assertThat(created.text("role")).isEqualTo("MAINTAINER");
        assertThat(created.number("memberCount")).isEqualTo(1);
        assertThat(created.text("archivedAt")).isNull();

        Response members = herve.get("/api/v1/spaces/" + created.text("id") + "/members");
        assertThat(members.text("0.displayName")).isEqualTo("Hervé Coulibaly");
        assertThat(members.text("0.role")).isEqualTo("MAINTAINER");
        assertThat(members.flag("0.self")).isTrue();

        Response sameName = herve.post("/api/v1/spaces", body("name", "Réseau & sécurité"));
        assertThat(sameName.text("slug")).isEqualTo("reseau-securite-2");
        assertThat(sameName.text("color")).isEqualTo("#3F6C9A");

        // The default colour follows the spaces the person sees: it does not tell how many are hidden.
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        assertThat(eli.post("/api/v1/spaces", body("name", "Supervision")).text("color")).isEqualTo("#13705E");

        assertThat(auditActions()).contains("space.created");
    }

    @Test
    void aNewSpaceIsChecked() {
        Browser owner = setUpOrganization();
        owner.post("/api/v1/spaces", body("name", "Infrastructure"));

        assertThat(owner.post("/api/v1/spaces", body("name", " ")).fieldErrors()).containsExactly("name:required");
        assertThat(owner.post("/api/v1/spaces", body("name", "Infra", "slug", "infrastructure")).fieldErrors())
                .containsExactly("slug:slug_taken");
        assertThat(owner.post("/api/v1/spaces", body("name", "Infra", "slug", "mon espace")).fieldErrors())
                .containsExactly("slug:invalid_slug");
        assertThat(owner.post("/api/v1/spaces", body("name", "Infra", "color", "#000000")).fieldErrors())
                .containsExactly("color:invalid_value");
        assertThat(owner.post("/api/v1/spaces", body("name", "Infra", "description", "x".repeat(501))).fieldErrors())
                .containsExactly("description:too_long");

        Response chosen = owner.post("/api/v1/spaces", body("name", "Infra", "slug", " Infra-Lab ", "color", "#5b5fd6"));
        assertThat(chosen.status()).as(chosen.toString()).isEqualTo(201);
        assertThat(chosen.text("slug")).isEqualTo("infra-lab");
        assertThat(chosen.text("color")).isEqualTo("#5B5FD6");
    }

    @Test
    void maintainersChangeTheSettingsOfTheirSpace() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        String space = "/api/v1/spaces/" + eli.post("/api/v1/spaces",
                body("name", "Infrastructure", "description", "Serveurs")).text("id");
        eli.post("/api/v1/spaces", body("name", "Support"));

        Response changed = eli.patch(space, body("name", "Infra", "slug", "infra", "description", "", "color", "#8E4B44"));
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(changed.text("name")).isEqualTo("Infra");
        assertThat(changed.text("description")).isNull();
        assertThat(changed.text("color")).isEqualTo("#8E4B44");
        assertThat(eli.get("/api/v1/spaces/by-slug/infra").text("name")).isEqualTo("Infra");
        assertThat(eli.get("/api/v1/spaces/by-slug/infrastructure").status()).isEqualTo(404);

        assertThat(eli.patch(space, body("slug", "support")).fieldErrors()).containsExactly("slug:slug_taken");
        assertThat(eli.patch(space, body("name", "")).fieldErrors()).containsExactly("name:required");
        assertThat(eli.patch(space, body("name", "Infra")).text("name")).isEqualTo("Infra");

        String details = jdbc.sql("select details::text from audit_event where action = 'space.updated'")
                .query(String.class).single();
        assertThat(details).contains("\"previousName\": \"Infrastructure\"", "\"previousSlug\": \"infrastructure\"");
    }

    @Test
    void anArchivedSpaceIsHiddenAndFrozenUntilItIsActiveAgain() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String space = "/api/v1/spaces/" + eli.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        eli.post(space + "/members", body("userId", userId(HERVE), "role", "CONTRIBUTOR"));

        Response archived = eli.post(space + "/archive");
        assertThat(archived.text("archivedAt")).isNotNull();
        assertThat(herve.get(space).status()).isEqualTo(404);
        assertThat(herve.get("/api/v1/spaces").json().size()).isZero();
        assertThat(eli.get("/api/v1/spaces").json().size()).isZero();
        assertThat(eli.get("/api/v1/spaces?archived=true").text("0.name")).isEqualTo("Infrastructure");

        assertThat(eli.patch(space, body("name", "Infra")).code()).isEqualTo("space_archived");
        assertThat(eli.post(space + "/folders", body("name", "Procédures")).code()).isEqualTo("space_archived");
        assertThat(eli.patch(space + "/members/" + userId(HERVE), body("role", "READER")).code())
                .isEqualTo("space_archived");
        assertThat(eli.post(space + "/archive").status()).isEqualTo(200);

        Response active = eli.post(space + "/unarchive");
        assertThat(active.text("archivedAt")).isNull();
        assertThat(herve.get(space).text("role")).isEqualTo("CONTRIBUTOR");
        assertThat(auditActions()).contains("space.archived", "space.unarchived");
    }

    @Test
    void deletingASpaceDeletesItsFoldersAndItsMembers() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String id = owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        String space = "/api/v1/spaces/" + id;
        owner.post(space + "/folders", body("name", "Procédures"));
        owner.post(space + "/members", body("userId", userId(HERVE), "role", "READER"));

        assertThat(owner.delete(space).status()).isEqualTo(204);
        assertThat(owner.get(space).status()).isEqualTo(404);
        assertThat(count("folder", id)).isZero();
        assertThat(count("space_member", id)).isZero();
        assertThat(auditActions()).contains("space.deleted");
    }

    @Test
    void anUnknownOrMalformedIdentifierAnswersNotFound() {
        Browser owner = setUpOrganization();

        assertThat(owner.get("/api/v1/spaces/" + UUID.randomUUID()).code()).isEqualTo("not_found");
        assertThat(owner.get("/api/v1/spaces/infrastructure").code()).isEqualTo("not_found");
        assertThat(owner.get("/api/v1/spaces/by-slug/infrastructure").code()).isEqualTo("not_found");
        // A malformed query parameter is a bad request.
        assertThat(owner.get("/api/v1/spaces?archived=sometimes").fieldErrors()).containsExactly("archived:invalid_value");
    }

    private int count(String table, String spaceId) {
        return jdbc.sql("select count(*) from " + table + " where space_id = ?::uuid")
                .param(spaceId).query(Integer.class).single();
    }
}
