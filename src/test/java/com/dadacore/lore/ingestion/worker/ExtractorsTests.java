package com.dadacore.lore.ingestion.worker;

import static com.dadacore.lore.support.SampleFiles.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.support.SampleFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The text of each format, as the extraction process reads it. */
class ExtractorsTests {

    private static final String FRENCH = """
            Avant toute restauration, vérifiez que les rôles de la base existent sur le serveur cible.
            Arrêtez ensuite le service, puis lancez la restauration depuis la dernière sauvegarde.
            Si une erreur apparaît, notez-la dans le ticket et prévenez la personne d'astreinte.""";

    private static final String ENGLISH = """
            Before any restore, check that the roles of the database exist on the target server.
            Then stop the service, and run the restore from the latest backup of the cluster.
            If an error appears, write it in the ticket and tell the person who is on call.""";

    @TempDir
    Path folder;

    private final Extractors extractors = new Extractors(10_000_000);

    @Test
    void aPdfIsCutByPageAndItsLanguageFound() throws Exception {
        Path file = write(folder, "a.pdf", SampleFiles.pdf(FRENCH, ENGLISH, FRENCH));

        Extraction extraction = extractors.extract(file, Extractors.PDF);

        assertThat(extraction.scanned()).isFalse();
        assertThat(extraction.pageCount()).isEqualTo(3);
        assertThat(extraction.chunks()).extracting(ExtractedChunk::anchor).containsExactly("page-1", "page-2", "page-3");
        assertThat(extraction.chunks()).extracting(ExtractedChunk::page).containsExactly(1, 2, 3);
        assertThat(extraction.chunks().getFirst().content()).contains("vérifiez que les rôles");
        assertThat(extraction.language()).isEqualTo("fr");
        assertThat(extraction.truncated()).isFalse();
    }

    @Test
    void aPdfMadeOfImagesIsScanned() throws Exception {
        Extraction scan = extractors.extract(write(folder, "scan.pdf", SampleFiles.pdf(null, null, null)), Extractors.PDF);
        assertThat(scan.scanned()).isTrue();
        assertThat(scan.pageCount()).isEqualTo(3);
        assertThat(scan.chunks()).isEmpty();

        // Half of the pages without text is enough; their other pages are kept.
        Extraction half = extractors.extract(write(folder, "half.pdf", SampleFiles.pdf(ENGLISH, null)), Extractors.PDF);
        assertThat(half.scanned()).isTrue();
        assertThat(half.chunks()).extracting(ExtractedChunk::anchor).containsExactly("page-1");

        Extraction mostlyText = extractors.extract(write(folder, "text.pdf", SampleFiles.pdf(ENGLISH, ENGLISH, null)),
                Extractors.PDF);
        assertThat(mostlyText.scanned()).isFalse();
    }

    @Test
    void pagesWithoutTextButWithoutImageEitherAreNotAScan() throws Exception {
        // Slides with short titles, a form, a drawing labelled in a few letters, blank pages: no image
        // of a page, so not scanned, whatever little text they hold.
        Extraction slides = extractors.extract(write(folder, "slides.pdf", SampleFiles.pdf("Plan", "Merci", "Questions")),
                Extractors.PDF);
        assertThat(slides.scanned()).isFalse();
        assertThat(slides.chunks()).isNotEmpty();
        Extraction blank = extractors.extract(write(folder, "annexes.pdf", SampleFiles.pdf(ENGLISH, "", "")), Extractors.PDF);
        assertThat(blank.scanned()).isFalse();

        // An image on the pages without text: scanned, as before.
        Extraction scan = extractors.extract(write(folder, "scan-with-cover.pdf", SampleFiles.pdf("Manuel", null, null)),
                Extractors.PDF);
        assertThat(scan.scanned()).isTrue();
    }

    @Test
    void aScanIsFoundWhereverThePdfPutsTheImageOfItsPages() throws Exception {
        // Written in the content of each page, as some scanners and fax programs do, or shown by a
        // stamp over it: an image all the same.
        for (SampleFiles.ScanImage kind : SampleFiles.ScanImage.values()) {
            Extraction scan = extractors.extract(write(folder, kind + ".pdf", SampleFiles.scannedPdf(kind, 2)),
                    Extractors.PDF);
            assertThat(scan.scanned()).as(kind.name()).isTrue();
            assertThat(scan.pageCount()).as(kind.name()).isEqualTo(2);
        }
    }

    @Test
    void aProtectedFileIsReportedAsSuch() {
        assertThatThrownBy(() -> extractors.extract(write(folder, "p.pdf", SampleFiles.protectedPdf(ENGLISH)), Extractors.PDF))
                .isInstanceOfSatisfying(ExtractionFailure.class, f -> assertThat(f.code()).isEqualTo("encrypted"));
        assertThatThrownBy(() -> extractors.extract(write(folder, "p.docx", SampleFiles.protectedDocx(ENGLISH)), Extractors.DOCX))
                .isInstanceOfSatisfying(ExtractionFailure.class, f -> assertThat(f.code()).isEqualTo("encrypted"));
    }

