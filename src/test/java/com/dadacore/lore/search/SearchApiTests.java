package com.dadacore.lore.search;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;

import tools.jackson.databind.JsonNode;

/** What the search finds, how it ranks it, and what it says of it. */
class SearchApiTests extends ApiTest {

    @Autowired
    ExtractionResults results;

    private Browser owner;
    private String space;
    private int counter;

    @BeforeEach
    void organization() {
        owner = setUpOrganization();
        space = "/api/v1/spaces/" + owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
    }

    @Test
    void identifiersAccentsAndFormsAreFoundAsTyped() {
        String runbook = document("Restaurer une base", "fr", passage("h-7-2", List.of("4. Vérifier", "4.2 Erreurs fréquentes"),
                "Si pg_restore affiche pg_restore: error: role \"reporting\" does not exist, créez le rôle sur 10.0.3.17 puis relancez."));
        String guide = document("Redémarrage du serveur de fichiers", "fr", passage("h-1", List.of("Redémarrage"),
                "Le navigateur affiche ERR_CONNECTION_REFUSED pendant le redémarrage : voir https://lore.example.com/docs/reprise pour la suite."));

        assertThat(found("pg_restore")).containsExactly(runbook);
        assertThat(found("ERR_CONNECTION_REFUSED")).containsExactly(guide);
        assertThat(found("10.0.3.17")).containsExactly(runbook);
        assertThat(found("10.0.3.18")).isEmpty();
        assertThat(found("ROLE")).containsExactly(runbook);
        assertThat(found("redemarrer serveurs")).containsExactly(guide);
        assertThat(found("https://lore.example.com/docs/reprise")).containsExactly(guide);
        assertThat(found("lore.example.com")).containsExactly(guide);
        assertThat(found("pg_restore: error: role \"reporting\" does not exist")).containsExactly(runbook);
        // The syntax: a phrase, one or the other, a word left out.
        assertThat(found("\"does not exist\"")).containsExactly(runbook);
        assertThat(found("\"reporting role\"")).isEmpty();
        assertThat(found("pg_restore or ERR_CONNECTION_REFUSED")).containsExactlyInAnyOrder(runbook, guide);
        assertThat(found("relancez -rôle")).isEmpty();
        // Left out anywhere in the document: its title too.
        assertThat(found("ERR_CONNECTION_REFUSED -fichiers")).isEmpty();
        assertThat(found("ERR_CONNECTION_REFUSED -barman")).containsExactly(guide);
    }

    @Test
    void whatIsTypedNeverBreaksTheSearchNorLeavesOutMoreThanAsked() {
        String dump = document("Sauvegarde logique", "fr", passage("h-1", List.of(),
                "Lancez pg_dump sur le schéma public : ce n'est pas long. Puis AWS SES envoie le rapport."));
        String other = document("Rapport AWS", "fr", passage("h-1", List.of(), "AWS envoie le rapport chaque nuit."));

        // An option of a pasted command, a stop word, a negation inside an exclusion: nothing more is left out.
        assertThat(found("pg_dump -n public")).containsExactly(dump);
        assertThat(found("pg_dump -le public")).containsExactly(dump);
        assertThat(found("rapport -(-bar)")).containsExactlyInAnyOrder(dump, other);
        assertThat(found("rapport -\"pg_dump sur\"")).containsExactly(other);
        // A stop word in one language only is a name here: SES is searched.
        assertThat(found("AWS SES")).containsExactly(dump);
        // A control character: neither an error nor a mark.
        assertThat(owner.get("/api/v1/search?q=pg_dump%00public").status()).isEqualTo(200);
        assertThat(owner.get("/api/v1/search/titles?q=sauv%00").status()).isEqualTo(200);
        owner.patch("/api/v1/documents/" + other, body("title", "Rapport \u0002officiel\u0003 AWS"));
        assertThat(owner.get("/api/v1/documents/" + other).text("title")).isEqualTo("Rapport  officiel  AWS");
    }

