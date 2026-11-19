package com.dadacore.lore.ingestion.jobs;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import com.dadacore.lore.support.SampleFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The extraction for real: imported files read by extraction processes started from the class path
 * of the tests, the outcome recorded on their version. Crashes and hangs are simulated by the
 * processes themselves (lore.extraction.test-hooks).
 */
@TestPropertySource(properties = {
        "lore.extraction.enabled=true",
        "lore.extraction.test-hooks=true",
        "lore.extraction.retry-delays=1s,1s",
        "lore.extraction-timeout=10s",
        "lore.extraction-workers=1"})
class ExtractionApiTests extends ApiTest {

    private static final String ENGLISH = "Before any restore, check that the roles of the database exist on the target server.";

    @Autowired
    ExtractionWorkers workers;

    @Autowired
    ExtractionSignal signal;

    @Test
    void aPdfIsReadByAnExtractionProcess() {
        Imported imported = importFile("guide.pdf", SampleFiles.pdf(ENGLISH, ENGLISH + " Then stop the service."));

        Response document = waitForExtraction(imported);

        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
        assertThat(document.number("currentVersion.pageCount")).isEqualTo(2);
        assertThat(document.number("currentVersion.sectionCount")).isEqualTo(2);
        assertThat(document.text("currentVersion.language")).isEqualTo("en");
        assertThat(jdbc.sql("select anchor from chunk order by ordinal").query(String.class).list())
                .containsExactly("page-1", "page-2");
        assertThat(jdbc.sql("select count(*) from extraction_job").query(Integer.class).single()).isZero();
    }