    @Test
    void aWordDocumentIsCutByTitle() throws Exception {
        Path file = write(folder, "plan.docx", SampleFiles.docx(
                "Introduction du plan de reprise, à lire en premier.",
                "# Restaurer la base",
                FRENCH,
                "## Vérifier les rôles",
                "Lancez la requête de contrôle avant la restauration.",
                "# Après la reprise",
                "Prévenez les équipes."));

        Extraction extraction = extractors.extract(file, Extractors.DOCX);

        assertThat(extraction.chunks()).extracting(ExtractedChunk::anchor).containsExactly("h-0", "h-1", "h-2", "h-3");
        assertThat(extraction.chunks().get(2).headings()).containsExactly("Restaurer la base", "Vérifier les rôles");
        assertThat(extraction.chunks().get(3).headings()).containsExactly("Après la reprise");
        assertThat(extraction.chunks().get(2).content()).isEqualTo("Lancez la requête de contrôle avant la restauration.");
        assertThat(extraction.pageCount()).isNull();
    }

    @Test
    void aFileThatIsNotWhatItsNameSaysIsRefused() {
        assertThatThrownBy(() -> extractors.extract(write(folder, "x.docx", SampleFiles.emptyOle2()), Extractors.DOCX))
                .isInstanceOfSatisfying(ExtractionFailure.class, f -> assertThat(f.code()).isEqualTo("unsupported_content"));
        assertThatThrownBy(() -> extractors.extract(write(folder, "y.pdf", "not a PDF".getBytes(StandardCharsets.UTF_8)), Extractors.PDF))
                .isInstanceOfSatisfying(ExtractionFailure.class, f -> assertThat(f.code()).isEqualTo("corrupt"));
    }

    @Test
    void aCompressionBombIsReadAsAStreamAndCut() throws Exception {
        // Twenty megabytes of text in a few kilobytes: read as it comes, and cut at the maximum.
        Extraction extraction = new Extractors(1_000_000).extract(write(folder, "bomb.docx", SampleFiles.compressionBomb()),
                Extractors.DOCX);

        assertThat(extraction.truncated()).isTrue();
        assertThat(extraction.chunks()).isEmpty();
    }

    @Test
    void markdownKeepsItsTitlesAndCode() throws Exception {
        Path file = write(folder, "runbook.md", """
                # Rotation des certificats

                Le certificat interne expire chaque année.

                ## Étapes

                1. Générez la clé.
                2. Signez la demande.

                ```
                openssl req -new -key tls.key -out tls.csr
                ```
                """.getBytes(StandardCharsets.UTF_8));

        Extraction extraction = extractors.extract(file, Extractors.MARKDOWN);

        assertThat(extraction.title()).isEqualTo("Rotation des certificats");
        assertThat(extraction.chunks()).extracting(ExtractedChunk::anchor).containsExactly("h-1", "h-2");
        assertThat(extraction.chunks().get(1).headings()).containsExactly("Rotation des certificats", "Étapes");
        assertThat(extraction.chunks().get(1).content()).contains("Générez la clé.").contains("openssl req -new");
    }

    @Test
    void anOlderTextFileIsReadAsWindows1252() throws Exception {
        Path file = write(folder, "notes.txt", SampleFiles.text("Procédure d'arrêt : vérifiez la sauvegarde.\n\n" + FRENCH,
                Charset.forName("windows-1252")));

        Extraction extraction = extractors.extract(file, Extractors.TEXT);

        assertThat(extraction.chunks()).extracting(ExtractedChunk::anchor).containsExactly("part-1");
        assertThat(extraction.chunks().getFirst().content()).startsWith("Procédure d'arrêt");
        assertThat(extraction.language()).isEqualTo("fr");
    }

    @Test
    void aLongTextIsCutIntoChunksThenTruncated() throws Exception {
        String paragraph = ENGLISH.replace('\n', ' ');
        String text = (paragraph + "\n\n").repeat(200);
        Path file = write(folder, "long.txt", text.getBytes(StandardCharsets.UTF_8));

        Extraction whole = extractors.extract(file, Extractors.TEXT);
        assertThat(whole.chunks()).hasSizeGreaterThan(10).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(4000));
        assertThat(whole.truncated()).isFalse();
        assertThat(whole.language()).isEqualTo("en");

