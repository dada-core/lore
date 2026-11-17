package com.dadacore.lore.ingestion.worker;

import static com.dadacore.lore.support.SampleFiles.write;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.support.SampleFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The rendering of each format, as the extraction process writes it, before Lore cleans it. */
class RenderingTests {

    private static final Pattern ID = Pattern.compile("id=\"([^\"]+)\"");
    private static final Pattern PART = Pattern.compile("-\\d+$");

    @TempDir
    Path folder;

    private final Extractors extractors = new Extractors(10_000_000);

    private static final String RUNBOOK = """
            Avant de commencer, prévenez l'astreinte.

            # Restaurer une base PostgreSQL

            Lancez `pg_restore` depuis la **dernière** sauvegarde, voir https://dada.group/docs.

            ## Préparer

            1. Arrêtez le service.
            2. Vérifiez les rôles : <kbd>Ctrl</kbd> + <kbd>C</kbd>, puis H<sub>2</sub>O.<br>

            | Serveur | Rôle |
            |---|---|
            | pg-01 | primaire |

            ```sql
            CREATE ROLE lore;
                GRANT ALL ON DATABASE lore TO lore;
            ```

            ## Erreurs fréquentes

            > ### Rôle absent
            > Créez-le d'abord.

            #

            Voir [la section](#erreurs-frequentes), [le guide](../guide.md), [piège](javascript:alert(1)),
            [écrire](mailto:astreinte@dada.group) et ![le schéma](https://dada.group/schema.png) ![local](schema.png).

            <script>alert(1)</script>
            <div onclick="x()">texte d'un bloc</div>
            <details><summary>Plus</summary>caché</details>
            """;

