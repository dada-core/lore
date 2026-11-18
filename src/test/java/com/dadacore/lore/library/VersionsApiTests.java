package com.dadacore.lore.library;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;

import tools.jackson.databind.JsonNode;

/** The versions of a document, its statuses, and what an archived document still allows. */
class VersionsApiTests extends ApiTest {

    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String ELFRIED = "elfried.yaba@dada.group";

    @Autowired
    ExtractionResults results;

    @Autowired
    PlatformTransactionManager transactions;

    private int counter;

    /** The owner, Eli maintainer of Infrastructure, Hervé contributor, Elfried reader. */
    private record Team(Browser owner, Browser eli, Browser herve, Browser elfried, String space) {
    }

    @Test
    void aNewVersionBecomesCurrentOnceItsTextIsExtractedAndNotBefore() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Ancienne consigne de restauration."),
                passage("h-2", "Vérification commune des sauvegardes."));
        assertThat(team.herve.put(status(id), body("status", "PUBLISHED")).status()).isEqualTo(200);

        Response created = newVersion(team.herve, id, "v2", "note", "Ajout de la vérification des rôles", "status", "PUBLISHED");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.number("currentVersion.number")).isEqualTo(1);
        assertThat(created.number("newerVersion.number")).isEqualTo(2);
        assertThat(created.text("newerVersion.extractionStatus")).isEqualTo("PENDING");
        assertThat(created.text("newerVersion.author.displayName")).isEqualTo("Hervé Coulibaly");
        assertThat(created.number("versionCount")).isEqualTo(2);
        // Meanwhile, everyone reads version 1, and the search finds it.
        assertThat(found(team.elfried, "ancienne")).containsExactly(id);
        assertThat(team.elfried.get(rendering(id, 1)).status()).isNotEqualTo(404);

        results.extracted(version(id, 2), extraction(passage("h-1", "Nouvelle consigne des rôles."),
                passage("h-2", "Vérification commune des sauvegardes.")));

        Response after = team.elfried.get("/api/v1/documents/" + id);
        assertThat(after.number("currentVersion.number")).isEqualTo(2);
        assertThat(after.at("newerVersion").isNull()).isTrue();
        assertThat(after.text("currentVersion.note")).isEqualTo("Ajout de la vérification des rôles");
        // One passage of two rewritten: one removed and one added, of four.
        assertThat(after.at("currentVersion.changeRatio").asDouble()).isEqualTo(0.5);
        assertThat(after.text("status")).isEqualTo("PUBLISHED");
        assertThat(found(team.elfried, "ancienne")).isEmpty();
        assertThat(found(team.elfried, "nouvelle")).containsExactly(id);
        // The passages of version 1 are gone; its file stays, in the history.
        assertThat(jdbc.sql("select count(*) from chunk where version_id = ?").param(version(id, 1)).query(Integer.class).single())
                .isZero();
        assertThat(team.elfried.raw("GET", "/api/v1/documents/" + id + "/versions/1/file").status()).isEqualTo(200);
        JsonNode versions = team.elfried.get("/api/v1/documents/" + id + "/versions").json();
        assertThat(versions.at("/items/0/number").asInt()).isEqualTo(2);
        assertThat(versions.at("/items/1/number").asInt()).isEqualTo(1);
        assertThat(versions.at("/items/1/changeRatio").asDouble()).isEqualTo(1.0);
        assertThat(versions.at("/items/1/note").isNull()).isTrue();
        assertThat(versions.at("/total").asInt()).isEqualTo(2);
        assertThat(team.elfried.get("/api/v1/documents/" + id + "/versions/1").number("number")).isEqualTo(1);
        assertThat(team.elfried.get("/api/v1/documents/" + id + "/versions/9").status()).isEqualTo(404);
        // Recorded with its number and the one before; the note is not copied.
        assertThat(jdbc.sql("select (details->>'version') || ' ' || (details->>'previous') || ' ' || ((details->'note') is not null)"
                + " from audit_event where action = 'document.version_imported'").query(String.class).single())
                .isEqualTo("2 1 false");
    }

    @Test
    void theSameFileAsTheLatestVersionAddsNothingAnOlderOneIsAWayBack() {
        Team team = team();
        byte[] first = "Le premier fichier.".getBytes(StandardCharsets.UTF_8);
        String id = team.herve.upload("/api/v1/spaces/" + team.space + "/documents", "notes.txt", first).text("id");
        results.extracted(version(id, 1), extraction(passage("h-1", "Texte.")));

        Response same = team.herve.upload("/api/v1/documents/" + id + "/versions", "notes.txt", first);
        assertThat(same.code()).isEqualTo("same_content");
        assertThat(same.at("version").asInt()).isEqualTo(1);
        assertThat(newVersion(team.herve, id, "deuxième").status()).isEqualTo(201);
        // Identical to the version in progress: refused too.
        assertThat(newVersion(team.herve, id, "deuxième").code()).isEqualTo("same_content");
        Response back = team.herve.upload("/api/v1/documents/" + id + "/versions", "notes.txt", first);
        assertThat(back.status()).as(back.toString()).isEqualTo(201);
        assertThat(back.number("newerVersion.number")).isEqualTo(3);
        // A note too long, a status that is not one.
        assertThat(newVersion(team.herve, id, "x", "note", "n".repeat(501)).fieldErrors()).contains("note:too_long");
        assertThat(newVersion(team.herve, id, "y", "status", "OBSOLETE").fieldErrors()).contains("status:invalid_value");
        // A reader imports nothing; a format may change.
        assertThat(newVersion(team.elfried, id, "z").code()).isEqualTo("contributor_only");
        Response markdown = team.herve.upload("/api/v1/documents/" + id + "/versions", "notes.md",
                "# Titre\n\nTexte nouveau.".getBytes(StandardCharsets.UTF_8));
        assertThat(markdown.status()).as(markdown.toString()).isEqualTo(201);
        // A note written on Windows keeps its line breaks, without a space before them.
        Response windows = newVersion(team.herve, id, "w", "note", "Ligne 1\r\nLigne 2");
        assertThat(windows.status()).as(windows.toString()).isEqualTo(201);
        assertThat(jdbc.sql("select note from document_version where document_id = ?::uuid order by number desc limit 1")
                .param(id).query(String.class).single()).isEqualTo("Ligne 1\nLigne 2");
    }

    @Test
    void twoVersionsSentAtOnceTakeTwoNumbersAndAnOlderOneEndingLaterStaysInTheHistory() throws Exception {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Version un."));

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> newVersion(team.herve, id, "a"));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> newVersion(team.eli, id, "b"));
        assertThat(List.of(a.get().status(), b.get().status())).containsOnly(201);
        assertThat(jdbc.sql("select number from document_version where document_id = ?::uuid order by number")
                .param(id).query(Integer.class).list()).containsExactly(1, 2, 3);

        // Version 3 ends first and becomes current; version 2, ending after it, stays in the history.
        results.extracted(version(id, 3), extraction(passage("h-1", "Version trois.")));
        results.extracted(version(id, 2), extraction(passage("h-1", "Version deux.")));
        Response document = team.elfried.get("/api/v1/documents/" + id);
        assertThat(document.number("currentVersion.number")).isEqualTo(3);
        assertThat(document.at("newerVersion").isNull()).isTrue();
        assertThat(found(team.elfried, "trois")).containsExactly(id);
        assertThat(found(team.elfried, "deux")).isEmpty();
        assertThat(jdbc.sql("select count(*) from chunk where version_id = ?").param(version(id, 2)).query(Integer.class).single())
                .isZero();
    }

    @Test
    void aVersionWhoseEndWaitedForAnotherBecomingCurrentBecomesCurrentAfterIt() throws Exception {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Version un."));
        newVersion(team.herve, id, "v2");
        newVersion(team.herve, id, "v3");
        UUID two = version(id, 2);
        UUID three = version(id, 3);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // As the end of the extraction of version 2: the document held, then version 2 made current.
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbc.sql("select id from document where id = ?::uuid for no key update").param(id).query(UUID.class).single();
            locked.countDown();
            await(release);
            jdbc.sql("update document set current_version_id = ? where id = ?::uuid").params(two, id).update();
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        // The end of the extraction of version 3 waits for it, and then finds version 2 current.
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status ->
                results.extracted(three, extraction(passage("h-1", "Version trois.")))));
        Thread.sleep(300);
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(team.herve.get("/api/v1/documents/" + id).number("currentVersion.number")).isEqualTo(3);
        assertThat(found(team.herve, "trois")).containsExactly(id);
    }

    @Test
    void aDocumentDeletedWhileTheTextOfAVersionIsRecordedWaitsForIt() throws Exception {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Version un."));
        newVersion(team.herve, id, "v2");
        UUID two = version(id, 2);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // As the end of a job: its row, then its version, then the document.
        CompletableFuture<Void> extraction = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbc.sql("delete from extraction_job where version_id = ?").param(two).update();
            jdbc.sql("update document_version set extraction_status = 'INDEXED' where id = ?").param(two).update();
            locked.countDown();
            await(release);
            jdbc.sql("select id from document where id = ?::uuid for no key update").param(id).query(UUID.class).optional();
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Response> deletion = CompletableFuture.supplyAsync(() -> team.eli.delete("/api/v1/documents/" + id));
        Thread.sleep(500);
        release.countDown();

        extraction.get(10, TimeUnit.SECONDS);
        assertThat(deletion.get(10, TimeUnit.SECONDS).status()).isEqualTo(204);
        assertThat(team.eli.get("/api/v1/documents/" + id).status()).isEqualTo(404);
    }

    @Test
    void theVersionsToAttendToAreThoseImportedSinceTheCurrentOne() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Version un."));
        newVersion(team.herve, id, "v2");
        results.failed(version(id, 2), "timeout");
        newVersion(team.herve, id, "v3");

        assertThat(attention(team.herve)).containsExactlyInAnyOrder(id + ":2:FAILED", id + ":3:PENDING");
        results.extracted(version(id, 3), extraction(passage("h-1", "Version trois.")));
        assertThat(attention(team.herve)).isEmpty();
    }

    @Test
    void aFailedVersionLeavesTheCurrentOneAndIsTriedAgain() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Version un."));
        newVersion(team.herve, id, "v2");

        results.failed(version(id, 2), "timeout");

        Response document = team.herve.get("/api/v1/documents/" + id);
        assertThat(document.number("currentVersion.number")).isEqualTo(1);
        assertThat(document.text("newerVersion.extractionStatus")).isEqualTo("FAILED");
        assertThat(document.text("newerVersion.extractionError")).isEqualTo("timeout");
        assertThat(document.flag("actions.retry")).isTrue();
        assertThat(attention(team.herve)).containsExactly(id + ":2:FAILED");
        Response retried = team.herve.post("/api/v1/documents/" + id + "/extraction/retry");
        assertThat(retried.text("newerVersion.extractionStatus")).isEqualTo("PENDING");
        results.extracted(version(id, 2), extraction(passage("h-1", "Version deux.")));
        assertThat(team.herve.get("/api/v1/documents/" + id).number("currentVersion.number")).isEqualTo(2);
        assertThat(attention(team.herve)).isEmpty();
    }

    @Test
    void theStatusAskedWithAVersionAndItsLanguageFollowItWhenItBecomesCurrent() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Le guide."));
        team.herve.put(status(id), body("status", "PUBLISHED"));
        team.herve.patch("/api/v1/documents/" + id, body("language", "en"));

        newVersion(team.herve, id, "v2", "status", "DRAFT");
        // Published until then: the reader is not told "draft" while the text is extracted.
        assertThat(team.elfried.get("/api/v1/documents/" + id).text("status")).isEqualTo("PUBLISHED");
        results.extracted(version(id, 2), extraction(passage("h-1", "The guide.")));
        Response document = team.elfried.get("/api/v1/documents/" + id);
        assertThat(document.text("status")).isEqualTo("DRAFT");
        // The language chosen by a person is taken again, though detected otherwise.
        assertThat(document.text("currentVersion.language")).isEqualTo("en");
        // The change of status that follows is recorded in the name of who imported the version.
        assertThat(jdbc.sql("select count(*) from audit_event e join user_account u on u.id = e.actor_id"
                + " where e.action = 'document.status_changed' and u.email = ? and e.details->>'version' = '2'")
                .param(HERVE).query(Integer.class).single()).isEqualTo(1);

        // A status changed meanwhile wins: the version asked for Published, the document became obsolete.
        newVersion(team.herve, id, "v3", "status", "PUBLISHED");
        team.herve.put(status(id), body("status", "OBSOLETE"));
        results.extracted(version(id, 3), extraction(passage("h-1", "The guide, third.")));
        assertThat(team.elfried.get("/api/v1/documents/" + id).text("status")).isEqualTo("OBSOLETE");
    }

    @Test
    void contributorsChangeTheStatusAndMaintainersArchive() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Procédure."));
        String other = imported(team.herve, team.space, passage("h-1", "La bonne procédure."));

        assertThat(team.elfried.put(status(id), body("status", "PUBLISHED")).code()).isEqualTo("contributor_only");
        assertThat(team.herve.put(status(id), body("status", "PUBLISHED")).text("status")).isEqualTo("PUBLISHED");
        assertThat(team.herve.put(status(id), body("status", "ARCHIVED")).code()).isEqualTo("maintainer_only");
        assertThat(team.herve.put(status(id), body("status", "SUPERSEDED")).fieldErrors()).contains("status:invalid_value");

        Response obsolete = team.herve.put(status(id), body("status", "OBSOLETE", "replacedBy", other));
        assertThat(obsolete.text("status")).isEqualTo("OBSOLETE");
        assertThat(obsolete.text("replacedBy.id")).isEqualTo(other);
        assertThat(obsolete.text("replacedBy.title")).isEqualTo(team.herve.get("/api/v1/documents/" + other).text("title"));
        // Not itself, not an obsolete one, not one the person cannot read: the same answer as none.
        assertThat(team.herve.put(status(other), body("status", "OBSOLETE", "replacedBy", other)).fieldErrors())
                .contains("replacedBy:not_replaceable");
        assertThat(team.herve.put(status(other), body("status", "OBSOLETE", "replacedBy", id)).fieldErrors())
                .contains("replacedBy:not_replaceable");
        assertThat(team.herve.put(status(other), body("status", "OBSOLETE", "replacedBy", UUID.randomUUID().toString()))
                .fieldErrors()).contains("replacedBy:unknown_document");
        // Leaving Obsolete removes the replacement; deleting the replacement removes it too.
        assertThat(team.herve.put(status(id), body("status", "PUBLISHED")).at("replacedBy").isNull()).isTrue();
        team.herve.put(status(id), body("status", "OBSOLETE", "replacedBy", other));
        team.eli.delete("/api/v1/documents/" + other);
        assertThat(team.herve.get("/api/v1/documents/" + id).at("replacedBy").isNull()).isTrue();
        assertThat(team.herve.get("/api/v1/documents/" + id).text("status")).isEqualTo("OBSOLETE");

        // A replacement Hervé cannot read stays when he saves the status again: he could not mean it.
        String hidden = imported(team.eli, team.space, passage("h-1", "Restreinte."));
        team.eli.put("/api/v1/documents/" + hidden + "/access", body("restricted", true, "userIds", List.of()));
        team.eli.put(status(id), body("status", "OBSOLETE", "replacedBy", hidden));
        int events = jdbc.sql("select count(*) from audit_event where action = 'document.status_changed'").query(Integer.class).single();
        assertThat(team.herve.put(status(id), body("status", "OBSOLETE")).at("replacedBy").isNull()).isTrue();
        assertThat(team.eli.get("/api/v1/documents/" + id).text("replacedBy.id")).isEqualTo(hidden);
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'document.status_changed'").query(Integer.class)
                .single()).isEqualTo(events);
        // Eli, who reads it, removes it.
        assertThat(team.eli.put(status(id), body("status", "OBSOLETE")).at("replacedBy").isNull()).isTrue();

        // Archived: the maintainers only, then out of it.
        Response archived = team.eli.put(status(id), body("status", "ARCHIVED"));
        assertThat(archived.text("status")).isEqualTo("ARCHIVED");
        assertThat(archived.flag("actions.edit")).isFalse();
        assertThat(archived.flag("actions.newVersion")).isFalse();
        assertThat(archived.flag("actions.archive")).isTrue();
        assertThat(team.herve.get("/api/v1/documents/" + id).status()).isEqualTo(404);
        assertThat(team.herve.put(status(id), body("status", "PUBLISHED")).status()).isEqualTo(404);
        assertThat(team.eli.put(status(id), body("status", "PUBLISHED")).text("status")).isEqualTo("PUBLISHED");
        assertThat(team.herve.get("/api/v1/documents/" + id).status()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'document.status_changed'").query(Integer.class)
                .single()).isEqualTo(8);
    }

    @Test
    void anArchivedDocumentIsReadOnlyButForItsStatusAndItsDeletion() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Procédure VPN 2024."));
        String folder = team.eli.post("/api/v1/spaces/" + team.space + "/folders", body("name", "Réseau")).text("id");
        team.eli.put(status(id), body("status", "ARCHIVED"));
        String before = team.eli.get("/api/v1/documents/" + id).text("statusChangedAt");

        assertThat(team.eli.patch("/api/v1/documents/" + id, body("title", "Autre")).code()).isEqualTo("document_archived");
        assertThat(team.eli.post("/api/v1/documents/" + id + "/move", body("folderId", folder)).code())
                .isEqualTo("document_archived");
        assertThat(team.eli.put("/api/v1/documents/" + id + "/access", body("restricted", true)).code())
                .isEqualTo("document_archived");
        assertThat(team.eli.put("/api/v1/documents/" + id + "/owner", body("userId", userId(ELI))).code())
                .isEqualTo("document_archived");
        assertThat(newVersion(team.eli, id, "v2").code()).isEqualTo("document_archived");
        // Out of the lists and the counts, but for who asks for it.
        assertThat(ids(team.eli.get("/api/v1/spaces/" + team.space + "/documents"))).doesNotContain(id);
        assertThat(ids(team.eli.get("/api/v1/spaces/" + team.space + "/documents?status=ARCHIVED"))).containsExactly(id);
        assertThat(ids(team.herve.get("/api/v1/spaces/" + team.space + "/documents?status=ARCHIVED"))).isEmpty();
        Response summary = team.eli.get("/api/v1/spaces/" + team.space + "/documents/summary");
        assertThat(summary.number("total")).isZero();
        assertThat(summary.number("archived")).isEqualTo(1);
        assertThat(team.herve.get("/api/v1/spaces/" + team.space + "/documents/summary").number("archived")).isZero();
        assertThat(team.eli.get("/api/v1/spaces/" + team.space).number("documentCount")).isZero();
        assertThat(team.eli.get("/api/v1/documents/" + id).text("statusChangedAt")).isEqualTo(before);
        assertThat(team.eli.delete("/api/v1/documents/" + id).status()).isEqualTo(204);
        // The deletion of the space records the archived documents it takes too.
        String other = imported(team.herve, team.space, passage("h-1", "Procédure VPN 2025."));
        team.eli.put(status(other), body("status", "ARCHIVED"));
        imported(team.herve, team.space, passage("h-1", "Procédure VPN 2026."));
        assertThat(team.eli.delete("/api/v1/spaces/" + team.space).status()).isEqualTo(204);
        assertThat(jdbc.sql("select (details->>'documents')::int from audit_event where action = 'space.deleted' and resource_id = ?")
                .param(team.space).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aLinkToASectionOfAnOlderVersionFindsTheSectionOfTheSameTitle() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Texte."));
        results.extracted(version(id, 1), new Extraction(false, "fr", null, false, null,
                List.of(new ExtractedChunk(0, "h-2", null, List.of(), "Erreurs.")), rendering("Préparer", "Erreurs fréquentes")));
        newVersion(team.herve, id, "v2");
        results.extracted(version(id, 2), new Extraction(false, "fr", null, false, null,
                List.of(new ExtractedChunk(0, "h-3", null, List.of(), "Erreurs.")),
                rendering("Préparer", "Restaurer", "Erreurs  FRÉQUENTES")));

        Response found = team.elfried.get("/api/v1/documents/" + id + "/versions/2/rendering?anchor=h-2&fromVersion=1");
        assertThat(found.status()).as(found.toString()).isEqualTo(200);
        assertThat(found.text("anchor")).isEqualTo("h-3");
        Response gone = team.elfried.get("/api/v1/documents/" + id + "/versions/2/rendering?anchor=h-9&fromVersion=1");
        assertThat(gone.at("anchor").isNull()).isTrue();
        assertThat(team.elfried.get("/api/v1/documents/" + id + "/versions/2/rendering?anchor=h-2").text("anchor"))
                .isEqualTo("h-2");
        // Opening version 1 is a view, with its number.
        team.elfried.get("/api/v1/documents/" + id + "?version=1");
        assertThat(jdbc.sql("select details->>'version' from audit_event where action = 'document.viewed'")
                .query(String.class).list()).containsExactly("1");
    }

    @Test
    void theActivityOfADocumentTellsWhatChangedButNeitherViewsNorWhoWasChosen() {
        Team team = team();
        String id = imported(team.herve, team.space, passage("h-1", "Procédure."));
        String replacement = imported(team.eli, team.space, passage("h-1", "Remplaçante."));
        team.eli.put("/api/v1/documents/" + replacement + "/access", body("restricted", true, "userIds", List.of()));
        team.herve.get("/api/v1/documents/" + id);
        team.herve.raw("GET", "/api/v1/documents/" + id + "/versions/1/file");
        team.herve.put("/api/v1/documents/" + id + "/access", body("restricted", true, "userIds", List.of(userId(ELFRIED))));
        team.herve.put("/api/v1/documents/" + id + "/access", body("restricted", false));
        team.eli.put(status(id), body("status", "OBSOLETE", "replacedBy", replacement));

        JsonNode herve = team.herve.get("/api/v1/documents/" + id + "/activity").json();
        List<String> actions = new ArrayList<>();
        herve.path("items").forEach(item -> actions.add(item.path("action").asString()));
        assertThat(actions).containsExactly("document.status_changed", "document.access_changed", "document.access_changed",
                "document.imported");
        // Hervé does not read the replacement, restricted: no trace of it.
        assertThat(herve.toString()).doesNotContain(replacement).doesNotContain(userId(ELFRIED).toString());
        assertThat(herve.at("/items/2/details/added").asInt()).isEqualTo(1);
        assertThat(herve.at("/items/0/actor/displayName").asString()).isEqualTo("Eli Brou");
        JsonNode eli = team.eli.get("/api/v1/documents/" + id + "/activity?size=1").json();
        assertThat(eli.at("/items/0/details/replacedBy/id").asString()).isEqualTo(replacement);
        String next = eli.path("next").asString();
        JsonNode second = team.eli.get("/api/v1/documents/" + id + "/activity?size=1&before=" + next).json();
        assertThat(second.at("/items/0/action").asString()).isEqualTo("document.access_changed");
        assertThat(team.eli.get("/api/v1/documents/" + id + "/activity?before=nope").fieldErrors()).contains("before:invalid_value");
        // A move to a folder deleted since says so, and a move to the root names no folder.
        String folder = team.eli.post("/api/v1/spaces/" + team.space + "/folders", body("name", "Réseau")).text("id");
        team.eli.post("/api/v1/documents/" + id + "/move", body("folderId", folder));
        team.eli.post("/api/v1/documents/" + id + "/move", body("folderId", null));
        assertThat(team.eli.delete("/api/v1/spaces/" + team.space + "/folders/" + folder).status()).isEqualTo(204);
        JsonNode moves = team.eli.get("/api/v1/documents/" + id + "/activity?size=2").json();
        assertThat(moves.at("/items/0/details/to").isMissingNode()).isTrue();
        assertThat(moves.at("/items/1/details/to/deleted").asBoolean()).isTrue();
        assertThat(moves.at("/items/1/details/to/name").isMissingNode()).isTrue();

        // The home page: the documents the person sees only.
        List<String> home = new ArrayList<>();
        team.herve.get("/api/v1/activity").json().forEach(item -> home.add(item.at("/document/id").asString()));
        assertThat(home).contains(id).doesNotContain(replacement);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Team team() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        Browser herve = join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        Browser elfried = join(owner, "MEMBER", "Elfried Yaba", ELFRIED);
        String space = eli.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        eli.post("/api/v1/spaces/" + space + "/members", body("userId", userId(HERVE), "role", "CONTRIBUTOR"));
        eli.post("/api/v1/spaces/" + space + "/members", body("userId", userId(ELFRIED), "role", "READER"));
        return new Team(owner, eli, herve, elfried, space);
    }

    /** A text document, imported and extracted. */
    private String imported(Browser who, String space, ExtractedChunk... passages) {
        Response created = who.upload("/api/v1/spaces/" + space + "/documents", "notes-" + (++counter) + ".txt",
                ("Contenu " + counter).getBytes(StandardCharsets.UTF_8));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String id = created.text("id");
        results.extracted(version(id, 1), extraction(passages));
        return id;
    }

    private Response newVersion(Browser who, String id, String content, String... fields) {
        return who.upload("/api/v1/documents/" + id + "/versions", "notes.txt", ("Nouveau " + content).getBytes(StandardCharsets.UTF_8),
                fields);
    }

    private static Extraction extraction(ExtractedChunk... passages) {
        return new Extraction(false, "fr", null, false, null, List.of(passages), null);
    }

    private static Rendering rendering(String... titles) {
        StringBuilder html = new StringBuilder("<section id=\"h-0\"><p>Avant.</p></section>");
        List<Rendering.Place> places = new ArrayList<>();
        places.add(new Rendering.Place("h-0", 1, 0, null));
        for (int i = 0; i < titles.length; i++) {
            html.append("<section id=\"h-").append(i + 1).append("\"><h2>").append(titles[i]).append("</h2></section>");
            places.add(new Rendering.Place("h-" + (i + 1), 1, 2, titles[i]));
        }
        return new Rendering(List.of(html.toString()), places, false);
    }

    private ExtractedChunk passage(String anchor, String content) {
        return new ExtractedChunk(++counter, anchor, null, List.of(), content);
    }

    private UUID version(String id, int number) {
        return jdbc.sql("select id from document_version where document_id = ?::uuid and number = ?").params(id, number)
                .query(UUID.class).single();
    }

    private List<String> found(Browser who, String q) {
        List<String> ids = new ArrayList<>();
        who.get("/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)).json().path("items")
                .forEach(item -> ids.add(item.at("/document/id").asString()));
        return ids;
    }

    private static List<String> ids(Response list) {
        List<String> ids = new ArrayList<>();
        list.json().path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    private static List<String> attention(Browser who) {
        List<String> items = new ArrayList<>();
        who.get("/api/v1/imports/attention").json()
                .forEach(item -> items.add(item.path("id").asString() + ":" + item.path("version").asInt() + ":"
                        + item.path("extractionStatus").asString()));
        return items;
    }

    private static String status(String id) {
        return "/api/v1/documents/" + id + "/status";
    }

    private static String rendering(String id, int number) {
        return "/api/v1/documents/" + id + "/versions/" + number + "/rendering";
    }
}
