package com.dadacore.lore.library;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import com.dadacore.lore.support.SampleFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The import of files and the documents they become (the extraction itself is in ExtractionApiTests). */
class DocumentsApiTests extends ApiTest {

    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String ELFRIED = "elfried.yaba@dada.group";
    private static final Path FILES = Path.of("target/test-files");

    private static final byte[] GUIDE = SampleFiles.pdf("Avant toute restauration, vérifiez les rôles de la base.");

    @Autowired
    ExtractionResults results;

    @Test
    void aContributorImportsAFileThatBecomesADocumentAndItsFirstVersion() {
        Team team = team();

        Response imported = team.herve.upload(documents(team.space), "plan-reprise_2026.pdf", GUIDE);

        assertThat(imported.status()).as(imported.toString()).isEqualTo(201);
        assertThat(imported.text("title")).isEqualTo("Plan reprise 2026");
        assertThat(imported.text("status")).isEqualTo("DRAFT");
        assertThat(imported.flag("restricted")).isFalse();
        assertThat(imported.text("owner.displayName")).isEqualTo("Hervé Coulibaly");
        assertThat(imported.text("space.name")).isEqualTo("Infrastructure");
        assertThat(imported.number("currentVersion.number")).isEqualTo(1);
        assertThat(imported.text("currentVersion.filename")).isEqualTo("plan-reprise_2026.pdf");
        assertThat(imported.text("currentVersion.mediaType")).isEqualTo("application/pdf");
        assertThat(imported.text("currentVersion.extractionStatus")).isEqualTo("PENDING");
        assertThat(imported.flag("actions.edit")).isTrue();
        assertThat(imported.flag("actions.delete")).isTrue();
        assertThat(imported.flag("actions.manageAccess")).isTrue();
        assertThat(imported.text("duplicateOf")).isNull();

        String id = imported.text("id");
        assertThat(jdbc.sql("select count(*) from extraction_job").query(Integer.class).single()).isEqualTo(1);
        assertThat(file(team, sha(id))).exists();
        assertThat(auditActions()).contains("document.imported");

        Response list = team.elfried.get(documents(team.space));
        assertThat(list.number("total")).isEqualTo(1);
        assertThat(list.text("items.0.title")).isEqualTo("Plan reprise 2026");
        assertThat(team.elfried.get(documents(team.space) + "/summary").number("inProgress")).isEqualTo(1);
        assertThat(team.elfried.get("/api/v1/spaces/" + team.space).number("documentCount")).isEqualTo(1);
        // A reader sees it, without changing it.
        Response asReader = team.elfried.get("/api/v1/documents/" + id);
        assertThat(asReader.flag("actions.edit")).isFalse();
        assertThat(asReader.flag("actions.delete")).isFalse();
    }