    @Test
    void aMarkdownFileIsLaidOutForReading() {
        Imported imported = importFile("runbook.md", """
                # Restaurer une base

                Lancez `pg_restore` depuis la sauvegarde.

                ## Erreurs fréquentes

                <script>alert(1)</script>

                Rôle absent : voir [la section](#erreurs-frequentes).
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(waitForExtraction(imported).text("currentVersion.extractionStatus")).isEqualTo("INDEXED");

        Response rendering = imported.browser.get("/api/v1/documents/" + imported.id + "/versions/1/rendering");
        assertThat(rendering.status()).as(rendering.toString()).isEqualTo(200);
        assertThat(rendering.text("html")).contains("<section id=\"h-1\"><h2>Restaurer une base</h2>")
                .contains("<code>pg_restore</code>").contains("<a href=\"#h-2\"").contains(">la section</a>").doesNotContain("<script");
        assertThat(rendering.text("outline.1.title")).isEqualTo("Erreurs fréquentes");
        assertThat(rendering.number("renderer")).isEqualTo(1);
    }

    @Test
    void aPdfIsShownAsItIsWithoutRendering() {
        Imported pdf = importFile("guide.pdf", SampleFiles.pdf(ENGLISH));
        waitForExtraction(pdf);
        assertThat(pdf.browser.get("/api/v1/documents/" + pdf.id + "/versions/1/rendering").code()).isEqualTo("no_rendering");
        assertThat(jdbc.sql("select count(*) from rendering").query(Integer.class).single()).isZero();
    }

    @Test
    void aRenderingReturnedByAProcessTakenOverByAFileIsCleanedBeforeItIsKept() {
        workers.simulateNext("hostile");

        Imported imported = importFile("notes.md", "# Notes\n\nTexte.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(waitForExtraction(imported).text("currentVersion.extractionStatus")).isEqualTo("INDEXED");

        String html = imported.browser.get("/api/v1/documents/" + imported.id + "/versions/1/rendering").text("html");
        assertThat(html).contains("Avant").contains("Après").contains("lien")
                .doesNotContain("<script").doesNotContain("pwned").doesNotContain("onerror").doesNotContain("javascript:")
                .doesNotContain("root").doesNotContain("style").doesNotContain("<img");
        assertThat(jdbc.sql("select html from rendering_part").query(String.class).single()).isEqualTo(html);
        assertThat(jdbc.sql("select places::text from rendering").query(String.class).single()).doesNotContain("root");
    }

    @Test
    void aScanIsImportedAndMarkedAsSuch() {
        Response document = waitForExtraction(importFile("schema.pdf", SampleFiles.pdf(null, null)));

        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("SCANNED");
        assertThat(document.number("currentVersion.pageCount")).isEqualTo(2);
    }

    @Test
    void aProtectedFileFailsAtOnceAndIsNotTriedAgain() {
        Imported imported = importFile("plan.docx", SampleFiles.protectedDocx(ENGLISH));

        Response document = waitForExtraction(imported);

        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("FAILED");
        assertThat(document.text("currentVersion.extractionError")).isEqualTo("encrypted");
        assertThat(document.flag("actions.retry")).isFalse();
        assertThat(jdbc.sql("select count(*) from extraction_job").query(Integer.class).single()).isZero();
    }

    @Test
    void aWordDocumentCannotMakeTheExtractionProcessReadAFileOfTheServer(@TempDir Path folder) throws Exception {
        Path secret = Files.writeString(folder.resolve("secret.txt"), "TOP-SECRET-VALUE");
        byte[] docx = SampleFiles.docxWithDoctype("<!DOCTYPE w:document [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>",
                "Valeur : &xxe;");

        Response document = waitForExtraction(importFile("xxe.docx", docx));

        // Read without the entity, or refused as damaged: either way, never the file.
        assertThat(document.text("currentVersion.extractionStatus")).isIn("INDEXED", "FAILED");
        assertThat(jdbc.sql("select content from chunk").query(String.class).list())
                .noneSatisfy(content -> assertThat(content).contains("TOP-SECRET-VALUE"));
        assertThat(jdbc.sql("select html from rendering_part").query(String.class).list())
                .noneSatisfy(html -> assertThat(html).contains("TOP-SECRET-VALUE"));
        assertThat(jdbc.sql("select count(*) from extraction_job").query(Integer.class).single()).isZero();
    }

    @Test
    void aCrashedProcessIsReplacedAndTheFileTriedAgain() {
        workers.simulateNext("crash");

        Response document = waitForExtraction(importFile("guide.pdf", SampleFiles.pdf(ENGLISH)));

        assertThat(workers.simulationPending()).as("the first attempt crashed").isFalse();
        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
    }

    @Test
    void aProcessOutOfMemoryIsReplacedAndTheFileTriedAgain() {
        // The Java machine reports it on its way out, which must not be taken for a reply.
        workers.simulateNext("oom");

        Response document = waitForExtraction(importFile("guide.pdf", SampleFiles.pdf(ENGLISH)));

        assertThat(workers.simulationPending()).as("the first attempt ran out of memory").isFalse();
        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
    }

    @Test
    void aProcessThatHangsIsStoppedAtTheTimeoutAndTheFileTriedAgain() {
        workers.simulateNext("hang");

        Response document = waitForExtraction(importFile("guide.pdf", SampleFiles.pdf(ENGLISH)));

        assertThat(workers.simulationPending()).as("the first attempt hung").isFalse();
        assertThat(document.text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
    }

    @Test
    void stoppingLoreGivesTheExtractionInProgressBackWithoutCountingIt() throws InterruptedException {
        workers.simulateNext("hang");
        Imported imported = importFile("guide.pdf", SampleFiles.pdf(ENGLISH));
        Instant end = Instant.now().plusSeconds(20);
        while (jdbc.sql("select count(*) from extraction_job where status = 'RUNNING'").query(Integer.class).single() == 0
                && Instant.now().isBefore(end)) {
            Thread.sleep(100);
        }

        workers.stop();
        try {
            assertThat(jdbc.sql("select status || ':' || attempts from extraction_job").query(String.class).single())
                    .isEqualTo("PENDING:0");
        } finally {
            workers.start();
        }

        assertThat(waitForExtraction(imported).text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
    }

    @Test
    void aJobLeftRunningByAStoppedServerIsTakenOver() {
        Imported imported = importFile("guide.pdf", SampleFiles.pdf(ENGLISH));
        waitForExtraction(imported);
        UUID version = UUID.fromString(imported.browser.get("/api/v1/documents/" + imported.id).text("currentVersion.id"));

        // As if a server had stopped in the middle: a job running under a lease that expired.
        jdbc.sql("update document_version set extraction_status = 'PENDING' where id = ?").param(version).update();
        jdbc.sql("""
                insert into extraction_job (version_id, organization_id, sha256, media_type, status, attempts, lease, locked_until)
                select v.id, s.organization_id, v.sha256, v.media_type, 'RUNNING', 1, gen_random_uuid(), now() - interval '1 minute'
                from document_version v join document d on d.id = v.document_id join space s on s.id = d.space_id
                where v.id = ?
                """).param(version).update();
        signal.wake();

        assertThat(waitForExtraction(imported).text("currentVersion.extractionStatus")).isEqualTo("INDEXED");
    }

    private record Imported(Browser browser, String id) {
    }

    private Imported importFile(String filename, byte[] content) {
        Browser owner = setUpOrganization();
        String space = owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        Response imported = owner.upload("/api/v1/spaces/" + space + "/documents", filename, content);
        assertThat(imported.status()).as(imported.toString()).isEqualTo(201);
        return new Imported(owner, imported.text("id"));
    }

    /** The document once its extraction is over, or after a minute. */
    private Response waitForExtraction(Imported imported) {
        Instant end = Instant.now().plus(Duration.ofSeconds(60));
        while (true) {
            Response document = imported.browser.get("/api/v1/documents/" + imported.id);
            if (!"PENDING".equals(document.text("currentVersion.extractionStatus")) || Instant.now().isAfter(end)) {
                return document;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return document;
            }
        }
    }
}