    @Test
    void markdownIsLaidOutWithTheAnchorsOfItsChunks() throws Exception {
        Extraction extraction = extractors.extract(write(folder, "runbook.md", RUNBOOK.getBytes(StandardCharsets.UTF_8)),
                Extractors.MARKDOWN);

        Rendering rendering = extraction.rendering();
        assertThat(rendering).isNotNull();
        String html = String.join("", rendering.parts());
        everyChunkHasItsPlace(extraction);
        assertThat(rendering.places()).extracting(Rendering.Place::anchor).containsExactly("h-0", "h-1", "h-2", "h-3", "h-4");
        assertThat(rendering.places()).extracting(Rendering.Place::level).containsExactly(0, 1, 2, 2, 3);
        assertThat(rendering.places()).extracting(Rendering.Place::title)
                .containsExactly(null, "Restaurer une base PostgreSQL", "Préparer", "Erreurs fréquentes", "Rôle absent");
        // Titles go one level down, under the title of the page; the title in a quote keeps its anchor.
        assertThat(html).contains("<section id=\"h-1\"><h2>Restaurer une base PostgreSQL</h2>")
                .contains("<h4 id=\"h-4\">Rôle absent</h4>");
        assertThat(html).contains("<code>pg_restore</code>").contains("<strong>dernière</strong>")
                .contains("<table>").contains("<td>pg-01</td>")
                .contains("<pre><code>CREATE ROLE lore;\n    GRANT ALL ON DATABASE lore TO lore;</code></pre>")
                .contains("<kbd>Ctrl</kbd>").contains("<sub>2</sub>").contains("<br>")
                .contains("<details><summary>Plus</summary>caché</details>");
        // Links to the web only, in a new tab; a title of the document by its anchor; the rest as text.
        assertThat(html).contains("<a href=\"https://dada.group/docs\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">")
                .contains("<a href=\"#h-3\">la section</a>")
                .contains("le guide").doesNotContain("../guide.md")
                .contains("piège").doesNotContain("javascript:")
                .contains("<a href=\"mailto:astreinte@dada.group\"");
        // Images are never loaded: a mark, with the link of an image on the web.
        assertThat(html).contains("<a class=\"lore-image\" href=\"https://dada.group/schema.png\"")
                .contains("<span class=\"lore-image\">local</span>").doesNotContain("<img");
        // HTML written in the file: the short list only, the rest left out.
        assertThat(html).doesNotContain("<script").doesNotContain("alert(1)</").doesNotContain("onclick")
                .doesNotContain("<div");
    }

    @Test
    void wordIsLaidOutFromTheXhtmlOfItsText() throws Exception {
        String body = """
                <w:p><w:r><w:t>Introduction du plan.</w:t></w:r></w:p>
                <w:p><w:hyperlink w:anchor="_Toc2"><w:r><w:t>Aller à l'annexe</w:t></w:r></w:hyperlink></w:p>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Plan de reprise</w:t></w:r></w:p>
                <w:p><w:r><w:t xml:space="preserve">Texte </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">gras </w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t xml:space="preserve">italique </w:t></w:r><w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t>souligné</w:t></w:r><w:r><w:br/><w:t>après un saut</w:t></w:r></w:p>
                <w:p><w:hyperlink r:id="rId10"><w:r><w:t>la documentation</w:t></w:r></w:hyperlink><w:r><w:t xml:space="preserve"> et </w:t></w:r><w:hyperlink r:id="rId11"><w:r><w:t>un piège</w:t></w:r></w:hyperlink></w:p>
                <w:p></w:p>
                <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Étapes</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Arrêter le service</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>Prévenir l'équipe</w:t></w:r></w:p>
                <w:tbl><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid>
                <w:tr><w:tc><w:p><w:r><w:t>Serveur</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Rôle</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>pg-01</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>primaire</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
                <w:p><w:pPr><w:pStyle w:val="Code"/></w:pPr><w:r><w:t xml:space="preserve">CREATE ROLE lore;</w:t></w:r><w:r><w:br/></w:r><w:r><w:tab/><w:t xml:space="preserve">GRANT   ALL;</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:bookmarkStart w:id="0" w:name="_Toc2"/><w:r><w:t>Annexe &lt;script&gt;</w:t></w:r><w:bookmarkEnd w:id="0"/></w:p>
                <w:p><w:r><w:t>Fin.</w:t></w:r></w:p>
                """;
        Extraction extraction = extractors.extract(write(folder, "plan.docx",
                SampleFiles.docxBody(body, "https://dada.group/docs?a=1&b=2", "javascript:alert(1)")), Extractors.DOCX);

        Rendering rendering = extraction.rendering();
        assertThat(rendering).isNotNull();
        String html = String.join("", rendering.parts());
        everyChunkHasItsPlace(extraction);
        assertThat(rendering.places()).extracting(Rendering.Place::anchor).containsExactly("h-0", "h-1", "h-2", "h-3");
        assertThat(html).contains("<section id=\"h-1\"><h2>Plan de reprise</h2>")
                .contains("<strong>gras </strong>").contains("<em>italique </em>").contains("<u>souligné</u>")
                .contains("<br>après un saut")
                .contains("<a href=\"https://dada.group/docs?a=1&amp;b=2\" target=\"_blank\"")
                .contains("un piège").doesNotContain("javascript:")
                // Word numbers its lists itself: they stay paragraphs that start with their number.
                .contains("<p>1. Arrêter le service</p>")
                .contains("<table>").contains("<td>pg-01</td>")
                .contains("<pre>CREATE ROLE lore;\n\tGRANT   ALL;</pre>")
                // A link to a bookmark in a title leads to that title, even before it.
                .contains("<a href=\"#h-3\">Aller à l&#39;annexe</a>")
                .contains("<h2>Annexe &lt;script&gt;</h2>");
        // Empty paragraphs and the list of links that Tika adds at the end are left out.
        assertThat(html).doesNotContain("<p></p>").doesNotContain("external-ref");
    }

    @Test
    void aTextKeepsItsSpacesAndItsLines() throws Exception {
        String text = "Procédure\n\tétape 1 :    arrêter\n\n" + "ligne de journal assez longue pour remplir\n".repeat(200)
                + "a".repeat(5_000) + "😀" + "b".repeat(10);
        Extraction extraction = extractors.extract(write(folder, "notes.txt", text.getBytes(StandardCharsets.UTF_8)),
                Extractors.TEXT);

        Rendering rendering = extraction.rendering();
        everyChunkHasItsPlace(extraction);
        // One part of 4,000 characters is one chunk and one section, under the same anchor.
        assertThat(extraction.chunks()).extracting(ExtractedChunk::anchor)
                .containsExactlyElementsOf(rendering.places().stream().map(Rendering.Place::anchor).toList());
        assertThat(rendering.parts().getFirst()).startsWith("<section id=\"part-1\"><pre>Procédure\n\tétape 1 :    arrêter\n\n");
        assertThat(String.join("", rendering.parts())).contains("😀").doesNotContain("<br>");
        assertThat(rendering.places()).allSatisfy(place -> assertThat(place.title()).isNull());
    }

    @Test
    void aLongRenderingIsCutInPartsBetweenTwoBlocks() {
        RenderingBuilder builder = new RenderingBuilder(100, 1_000);
        builder.section("h-1", 1, "Un", null);
        for (int i = 0; i < 5; i++) {
            builder.block("<p>" + "x".repeat(40) + "</p>");
        }
        builder.section("h-2", 2, "Deux", null);
        builder.block("<p>fin</p>");

        Rendering rendering = builder.build(false);

        assertThat(rendering.parts()).hasSizeGreaterThan(2);
        assertThat(rendering.parts().get(1)).startsWith("<section class=\"lore-continued\">");
        assertThat(rendering.parts()).allSatisfy(part -> assertThat(count(part, "<section")).isEqualTo(count(part, "</section>")));
        assertThat(rendering.places()).extracting(Rendering.Place::anchor).containsExactly("h-1", "h-2");
        assertThat(rendering.places().get(1).part()).isEqualTo(rendering.parts().size());
        assertThat(rendering.parts().get(rendering.places().get(1).part() - 1)).contains("id=\"h-2\"");
    }

    @Test
    void theRenderingStopsAtItsMaximumAndSaysSo() {
        RenderingBuilder builder = new RenderingBuilder(1_000, 300);
        builder.section("part-1", 0, null, null);
        for (int i = 0; i < 10; i++) {
            builder.block("<pre>" + "y".repeat(50) + "</pre>");
        }
        Rendering rendering = builder.build(false);
        assertThat(rendering.truncated()).isTrue();
        assertThat(String.join("", rendering.parts()).length()).isLessThan(400);
    }

    @Test
    void aTruncatedTextGivesATruncatedRendering() throws Exception {
        Extraction extraction = new Extractors(2_000).extract(write(folder, "long.md",
                ("# Titre\n\n" + "Une phrase de la procédure. ".repeat(500)).getBytes(StandardCharsets.UTF_8)), Extractors.MARKDOWN);

        assertThat(extraction.truncated()).isTrue();
        assertThat(extraction.rendering().truncated()).isTrue();
    }

    @Test
    void detailsHoldTheMarkdownThatFollowsThemAndScriptsLoseTheirContent() throws Exception {
        String markdown = """
                # Procédure

                <details>
                <summary>Commandes de secours</summary>

                Relancez **pg_restore** avec `--clean`.

                </details>

                <script type="text/javascript">document.title = "pris";</script>
                <style>body { display: none }</style>

                [piège][ref] et <span style="color:red" onclick="x()">texte</span> a < b

                [ref]: javascript:alert(1)
                """;
        Extraction extraction = extractors.extract(write(folder, "details.md", markdown.getBytes(StandardCharsets.UTF_8)),
                Extractors.MARKDOWN);

        String html = String.join("", extraction.rendering().parts());
        // The details open in one block and close after the paragraph they hold.
        assertThat(html).containsSubsequence("<details>", "<summary>Commandes de secours</summary>",
                "<strong>pg_restore</strong>", "</details>");
        assertThat(html).doesNotContain("pris").doesNotContain("display").doesNotContain("javascript")
                .doesNotContain("onclick").doesNotContain("style=").contains("piège").contains("texte").contains("a &lt; b");
    }

    @Test
    void anAnchorOutOfRangeInALinkIsOnlyText() throws Exception {
        Extraction extraction = extractors.extract(write(folder, "liens.md",
                "# Titre\n\nVoir [plus loin](#h-99999999999) et [là](#h-1).".getBytes(StandardCharsets.UTF_8)), Extractors.MARKDOWN);

        String html = String.join("", extraction.rendering().parts());
        assertThat(html).contains("Voir plus loin et <a href=\"#h-1\">là</a>");
    }

    @Test
    void theTitleInThePropertiesOfAWordFileIsNoPassage() throws Exception {
        Extraction extraction = extractors.extract(write(folder, "titre.docx", SampleFiles.docxWithTitle("Titre des propriétés",
                "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Plan</w:t></w:r></w:p>"
                        + "<w:p><w:r><w:t>Texte du plan.</w:t></w:r></w:p>")), Extractors.DOCX);

        assertThat(extraction.chunks()).extracting(ExtractedChunk::anchor).containsExactly("h-1");
        assertThat(extraction.chunks()).noneSatisfy(chunk -> assertThat(chunk.content()).contains("Titre des propriétés"));
        everyChunkHasItsPlace(extraction);
    }

    @Test
    void aSectionLeftOutAtTheMaximumHasNoPlace() {
        RenderingBuilder builder = new RenderingBuilder(1_000, 120);
        builder.section("h-1", 1, "Un", null);
        builder.block("<p>" + "a".repeat(60) + "</p>");
        builder.section("h-2", 1, "Deux", null);
        builder.block("<p>b</p>");

        Rendering rendering = builder.build(false);

        assertThat(rendering.truncated()).isTrue();
        assertThat(rendering.places()).extracting(Rendering.Place::anchor).containsExactly("h-1");
        assertThat(String.join("", rendering.parts())).doesNotContain("h-2");
        assertThat(rendering.parts()).noneSatisfy(part -> assertThat(part).isBlank());
    }

    @Test
    void aPdfHasNoRendering() throws Exception {
        Extraction extraction = extractors.extract(write(folder, "a.pdf", SampleFiles.pdf("Une page.")), Extractors.PDF);
        assertThat(extraction.rendering()).isNull();
    }

    @Test
    void tenMillionCharactersFitInTheResultOfTheProcess() throws Exception {
        StringBuilder markdown = new StringBuilder();
        for (int section = 0; markdown.length() < 10_000_000; section++) {
            markdown.append("## Section ").append(section).append("\n\n")
                    .append("Restaurer une base depuis la sauvegarde avec `pg_restore`. ".repeat(6))
                    .append("\n\n- un point\n- un autre point\n\n```\nCREATE ROLE lore;\n    GRANT ALL;\n```\n\n");
        }
        Path file = write(folder, "huge.md", markdown.toString().getBytes(StandardCharsets.UTF_8));

        long start = System.nanoTime();
        Extraction extraction = new Extractors(10_000_000).extract(file, Extractors.MARKDOWN);
        long millis = (System.nanoTime() - start) / 1_000_000;
        byte[] json = JsonMapper.builder().build().writeValueAsBytes(extraction);

        Rendering rendering = extraction.rendering();
        assertThat(rendering.parts()).hasSizeGreaterThan(20)
                .allSatisfy(part -> assertThat(part.length()).isLessThan(Rendering.PART_SIZE + 10_000));
        assertThat((long) json.length).as("written in %d ms", millis).isLessThan(100L * 1024 * 1024);
        everyChunkHasItsPlace(extraction);
    }

    /** Each chunk leads to its place in the rendering, and each identifier is there once. */
    private static void everyChunkHasItsPlace(Extraction extraction) {
        List<String> ids = new ArrayList<>();
        for (String part : extraction.rendering().parts()) {
            Matcher id = ID.matcher(part);
            while (id.find()) {
                ids.add(id.group(1));
            }
        }
        Set<String> unique = new HashSet<>(ids);
        assertThat(unique).as("each identifier once").hasSameSizeAs(ids);
        Set<String> places = new HashSet<>(extraction.rendering().places().stream().map(Rendering.Place::anchor).toList());
        assertThat(places).isEqualTo(unique);
        for (ExtractedChunk chunk : extraction.chunks()) {
            String place = chunk.anchor().matches("(page|h|part)-\\d+-\\d+") ? PART.matcher(chunk.anchor()).replaceFirst("")
                    : chunk.anchor();
            if (!unique.contains(place)) {
                throw new AssertionError("No place in the rendering for the chunk " + chunk.anchor());
            }
        }
    }

    private static int count(String text, String what) {
        int count = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) {
            count++;
        }
        return count;
    }
}