    @Test
    void aFileIsCheckedByItsContentBeforeAnythingIsKept() {
        Team team = team();
        String path = documents(team.space);

        assertThat(team.eli.upload(path, "procedure.doc", GUIDE).fieldErrors()).containsExactly("file:legacy_word");
        assertThat(team.eli.upload(path, "outil.exe", GUIDE).fieldErrors()).containsExactly("file:unsupported_format");
        assertThat(team.eli.upload(path, "faux.pdf", "MZ not a PDF".getBytes(StandardCharsets.UTF_8)).fieldErrors())
                .containsExactly("file:content_mismatch");
        assertThat(team.eli.upload(path, "notes.md", new byte[] {'#', ' ', 0, 1}).fieldErrors())
                .containsExactly("file:content_mismatch");
        assertThat(team.eli.upload(path, "vide.txt", new byte[0]).fieldErrors()).containsExactly("file:empty_file");
        assertThat(team.eli.upload(path, "a.pdf", GUIDE, "status", "OBSOLETE").fieldErrors()).containsExactly("status:invalid_value");
        assertThat(team.eli.upload(path, "a.pdf", GUIDE, "category", "POEM").fieldErrors()).containsExactly("category:invalid_value");

        String other = team.eli.post("/api/v1/spaces", body("name", "Réseau")).text("id");
        String folderOfAnother = team.eli.post("/api/v1/spaces/" + other + "/folders", body("name", "Procédures")).text("id");
        assertThat(team.eli.upload(path, "a.pdf", GUIDE, "folderId", folderOfAnother).fieldErrors())
                .containsExactly("folderId:unknown_folder");
        assertThat(team.eli.upload(path, "a.pdf", GUIDE, "restricted", "true", "userIds", userId(OWNER_EMAIL).toString())
                .fieldErrors()).containsExactly("userIds:not_space_member");

        assertThat(jdbc.sql("select count(*) from document").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from extraction_job").query(Integer.class).single()).isZero();
        assertThat(storedFiles(team)).isEmpty();
    }

    @Test
    void theSizeChosenByTheAdministratorsApplies() {
        Team team = team();

        assertThat(team.owner.get("/api/v1/imports/limits").number("maxMegabytes")).isEqualTo(50);
        Response storage = team.owner.get("/api/v1/admin/storage");
        assertThat(storage.number("maxUploadMegabytes")).isEqualTo(50);
        assertThat(storage.number("maxUploadCeilingMegabytes")).isEqualTo(1024);
        assertThat(team.eli.get("/api/v1/admin/storage").status()).isEqualTo(403);

        // Equal to the size in force, the choice is saved all the same: it stays when the default changes.
        assertThat(team.owner.patch("/api/v1/admin/storage", body("maxUploadMegabytes", 50)).status()).isEqualTo(200);
        assertThat(jdbc.sql("select max_upload_mb from import_settings").query(Integer.class).single()).isEqualTo(50);

        assertThat(team.owner.patch("/api/v1/admin/storage", body("maxUploadMegabytes", 2000)).fieldErrors())
                .containsExactly("maxUploadMegabytes:out_of_range");
        assertThat(team.owner.patch("/api/v1/admin/storage", body("maxUploadMegabytes", 0)).fieldErrors())
                .containsExactly("maxUploadMegabytes:out_of_range");
        assertThat(team.owner.patch("/api/v1/admin/storage", body("maxUploadMegabytes", 1)).number("maxUploadMegabytes"))
                .isEqualTo(1);
        assertThat(auditActions()).contains("import.settings_changed");

        // Just above: refused while it is read.
        byte[] large = "Procédure de sauvegarde.\n".repeat(60_000).getBytes(StandardCharsets.UTF_8);
        Response refused = team.eli.upload(documents(team.space), "sauvegarde.txt", large);
        assertThat(refused.status()).isEqualTo(413);
        assertThat(refused.code()).isEqualTo("file_too_large");
        assertThat(refused.number("maxMegabytes")).isEqualTo(1);
        // Far above: refused from the length announced, before the body is read, and still answered.
        byte[] huge = "Procédure de sauvegarde.\n".repeat(150_000).getBytes(StandardCharsets.UTF_8);
        Response early = team.eli.upload(documents(team.space), "archives.txt", huge);
        assertThat(early.code()).isEqualTo("file_too_large");
        assertThat(early.number("maxMegabytes")).isEqualTo(1);
        assertThat(storedFiles(team)).isEmpty();
    }

    @Test
    void twoImportsOfTheSameContentAtOnceKeepOneFile() throws Exception {
        Team team = team();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = pool.submit(() -> team.herve.upload(documents(team.space), "guide.pdf", GUIDE));
            Future<Response> second = pool.submit(() -> team.eli.upload(documents(team.space), "guide-bis.pdf", GUIDE));
            assertThat(first.get().status()).isEqualTo(201);
            assertThat(second.get().status()).isEqualTo(201);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.sql("select count(*) from document_version").query(Integer.class).single()).isEqualTo(2);
        assertThat(storedFiles(team)).hasSize(1);
    }

    @Test
    void aFormWithTooManyFieldsIsNotTakenForALargeFile() {
        Team team = team();
        String[] fields = new String[2 * 60];
        String[] ids = new String[60];
        for (int i = 0; i < 60; i++) {
            ids[i] = UUID.randomUUID().toString();
            fields[2 * i] = "userIds";
            fields[2 * i + 1] = ids[i];
        }

        // Each person in a field of their own: beyond the 50 fields of a form, refused as such.
        Response refused = team.eli.upload(documents(team.space), "guide.pdf", GUIDE, fields);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("invalid_request");

        // As the interface sends them, in one field: the people themselves are checked.
        Response checked = team.eli.upload(documents(team.space), "guide.pdf", GUIDE,
                "restricted", "true", "userIds", String.join(",", ids));
        assertThat(checked.fieldErrors()).containsExactly("userIds:not_space_member");
    }

    @Test
    void aLongFileNameInAnotherAlphabetIsAccepted() {
        Team team = team();
        // 240 characters of two bytes each: beyond the 512 bytes Tomcat allows by default for the header of a field.
        String name = "Ж".repeat(240) + ".txt";

        Response imported = team.herve.upload(documents(team.space), name,
                "Процедура восстановления базы".getBytes(StandardCharsets.UTF_8));

        assertThat(imported.status()).isEqualTo(201);
        assertThat(imported.text("currentVersion.filename")).isEqualTo(name);
    }

    @Test
    void aUnicodeTextFromNotepadIsAccepted() {
        Team team = team();
        byte[] utf16 = "\uFEFFNotes d'astreinte".getBytes(StandardCharsets.UTF_16LE);

        assertThat(team.herve.upload(documents(team.space), "notes.txt", utf16).status()).isEqualTo(201);
    }