    @Test
    void aSearchWithNothingToLookForIsRefused() {
        document("Restaurer une base", "fr", passage("h-1", List.of(), "Restaurer la base."));

        assertThat(owner.get(search("le la de")).json().at("/errors/0/code").asString()).isEqualTo("common_words_only");
        assertThat(owner.get(search("the")).json().at("/errors/0/code").asString()).isEqualTo("common_words_only");
        assertThat(owner.get(search("-barman")).json().at("/errors/0/code").asString()).isEqualTo("exclusions_only");
        assertThat(owner.get(search("???")).json().at("/errors/0/code").asString()).isEqualTo("common_words_only");
        assertThat(owner.get("/api/v1/search").json().at("/errors/0/code").asString()).isEqualTo("required");
        // An unclosed quote, or a lone "or", are taken as they come.
        assertThat(owner.get(search("\"restaurer la")).status()).isEqualTo(200);
        assertThat(owner.get(search("or restaurer or")).status()).isEqualTo(200);
    }

    @Test
    void aResultGivesItsBestPassageItsExcerptAndItsOtherPassages() {
        String id = document("Restaurer une base", "fr",
                passage("h-1", List.of("1. Préparer"), "Vérifiez la sauvegarde avant de commencer."),
                passage("h-2", List.of("2. Restaurer"), "Lancez pg_restore avec le rôle propriétaire."),
                passage("h-7-2", List.of("4. Vérifier", "4.2 Erreurs fréquentes"),
                        "Si pg_restore signale role \"reporting\" does not exist, créez le rôle 🚀 avant de relancer pg_restore."));

        JsonNode result = owner.get(search("pg_restore role")).json().at("/items/0");

        assertThat(result.path("match").asString()).isEqualTo("passage");
        assertThat(result.path("version").asInt()).isEqualTo(1);
        assertThat(result.at("/passage/anchor").asString()).isEqualTo("h-7-2");
        assertThat(texts(result.at("/passage/headings"))).containsExactly("4. Vérifier", "4.2 Erreurs fréquentes");
        String excerpt = result.at("/passage/excerpt").asString();
        assertThat(excerpt).contains("pg_restore signale role").doesNotContain("\u0002").doesNotContain("\u0003");
        List<String> marked = new ArrayList<>();
        result.at("/passage/highlights").forEach(range -> marked.add(excerpt.substring(range.get(0).asInt(), range.get(1).asInt())));
        assertThat(marked).contains("pg_restore", "role", "rôle");
        assertThat(texts(result.path("words"))).contains("pg_restore", "role", "rôle");
        assertThat(result.at("/otherPassages/total").asInt()).isEqualTo(1);
        assertThat(result.at("/otherPassages/items/0/anchor").asString()).isEqualTo("h-2");
        assertThat(result.at("/document/id").asString()).isEqualTo(id);
    }

    @Test
    void aDocumentIsFoundByItsTitleOrDescriptionAndAScannedOneByNothingElse() {
        String titled = document("Bascule Kubernetes", "fr", passage("h-1", List.of(), "Procédure de secours."));
        String described = document("Plan de reprise", "fr", passage("h-1", List.of(), "Ordre de remise en service."));
        owner.patch("/api/v1/documents/" + described, body("description", "Remise en service de la facturation."));
        String scanned = upload("schema.txt");
        results.extracted(version(scanned), new Extraction(true, null, 3, false, null, List.of(), null));
        owner.patch("/api/v1/documents/" + scanned, body("title", "Schéma de câblage", "description", "Baies et onduleurs."));
        String pending = upload("en-cours.txt");
        owner.patch("/api/v1/documents/" + pending, body("title", "Kubernetes en cours"));

        JsonNode title = owner.get(search("kubernetes")).json().at("/items/0");
        assertThat(found("kubernetes")).containsExactly(titled);
        assertThat(title.path("match").asString()).isEqualTo("title");
        assertThat(title.path("passage").isNull()).isTrue();
        assertThat(title.at("/document/titleHighlights/0/0").asInt()).isEqualTo(8);
        JsonNode description = owner.get(search("facturation")).json().at("/items/0");
        assertThat(description.path("match").asString()).isEqualTo("description");
        assertThat(description.at("/description/text").asString()).contains("facturation");
        assertThat(found("onduleurs")).containsExactly(scanned);
        assertThat(found("câblage")).containsExactly(scanned);
        assertThat(owner.get(search("câblage")).json().at("/items/0/document/extractionStatus").asString()).isEqualTo("SCANNED");
        // Being extracted for the first time: not found yet, but the palette names it.
        assertThat(found("cours")).isEmpty();
        assertThat(owner.get("/api/v1/search/titles?q=kub").json()).hasSize(2);
    }

