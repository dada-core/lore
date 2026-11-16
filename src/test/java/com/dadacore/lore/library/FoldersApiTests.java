package com.dadacore.lore.library;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * F5: the folders of a space.
 */
class FoldersApiTests extends ApiTest {

    private static final String HERVE = "herve.coulibaly@dada.group";

    @Test
    void contributorsOrganizeTheFoldersOfASpace() {
        Browser owner = setUpOrganization();
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String space = "/api/v1/spaces/" + owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        owner.post(space + "/members", body("userId", userId(HERVE), "role", "CONTRIBUTOR"));
        String folders = space + "/folders";

        Response procedures = herve.post(folders, body("name", "  Procédures "));
        assertThat(procedures.status()).as(procedures.toString()).isEqualTo(201);
        assertThat(procedures.text("name")).isEqualTo("Procédures");
        String schemas = herve.post(folders, body("name", "Schémas")).text("id");
        herve.post(folders, body("name", "Archives"));

        Response list = herve.get(folders);
        assertThat(list.text("0.name")).isEqualTo("Procédures");
        assertThat(list.text("1.name")).isEqualTo("Schémas");
        assertThat(list.text("2.name")).isEqualTo("Archives");

        assertThat(herve.post(folders, body("name", "PROCÉDURES")).fieldErrors()).containsExactly("name:name_taken");
        assertThat(herve.post(folders, body("name", "")).fieldErrors()).containsExactly("name:required");
        assertThat(herve.post(folders, body("name", "x".repeat(81))).fieldErrors()).containsExactly("name:too_long");
        assertThat(herve.patch(folders + "/" + schemas, body("name", "procédures")).fieldErrors())
                .containsExactly("name:name_taken");

        assertThat(herve.patch(folders + "/" + schemas, body("name", "Architecture")).text("name"))
                .isEqualTo("Architecture");
        assertThat(herve.patch(folders + "/" + schemas, body("name", "architecture")).text("name"))
                .isEqualTo("architecture");
        assertThat(herve.delete(folders + "/" + schemas).status()).isEqualTo(204);
        assertThat(herve.get(folders).json().size()).isEqualTo(2);
        assertThat(auditActions()).contains("folder.created", "folder.renamed", "folder.deleted");
    }

    @Test
    void aFolderIsOnlyReachedThroughItsOwnSpace() {
        Browser owner = setUpOrganization();
        String infrastructure = "/api/v1/spaces/" + owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        String support = "/api/v1/spaces/" + owner.post("/api/v1/spaces", body("name", "Support")).text("id");
        String folder = owner.post(infrastructure + "/folders", body("name", "Procédures")).text("id");

        assertThat(owner.patch(support + "/folders/" + folder, body("name", "Pris")).status()).isEqualTo(404);
        assertThat(owner.delete(support + "/folders/" + folder).status()).isEqualTo(404);
        assertThat(owner.get(infrastructure + "/folders").json().size()).isEqualTo(1);
    }
}