    @Test
    void onlyContributorsImportAndAnArchivedSpaceRefuses() {
        Team team = team();

        assertThat(team.elfried.upload(documents(team.space), "a.pdf", GUIDE).code()).isEqualTo("contributor_only");
        team.eli.post("/api/v1/spaces/" + team.space + "/archive");
        assertThat(team.eli.upload(documents(team.space), "a.pdf", GUIDE).code()).isEqualTo("space_archived");
        assertThat(team.herve.upload(documents(team.space), "a.pdf", GUIDE).status()).isEqualTo(404);
    }

    @Test
    void aRefusalBeforeTheFileIsReadStillReachesTheBrowser() {
        // Refused before the body is read: the server must still read it out, or the browser
        // sees a cut connection instead of the answer (an expired session, an archived space).
        Team team = team();
        byte[] large = "Procédure de sauvegarde.\n".repeat(150_000).getBytes(StandardCharsets.UTF_8);

        assertThat(browser().upload(documents(team.space), "sauvegarde.txt", large).status()).isIn(401, 403);
        assertThat(team.elfried.upload(documents(team.space), "sauvegarde.txt", large).code()).isEqualTo("contributor_only");
        team.eli.post("/api/v1/spaces/" + team.space + "/archive");
        assertThat(team.eli.upload(documents(team.space), "sauvegarde.txt", large).code()).isEqualTo("space_archived");
    }

    @Test
    void theSameContentIsStoredOnceAndPointedOutAsADuplicate() {
        Team team = team();
        String first = team.herve.upload(documents(team.space), "guide.pdf", GUIDE).text("id");

        Response second = team.eli.upload(documents(team.space), "guide-copie.pdf", GUIDE);

        assertThat(second.text("duplicateOf.id")).isEqualTo(first);
        assertThat(second.text("duplicateOf.title")).isEqualTo("Guide");
        assertThat(storedFiles(team)).hasSize(1);

        // The file stays while a version uses it, and goes with the last one.
        assertThat(team.herve.delete("/api/v1/documents/" + first).status()).isEqualTo(204);
        assertThat(storedFiles(team)).hasSize(1);
        assertThat(team.eli.delete("/api/v1/documents/" + second.text("id")).status()).isEqualTo(204);
        awaitNoStoredFiles(team);
        assertThat(auditActions()).contains("document.deleted");
    }

    @Test
    void aDocumentIsRenamedDescribedMovedAndDeletedByThoseAllowed() {
        Team team = team();
        String folder = team.eli.post("/api/v1/spaces/" + team.space + "/folders", body("name", "Procédures")).text("id");
        String id = team.herve.upload(documents(team.space), "guide.pdf", GUIDE).text("id");
        String document = "/api/v1/documents/" + id;

        Response changed = team.herve.patch(document, body("title", " Guide de restauration ",
                "description", "Pour l'astreinte.", "category", "runbook"));
        assertThat(changed.text("title")).isEqualTo("Guide de restauration");
        assertThat(changed.text("description")).isEqualTo("Pour l'astreinte.");
        assertThat(changed.text("category")).isEqualTo("RUNBOOK");
        assertThat(team.herve.patch(document, body("title", "  ")).fieldErrors()).containsExactly("title:required");
        assertThat(team.herve.patch(document, body("description", "", "category", "")).text("category")).isNull();
        assertThat(team.elfried.patch(document, body("title", "Autre")).code()).isEqualTo("contributor_only");

        assertThat(team.herve.post(document + "/move", body("folderId", folder)).text("folder.name")).isEqualTo("Procédures");
        assertThat(team.eli.delete("/api/v1/spaces/" + team.space + "/folders/" + folder).code()).isEqualTo("folder_not_empty");
        assertThat(team.herve.post(document + "/move", body("folderId", UUID.randomUUID().toString())).fieldErrors())
                .containsExactly("folderId:unknown_folder");
        assertThat(team.elfried.get(documents(team.space) + "?folder=" + folder).number("total")).isEqualTo(1);
        assertThat(team.elfried.get(documents(team.space) + "?folder=none").number("total")).isZero();

        // Another contributor neither deletes it nor manages its access; its maintainer does.
        Browser christ = join(team.owner, "MEMBER", "Christ Koffi", "christ.koffi@dada.group");
        team.eli.post("/api/v1/spaces/" + team.space + "/members",
                body("userId", userId("christ.koffi@dada.group"), "role", "CONTRIBUTOR"));
        assertThat(christ.get(document).flag("actions.delete")).isFalse();
        assertThat(christ.delete(document).code()).isEqualTo("maintainer_only");
        assertThat(christ.put(document + "/access", body("restricted", true)).code()).isEqualTo("maintainer_or_owner_only");
        assertThat(team.eli.delete(document).status()).isEqualTo(204);
        assertThat(team.eli.get(document).status()).isEqualTo(404);
        assertThat(team.eli.delete("/api/v1/spaces/" + team.space + "/folders/" + folder).status()).isEqualTo(204);
    }