    @Test
    void obsoleteDocumentsComeLastAndTheOrderIsStable() {
        String obsolete = document("Sauvegardes avec Barman", "fr", passage("h-1", List.of("Sauvegardes"), "Sauvegardes sauvegardes sauvegardes."));
        jdbc.sql("update document set status = 'OBSOLETE' where id = ?::uuid").param(obsolete).update();
        String titled = document("Sauvegardes nocturnes", "fr", passage("h-1", List.of(), "Une sauvegarde par nuit."));
        String text = document("Plan de reprise", "fr", passage("h-1", List.of(), "La sauvegarde complète."));

        assertThat(found("sauvegardes")).containsExactly(titled, text, obsolete);
        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Response one = owner.get(search("sauvegardes") + "&size=1&page=" + page);
            assertThat(one.number("total")).isEqualTo(3);
            paged.add(one.json().at("/items/0/document/id").asString());
        }
        assertThat(paged).containsExactly(titled, text, obsolete);
    }

    @Test
    void beyondTheFirstDocumentsRankedThePagesStillFollowOneOrder() {
        // 230 documents that hold the same word, more than the 200 ranked by their passages.
        UUID spaceId = UUID.fromString(space.substring(space.lastIndexOf('/') + 1));
        jdbc.sql("""
                with d as (
                    insert into document (space_id, title, status)
                    select ?, 'Note ' || n, 'PUBLISHED' from generate_series(1, 230) n returning id, title
                ), v as (
                    insert into document_version (document_id, number, sha256, filename, media_type, size_bytes,
                        extraction_status, extracted_at, language)
                    select id, 1, repeat('c', 64), 'note.txt', 'text/plain', 10, 'INDEXED', now(), 'fr' from d
                    returning id, document_id
                )
                insert into chunk (version_id, ordinal, anchor, headings, content, language)
                select v.id, 1, 'part-1', '{}', repeat('consigne ', (random() * 5)::int + 1) || 'du jour', 'fr' from v
                """).param(spaceId).update();
        // A statement does not see the rows its own subqueries insert: the current version, after.
        jdbc.sql("""
                update document d set current_version_id = v.id
                from document_version v where v.document_id = d.id and d.current_version_id is null and d.title like 'Note %'
                """).update();

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            Response one = owner.get(search("consigne") + "&size=50&page=" + page);
            assertThat(one.number("total")).isEqualTo(230);
            seen.addAll(found(one));
            one.json().path("items").forEach(item -> assertThat(item.path("match").asString()).isEqualTo("passage"));
        }
        assertThat(seen).hasSize(230).doesNotHaveDuplicates();
        assertThat(owner.get(search("consigne") + "&size=50&page=20").json().at("/errors/0/field").asString())
                .isEqualTo("page");
    }

    @Test
    void theFiltersNarrowTheResultsAndTheirCountsStayThoseWithoutFilters() {
        String runbook = document("Restaurer", "fr", passage("h-1", List.of(), "Sauvegarde de la base."));
        owner.patch("/api/v1/documents/" + runbook, body("category", "RUNBOOK"));
        String procedure = document("Exploiter", "fr", passage("h-1", List.of(), "Sauvegarde du serveur."));
        owner.patch("/api/v1/documents/" + procedure, body("category", "OPERATING_PROCEDURE"));
        jdbc.sql("update document set updated_at = now() - interval '90 days' where id = ?::uuid").param(procedure).update();

        Response filtered = owner.get(search("sauvegarde") + "&category=RUNBOOK");
        assertThat(found(filtered)).containsExactly(runbook);
        assertThat(filtered.number("total")).isEqualTo(1);
        assertThat(filtered.json().path("facets").path("categories")).hasSize(2);
        assertThat(found(owner.get(search("sauvegarde") + "&updated=30d"))).containsExactly(runbook);
        assertThat(found(owner.get(search("sauvegarde") + "&format=text/plain"))).hasSize(2);
        assertThat(found(owner.get(search("sauvegarde") + "&format=application/pdf"))).isEmpty();
        assertThat(found(owner.get(search("sauvegarde") + "&status=draft"))).hasSize(2);
        assertThat(owner.get(search("sauvegarde") + "&category=NOPE").json().at("/errors/0/field").asString()).isEqualTo("category");
    }

    @Test
    void whatChangesIsFoundAtOnce() {
        String id = document("Note", "fr", passage("h-1", List.of(), "Le serveur de fichiers."));
        owner.patch("/api/v1/documents/" + id, body("title", "Rotation des certificats"));
        assertThat(found("certificats")).containsExactly(id);
        owner.patch("/api/v1/documents/" + id, body("description", "Autorité interne"));
        assertThat(found("autorité")).containsExactly(id);
    }

    @Test
    void theLanguageIsChangedIndexedAgainAndKept() {
        String id = document("Guide", "fr", passage("h-1", List.of(), "The servers were restarted."));
        UUID version = version(id);

        Response changed = owner.patch("/api/v1/documents/" + id, body("language", "en"));

        assertThat(changed.text("currentVersion.language")).isEqualTo("en");
        assertThat(jdbc.sql("select language from chunk where version_id = ?").param(version).query(String.class).single())
                .isEqualTo("en");
        assertThat(jdbc.sql("select search_language from document where id = ?::uuid").param(id).query(String.class).single())
                .isEqualTo("en");
        // The English stemmer now reads it: "restart" finds "restarted" in the vector of the language.
        assertThat(jdbc.sql("select tsv_lang @@ to_tsquery('lore_en', 'restart') from chunk where version_id = ?")
                .param(version).query(Boolean.class).single()).isTrue();
        assertThat(auditActions()).contains("document.updated");
        // A new extraction of the same version keeps the language chosen.
        results.extracted(version, new Extraction(false, "fr", null, false, null,
                List.of(new ExtractedChunk(0, "h-1", null, List.of(), "The servers were restarted.")), null));
        assertThat(owner.get("/api/v1/documents/" + id).text("currentVersion.language")).isEqualTo("en");
        assertThat(owner.patch("/api/v1/documents/" + id, body("language", "de")).json().at("/errors/0/code").asString())
                .isEqualTo("invalid_value");
        // While the text is being extracted, the language waits for its end.
        String pending = upload("attente.txt");
        assertThat(owner.patch("/api/v1/documents/" + pending, body("language", "en")).code()).isEqualTo("extraction_pending");
    }

    @Test
    void anExcerptIsPlainTextWhateverTheDocumentHolds() {
        document("Page web", "en", passage("h-1", List.of(), "Restart <b>nginx</b> then <script>alert(1)</script> check ‮evil."));
        JsonNode result = owner.get(search("nginx")).json().at("/items/0");
        String excerpt = result.at("/passage/excerpt").asString();
        assertThat(excerpt).contains("nginx").doesNotContain("<b>").doesNotContain("<script>");
    }

    @Test
    void thePaletteNamesTheTitlesThatBeginWithTheWordsTyped() {
        String first = document("Restauration de la base", "fr", passage("h-1", List.of(), "Texte."));
        String second = document("Plan de restauration", "fr", passage("h-1", List.of(), "Texte."));
        document("Restructuration", "fr", passage("h-1", List.of(), "Texte."));

        JsonNode titles = owner.get("/api/v1/search/titles?q=restaura").json();

        assertThat(titles).hasSize(2);
        assertThat(titles.get(0).path("id").asString()).isEqualTo(first);
        assertThat(titles.get(1).path("id").asString()).isEqualTo(second);
        assertThat(titles.get(0).at("/titleHighlights/0/0").asInt()).isZero();
        assertThat(titles.get(0).at("/titleHighlights/0/1").asInt()).isEqualTo(8);
        assertThat(owner.get("/api/v1/search/titles?q=plan%20restau").json()).hasSize(1);
        assertThat(owner.get("/api/v1/search/titles?q=RESTAURATION").json()).hasSize(2);
    }

    private String document(String title, String language, ExtractedChunk... passages) {
        String id = upload("document-" + (++counter) + ".txt");
        owner.patch("/api/v1/documents/" + id, body("title", title));
        results.extracted(version(id), new Extraction(false, language, null, false, null, List.of(passages), null));
        return id;
    }

    private String upload(String filename) {
        Response created = owner.upload(space + "/documents", filename, ("Contenu " + (++counter)).getBytes());
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.text("id");
    }

    private UUID version(String id) {
        return jdbc.sql("select current_version_id from document where id = ?::uuid").param(id).query(UUID.class).single();
    }

    private ExtractedChunk passage(String anchor, List<String> headings, String content) {
        return new ExtractedChunk(++counter, anchor, null, headings, content);
    }

    private static String search(String q) {
        return "/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    private List<String> found(String q) {
        return found(owner.get(search(q)));
    }

    private static List<String> found(Response response) {
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        response.json().path("items").forEach(item -> ids.add(item.at("/document/id").asString()));
        return ids;
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asString()));
        return values;
    }
}