        Extraction cut = new Extractors(10_000).extract(file, Extractors.TEXT);
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.chunks().stream().mapToInt(c -> c.content().length()).sum()).isLessThanOrEqualTo(10_000);
    }

    @Test
    void aPdfLongerThanTheLimitIsCutNotRefused() throws Exception {
        String[] pages = new String[30];
        java.util.Arrays.fill(pages, "Restaurez la base de facturation depuis la sauvegarde de la nuit. ".repeat(6));
        Path file = write(folder, "manuel.pdf", SampleFiles.pdf(pages));

        Extraction extraction = new Extractors(2_000).extract(file, Extractors.PDF);

        assertThat(extraction.truncated()).isTrue();
        assertThat(extraction.chunks()).isNotEmpty();
        assertThat(extraction.chunks().stream().mapToInt(chunk -> chunk.content().length()).sum()).isLessThanOrEqualTo(2_000);
    }

    @Test
    void aHugeTitleIsCutAndCountedInTheText() throws Exception {
        String title = "Titre ".repeat(100_000);
        Path file = write(folder, "titre.md", ("# " + title + "\n\nCorps.\n\n## Suite\n\nFin.").getBytes(StandardCharsets.UTF_8));

        Extraction extraction = extractors.extract(file, Extractors.MARKDOWN);

        assertThat(extraction.title()).hasSizeLessThanOrEqualTo(XhtmlText.MAX_HEADING);
        assertThat(extraction.chunks()).allSatisfy(chunk -> assertThat(chunk.headings())
                .allSatisfy(heading -> assertThat(heading).hasSizeLessThanOrEqualTo(XhtmlText.MAX_HEADING)));
    }

    @Test
    void aWordDocumentCannotReadAFileOfTheServer() throws Exception {
        Path secret = write(folder, "secret.txt", "TOP-SECRET-VALUE".getBytes(StandardCharsets.UTF_8));
        byte[] docx = SampleFiles.docxWithDoctype("<!DOCTYPE w:document [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>",
                "Valeur : &xxe;");
        Path file = write(folder, "xxe.docx", docx);

        try {
            Extraction extraction = extractors.extract(file, Extractors.DOCX);
            assertThat(extraction.chunks()).noneSatisfy(chunk -> assertThat(chunk.content()).contains("TOP-SECRET-VALUE"));
        } catch (ExtractionFailure refused) {
            // Refusing the document is fine too: what matters is that the file is never read.
            assertThat(refused.code()).isEqualTo("corrupt");
        }
    }

    @Test
    void aStrayByteInAUtf8TextKeepsItsAccents() throws Exception {
        byte[] text = "Procédure de reprise après incident : vérifiez l’état des répliques.\n".repeat(200)
                .getBytes(StandardCharsets.UTF_8);
        byte[] damaged = java.util.Arrays.copyOf(text, text.length + 1);
        damaged[text.length] = (byte) 0xE9;
        Path file = write(folder, "reprise.txt", damaged);

        Extraction extraction = extractors.extract(file, Extractors.TEXT);

        assertThat(extraction.chunks().getFirst().content()).contains("Procédure de reprise après incident");
        assertThat(extraction.chunks()).noneSatisfy(chunk -> assertThat(chunk.content()).contains("Ã©"));
    }

    @Test
    void anEmptyWordHeadingKeepsThePathOfTitles() throws Exception {
        Path file = write(folder, "plan.docx", SampleFiles.docx("# Exploitation", "## Restauration", "#",
                "### Étapes", "Arrêtez le service, puis restaurez la base."));

        Extraction extraction = extractors.extract(file, Extractors.DOCX);

        ExtractedChunk steps = extraction.chunks().stream()
                .filter(chunk -> chunk.content().contains("Arrêtez le service")).findFirst().orElseThrow();
        assertThat(steps.headings()).containsExactly("Exploitation", "Restauration", "Étapes");
    }

    @Test
    void aLongWordIsNeverCutInTheMiddleOfACharacter() throws Exception {
        // An emoji, written with two units, astride the end of a chunk.
        String text = "a".repeat(3_999) + "😀" + "b".repeat(100);
        Path file = write(folder, "emoji.txt", text.getBytes(StandardCharsets.UTF_8));

        Extraction extraction = extractors.extract(file, Extractors.TEXT);

        assertThat(extraction.chunks()).allSatisfy(chunk -> assertThat(chunk.content().codePoints()
                .noneMatch(point -> point >= 0xD800 && point <= 0xDFFF)).as("no half character").isTrue());
        assertThat(extraction.chunks().stream().map(ExtractedChunk::content).reduce("", String::concat)).contains("😀");
    }

    @Test
    void aTextInNeitherLanguageIsOther() {
        assertThat(Languages.detect("SELECT id, name FROM space WHERE organization_id = ?; -- 42 rows")).isEqualTo("other");
        assertThat(Languages.detect("Dies ist eine Anleitung für die Wiederherstellung der Datenbank.")).isEqualTo("other");
    }
}