    @Test
    void aRestrictedDocumentIsSeenByThoseChosenOnly() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "audit.pdf", GUIDE, "restricted", "true",
                "userIds", userId(ELFRIED).toString()).text("id");
        String document = "/api/v1/documents/" + id;

        assertThat(team.elfried.get(document).status()).isEqualTo(200);
        assertThat(team.eli.get(document).status()).isEqualTo(200);

        Response access = team.herve.get(document + "/access");
        assertThat(access.flag("restricted")).isTrue();
        assertThat(access.json().path("people").findValuesAsString("displayName"))
                .containsExactlyInAnyOrder("Elfried Yaba", "Eli Brou", "Hervé Coulibaly");

        // Opened again: everyone in the space; restricted to nobody: the reader loses it.
        team.herve.put(document + "/access", body("restricted", true, "userIds", List.of()));
        assertThat(team.elfried.get(document).status()).isEqualTo(404);
        assertThat(team.elfried.get(documents(team.space)).number("total")).isZero();
        assertThat(team.elfried.get("/api/v1/spaces/" + team.space).number("documentCount")).isZero();
        team.herve.put(document + "/access", body("restricted", false));
        assertThat(team.elfried.get(document).status()).isEqualTo(200);
        assertThat(auditActions()).contains("document.access_changed");
    }

    @Test
    void anExtractionTriedAgainOnlyAfterAPassingFailure() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "guide.pdf", GUIDE).text("id");
        UUID version = UUID.fromString(team.herve.get("/api/v1/documents/" + id).text("currentVersion.id"));

        results.failed(version, "encrypted");
        assertThat(team.herve.post("/api/v1/documents/" + id + "/extraction/retry").code()).isEqualTo("not_retryable");

        results.failed(version, "timeout");
        jdbc.sql("delete from extraction_job").update();
        Response retried = team.herve.post("/api/v1/documents/" + id + "/extraction/retry");
        assertThat(retried.text("currentVersion.extractionStatus")).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from extraction_job").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theOutcomeOfAnExtractionIsRecordedOnItsVersion() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "rotation-tls.md", "# Rotation\n\nTexte.".getBytes(StandardCharsets.UTF_8))
                .text("id");
        UUID version = UUID.fromString(team.herve.get("/api/v1/documents/" + id).text("currentVersion.id"));

        results.extracted(version, new Extraction(false, "fr", null, false, "Rotation des certificats TLS internes",
                List.of(new ExtractedChunk(0, "h-1", null, List.of("Rotation"), "Texte."))));

        Response document = team.herve.get("/api/v1/documents/" + id);
        assertThat(document.text("title")).isEqualTo("Rotation des certificats TLS internes");
        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
        assertThat(document.text("currentVersion.language")).isEqualTo("fr");
        assertThat(document.number("currentVersion.sectionCount")).isEqualTo(1);
        assertThat(jdbc.sql("select anchor from chunk").query(String.class).list()).containsExactly("h-1");
        assertThat(team.herve.get("/api/v1/imports/attention").json().size()).isZero();
    }

    @Test
    void deletingASpaceDeletesItsFiles() {
        Team team = team();
        team.herve.upload(documents(team.space), "guide.pdf", GUIDE);
        assertThat(storedFiles(team)).hasSize(1);

        assertThat(team.eli.delete("/api/v1/spaces/" + team.space).status()).isEqualTo(204);

        awaitNoStoredFiles(team);
        assertThat(jdbc.sql("select count(*) from document").query(Integer.class).single()).isZero();
    }

    /** The owner, Eli maintainer of Infrastructure, Hervé contributor, Elfried reader. */
    private record Team(Browser owner, Browser eli, Browser herve, Browser elfried, String space) {
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

    private static String documents(String space) {
        return "/api/v1/spaces/" + space + "/documents";
    }

    private String sha(String documentId) {
        return jdbc.sql("select v.sha256 from document_version v where v.document_id = ?::uuid")
                .param(documentId).query(String.class).single();
    }

    private UUID organization() {
        return jdbc.sql("select id from organization").query(UUID.class).single();
    }

    private Path file(Team team, String sha256) {
        return FILES.resolve(organization().toString()).resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4))
                .resolve(sha256);
    }

    /** The files of deleted documents go after the commit, apart from the request. */
    private void awaitNoStoredFiles(Team team) {
        java.time.Instant end = java.time.Instant.now().plusSeconds(10);
        while (!storedFiles(team).isEmpty() && java.time.Instant.now().isBefore(end)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(storedFiles(team)).isEmpty();
    }

    private List<Path> storedFiles(Team team) {
        Path folder = FILES.resolve(organization().toString());
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (var files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
