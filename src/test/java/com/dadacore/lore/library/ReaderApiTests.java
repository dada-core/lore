package com.dadacore.lore.library;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import com.dadacore.lore.support.SampleFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;

/**
 * The reader: the rendering and the original of a version, the views and downloads in the audit
 * log, "Resume", the person in charge and the order of the library. The renderings are recorded
 * here as the extraction would record them (the extraction itself is in ExtractionApiTests).
 */
class ReaderApiTests extends ApiTest {

    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String ELFRIED = "elfried.yaba@dada.group";
    private static final String CHRIST = "christ.koffi@dada.group";
    private static final Path FILES = Path.of("target/test-files");

    /** A text long enough to be compressed, were it allowed: three kilobytes. */
    private static final byte[] TEXT = ("Procédure d'arrêt : vérifiez la sauvegarde, puis arrêtez le service.\n".repeat(45))
            .getBytes(StandardCharsets.UTF_8);
    private static final String NAME = "Plan de reprise d’activité \"2026\".txt";

    @Autowired
    ExtractionResults results;

    // The original.

    @Test
    void theOriginalIsDownloadedAsItWasImportedUnderItsName() throws Exception {
        Team team = team();
        String id = team.herve.upload(documents(team.space), NAME, TEXT).text("id");

        Browser.Raw file = team.elfried.raw("GET", file(id), "Accept-Encoding", "gzip, deflate, br");

        assertThat(file.status()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(TEXT);
        assertThat(sha256(file.body())).isEqualTo(sha(id));
        assertThat(file.header("Content-Type")).isEqualTo("text/plain");
        assertThat(ContentDisposition.parse(file.header("Content-Disposition")).getFilename()).isEqualTo(NAME);
        assertThat(ContentDisposition.parse(file.header("Content-Disposition")).isAttachment()).isTrue();
        assertThat(file.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(file.header("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(file.headers().allValues("Cache-Control")).containsExactly("private, no-cache");
        assertThat(file.header("Accept-Ranges")).isEqualTo("bytes");
        assertThat(file.header("ETag")).isEqualTo("\"" + sha(id) + "\"");
        assertThat(file.header("Last-Modified")).isNotNull();
        assertThat(file.header("Content-Length")).isEqualTo(String.valueOf(TEXT.length));
        // Never compressed, even a text: the ranges would no longer match the file.
        assertThat(file.header("Content-Encoding")).isNull();

        Browser.Raw head = team.elfried.raw("HEAD", file(id));
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.header("Content-Length")).isEqualTo(String.valueOf(TEXT.length));
        assertThat(head.body()).isEmpty();
    }

    @Test
    void anyNameComesBackAsItWasImported() {
        Team team = team();
        for (String name : List.of("数据库恢复手册.txt", "😀 runbook d’astreinte.md", "x".repeat(251) + ".txt",
                "a\\b \"c\" ; d=e.txt")) {
            String id = team.herve.upload(documents(team.space), name, ("Contenu " + name).getBytes(StandardCharsets.UTF_8))
                    .text("id");
            String stored = jdbc.sql("select filename from document_version where document_id = ?::uuid").param(id)
                    .query(String.class).single();

            Browser.Raw file = team.elfried.raw("GET", file(id));

            assertThat(file.status()).as(name).isEqualTo(200);
            assertThat(ContentDisposition.parse(file.header("Content-Disposition")).getFilename()).as(name).isEqualTo(stored);
            // The ASCII form, for the browsers that read only it.
            assertThat(file.header("Content-Disposition")).as(name).matches("attachment; filename=\"[\\x20-\\x7E]*\"; filename\\*=UTF-8''.*");
        }
    }

    @Test
    void theOriginalIsServedByRanges() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "notes.txt", TEXT).text("id");
        String etag = "\"" + sha(id) + "\"";

        Browser.Raw first = team.elfried.raw("GET", file(id), "Range", "bytes=0-9");
        assertThat(first.status()).isEqualTo(206);
        assertThat(first.header("Content-Range")).isEqualTo("bytes 0-9/" + TEXT.length);
        assertThat(first.body()).isEqualTo(java.util.Arrays.copyOfRange(TEXT, 0, 10));

        Browser.Raw end = team.elfried.raw("GET", file(id), "Range", "bytes=-5");
        assertThat(end.status()).isEqualTo(206);
        assertThat(end.body()).isEqualTo(java.util.Arrays.copyOfRange(TEXT, TEXT.length - 5, TEXT.length));

        Browser.Raw several = team.elfried.raw("GET", file(id), "Range", "bytes=0-4,10-14");
        assertThat(several.status()).isEqualTo(206);
        assertThat(several.header("Content-Type")).startsWith("multipart/byteranges; boundary=");
        assertThat(several.text()).contains("Content-Range: bytes 0-4/" + TEXT.length)
                .contains("Content-Range: bytes 10-14/" + TEXT.length)
                .contains(new String(TEXT, 0, 5, StandardCharsets.UTF_8));

        Browser.Raw outside = team.elfried.raw("GET", file(id), "Range", "bytes=999999-");
        assertThat(outside.status()).isEqualTo(416);
        assertThat(outside.header("Content-Range")).isEqualTo("bytes */" + TEXT.length);

        // A range of another file: the whole of this one.
        Browser.Raw changed = team.elfried.raw("GET", file(id), "Range", "bytes=0-9", "If-Range", "\"" + "0".repeat(64) + "\"");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body()).isEqualTo(TEXT);
        assertThat(team.elfried.raw("GET", file(id), "Range", "bytes=0-9", "If-Range", etag).status()).isEqualTo(206);
    }

    @Test
    void theBrowserKeepsTheFileButItsRightIsCheckedAtEachOpening() {
        Team team = team();
        String id = team.eli.upload(documents(team.space), "notes.txt", TEXT).text("id");
        String etag = "\"" + sha(id) + "\"";

        assertThat(team.elfried.raw("GET", file(id), "If-None-Match", etag).status()).isEqualTo(304);

        // Restricted to nobody: the reader no longer sees it, and is told nothing of the file.
        assertThat(team.eli.put("/api/v1/documents/" + id + "/access", body("restricted", true)).status()).isEqualTo(200);
        Browser.Raw refused = team.elfried.raw("GET", file(id), "If-None-Match", etag);
        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.header("ETag")).isNull();
    }

    @Test
    void aPdfOpensInTheBrowserButNoOtherFormat() {
        Team team = team();
        String pdf = team.herve.upload(documents(team.space), "guide.pdf", SampleFiles.pdf("Une page.")).text("id");
        String text = team.herve.upload(documents(team.space), "notes.txt", TEXT).text("id");

        Browser.Raw inline = team.elfried.raw("GET", file(pdf) + "?disposition=inline");
        assertThat(inline.status()).isEqualTo(200);
        assertThat(inline.header("Content-Type")).isEqualTo("application/pdf");
        assertThat(ContentDisposition.parse(inline.header("Content-Disposition")).isInline()).isTrue();
        assertThat(inline.header("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");

        assertThat(team.elfried.get(file(text) + "?disposition=inline").fieldErrors()).containsExactly("disposition:invalid_value");
        assertThat(team.elfried.get(file(text) + "?disposition=page").fieldErrors()).containsExactly("disposition:invalid_value");
        assertThat(team.elfried.get("/api/v1/documents/" + text + "/versions/2/file").status()).isEqualTo(404);
    }

    @Test
    void aFileMissingOnTheDiskIsAnnouncedThenGone() throws Exception {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "notes.txt", TEXT).text("id");
        assertThat(team.elfried.get("/api/v1/documents/" + id).flag("currentVersion.fileAvailable")).isTrue();
        assertThat(team.elfried.get("/api/v1/documents/" + id).text("currentVersion.sha256")).isEqualTo(sha(id));

        Files.delete(stored(sha(id)));

        assertThat(team.elfried.get("/api/v1/documents/" + id).flag("currentVersion.fileAvailable")).isFalse();
        Response gone = team.elfried.get(file(id));
        assertThat(gone.status()).isEqualTo(410);
        assertThat(gone.code()).isEqualTo("file_missing");
    }

    // The rendering.

    @Test
    void theRenderingIsReadPartByPartWithTheOutlineOfTheWholeDocument() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "runbook.md", "# Restaurer\n\nTexte.".getBytes(StandardCharsets.UTF_8))
                .text("id");
        assertThat(team.elfried.get(rendering(id)).code()).isEqualTo("extraction_pending");
        String long1 = "<p>" + "Restaurer la base depuis la sauvegarde. ".repeat(100) + "</p>";
        record(id, new Rendering(List.of(
                "<section id=\"h-0\"><p>Avant.</p></section><section id=\"h-1\"><h2>Restaurer</h2>" + long1 + "</section>",
                "<section id=\"h-2\"><h3>Erreurs</h3><p>Rôle absent.</p></section>"), List.of(
                new Rendering.Place("h-0", 1, 0, null),
                new Rendering.Place("h-1", 1, 1, "Restaurer"),
                new Rendering.Place("h-2", 2, 2, "Erreurs")), false));

        Response first = team.elfried.get(rendering(id));
        assertThat(first.status()).as(first.toString()).isEqualTo(200);
        assertThat(first.number("number")).isEqualTo(1);
        assertThat(first.number("part")).isEqualTo(1);
        assertThat(first.number("partCount")).isEqualTo(2);
        assertThat(first.text("html")).startsWith("<section id=\"h-0\">").doesNotContain("Erreurs");
        assertThat(first.at("outline").size()).isEqualTo(2);
        assertThat(first.text("outline.1.anchor")).isEqualTo("h-2");
        assertThat(first.number("outline.1.part")).isEqualTo(2);

        // The part of an anchor, its chunks included; an unknown anchor opens the first part.
        assertThat(team.elfried.get(rendering(id) + "?anchor=h-2").text("html")).contains("Rôle absent");
        assertThat(team.elfried.get(rendering(id) + "?anchor=h-2-3").number("part")).isEqualTo(2);
        assertThat(team.elfried.get(rendering(id) + "?anchor=h-99").number("part")).isEqualTo(1);
        assertThat(team.elfried.get(rendering(id) + "?part=2&outline=false").at("outline").isNull()).isTrue();
        assertThat(team.elfried.get(rendering(id) + "?part=3").fieldErrors()).containsExactly("part:out_of_range");
        assertThat(team.elfried.get("/api/v1/documents/" + id + "/versions/2/rendering").status()).isEqualTo(404);

        // Compressed, with a weak ETag; the same part again is not sent twice.
        Browser.Raw raw = team.elfried.raw("GET", rendering(id), "Accept-Encoding", "gzip", "Accept", "application/json");
        assertThat(raw.header("Content-Encoding")).isEqualTo("gzip");
        assertThat(raw.header("ETag")).startsWith("W/\"");
        assertThat(raw.headers().allValues("Cache-Control")).containsExactly("no-cache, private");
        assertThat(team.elfried.raw("GET", rendering(id), "If-None-Match", raw.header("ETag")).status()).isEqualTo(304);
    }

    @Test
    void aPdfOrAFailureHasNoRendering() {
        Team team = team();
        String pdf = team.herve.upload(documents(team.space), "guide.pdf", SampleFiles.pdf("Une page.")).text("id");
        results.extracted(version(pdf), new Extraction(false, "fr", 1, false, null,
                List.of(new ExtractedChunk(0, "page-1", 1, List.of(), "Une page.")), null));
        assertThat(team.elfried.get(rendering(pdf)).code()).isEqualTo("no_rendering");

        String text = team.herve.upload(documents(team.space), "notes.txt", TEXT).text("id");
        record(text, new Rendering(List.of("<section id=\"part-1\"><pre>x</pre></section>"),
                List.of(new Rendering.Place("part-1", 1, 0, null)), false));
        results.failed(version(text), "extractor_crashed");
        assertThat(team.elfried.get(rendering(text)).code()).isEqualTo("no_rendering");
        assertThat(jdbc.sql("select count(*) from rendering").query(Integer.class).single()).isZero();
    }

    // The audit log.

    @Test
    void aViewIsRecordedOnceAnHourAndADownloadEachTime() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "notes.txt", TEXT).text("id");
        String pdf = team.herve.upload(documents(team.space), "guide.pdf", SampleFiles.pdf("Une page.")).text("id");
        jdbc.sql("truncate table audit_event").update();

        team.elfried.get("/api/v1/documents/" + id);
        team.elfried.get("/api/v1/documents/" + id);
        team.eli.get("/api/v1/documents/" + id);
        team.elfried.raw("GET", file(id));
        team.elfried.raw("GET", file(id), "Range", "bytes=0-");
        // The resumption of a download, and the reading of a PDF in the page, are no downloads.
        team.elfried.raw("GET", file(id), "Range", "bytes=10-");
        team.elfried.raw("GET", file(pdf) + "?disposition=inline");
        team.elfried.raw("HEAD", file(id));
        // Nothing for a document the person does not see.
        team.elfried.get("/api/v1/documents/" + UUID.randomUUID());

        assertThat(jdbc.sql("select action || ':' || actor_id from audit_event order by id").query(String.class).list())
                .containsExactly("document.viewed:" + userId(ELFRIED), "document.viewed:" + userId(ELI),
                        "document.downloaded:" + userId(ELFRIED), "document.downloaded:" + userId(ELFRIED));
        assertThat(jdbc.sql("select details ->> 'filename' from audit_event where action = 'document.downloaded' limit 1")
                .query(String.class).single()).isEqualTo("notes.txt");
        assertThat(jdbc.sql("select details ->> 'version' from audit_event where action = 'document.viewed' limit 1")
                .query(String.class).single()).isEqualTo("1");

        // An hour later, it counts again.
        jdbc.sql("update audit_event set occurred_at = now() - interval '61 minutes'").update();
        team.elfried.get("/api/v1/documents/" + id);
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'document.viewed'").query(Integer.class).single())
                .isEqualTo(3);
    }

    // Resume.

    @Test
    void resumeShowsTheLastDocumentsThePersonOpenedAndStillSees() {
        Team team = team();
        List<String> ids = new ArrayList<>();
        for (String name : List.of("un.txt", "deux.txt", "trois.txt", "quatre.txt")) {
            ids.add(team.eli.upload(documents(team.space), name, ("Contenu " + name).getBytes(StandardCharsets.UTF_8))
                    .text("id"));
        }
        for (String id : ids) {
            team.elfried.get("/api/v1/documents/" + id);
            jdbc.sql("update audit_event set occurred_at = occurred_at - interval '1 minute'").update();
        }

        Response recent = team.elfried.get("/api/v1/documents/recent");
        assertThat(recent.json().size()).isEqualTo(3);
        assertThat(recent.text("0.title")).isEqualTo("Quatre");
        assertThat(recent.text("0.space.name")).isEqualTo("Infrastructure");
        assertThat(recent.text("0.viewedAt")).isNotNull();
        assertThat(team.elfried.get("/api/v1/documents/recent?size=10").json().size()).isEqualTo(4);
        assertThat(team.elfried.get("/api/v1/documents/recent?size=11").fieldErrors()).containsExactly("size:out_of_range");
        assertThat(team.eli.get("/api/v1/documents/recent").json().size()).isZero();

        // Restricted since, deleted: gone from Resume.
        team.eli.put("/api/v1/documents/" + ids.get(3) + "/access", body("restricted", true));
        team.eli.delete("/api/v1/documents/" + ids.get(2));
        assertThat(titles(team.elfried.get("/api/v1/documents/recent"))).containsExactly("Deux", "Un");

        // Out of the space: nothing left.
        team.eli.delete("/api/v1/spaces/" + team.space + "/members/" + userId(ELFRIED));
        assertThat(team.elfried.get("/api/v1/documents/recent").json().size()).isZero();
    }

    // The person in charge.

    @Test
    void theOwnerHandsARestrictedDocumentOverAndNoLongerSeesIt() {
        Team team = team();
        String id = team.herve.upload(documents(team.space), "notes.txt", TEXT, "restricted", "true").text("id");
        assertThat(team.herve.get("/api/v1/documents/" + id).flag("actions.changeOwner")).isTrue();
        assertThat(team.elfried.get("/api/v1/documents/" + id).status()).isEqualTo(404);
        team.eli.patch("/api/v1/spaces/" + team.space + "/members/" + userId(ELFRIED), body("role", "CONTRIBUTOR"));

        Response handed = team.herve.put("/api/v1/documents/" + id + "/owner", body("userId", userId(ELFRIED)));

        assertThat(handed.status()).as(handed.toString()).isEqualTo(200);
        assertThat(handed.text("owner.displayName")).isEqualTo("Elfried Yaba");
        assertThat(handed.flag("actions.edit")).isFalse();
        assertThat(team.elfried.get("/api/v1/documents/" + id).status()).isEqualTo(200);
        assertThat(team.herve.get("/api/v1/documents/" + id).status()).isEqualTo(404);
        // The maintainer sees it all the same.
        assertThat(team.eli.get("/api/v1/documents/" + id).status()).isEqualTo(200);
        assertThat(jdbc.sql("select details ->> 'toName' || ':' || (details ->> 'restricted') from audit_event"
                + " where action = 'document.owner_changed'").query(String.class).single()).isEqualTo("Elfried Yaba:true");
    }

    @Test
    void theNewOwnerIsAnActiveContributorOfTheSpace() {
        Team team = team();
        String id = team.eli.upload(documents(team.space), "notes.txt", TEXT).text("id");
        String owner = "/api/v1/documents/" + id + "/owner";
        Browser christ = join(team.owner, "MEMBER", "Christ Koffi", CHRIST);

        assertThat(team.eli.put(owner, body("userId", null)).fieldErrors()).containsExactly("userId:required");
        assertThat(team.eli.put(owner, body("userId", userId(CHRIST))).fieldErrors()).containsExactly("userId:not_space_member");
        assertThat(team.eli.put(owner, body("userId", UUID.randomUUID())).fieldErrors()).containsExactly("userId:not_space_member");
        assertThat(team.eli.put(owner, body("userId", userId(ELFRIED))).fieldErrors()).containsExactly("userId:not_contributor");
        // A contributor who is not its owner may not hand it over.
        assertThat(team.herve.put(owner, body("userId", userId(HERVE))).code()).isEqualTo("maintainer_or_owner_only");
        assertThat(team.elfried.put(owner, body("userId", userId(HERVE))).status()).isEqualTo(403);
        assertThat(christ.put(owner, body("userId", userId(HERVE))).status()).isEqualTo(404);

        // A disabled account cannot take it.
        team.owner.post("/api/v1/admin/members/" + userId(HERVE) + "/disable");
        assertThat(team.eli.put(owner, body("userId", userId(HERVE))).fieldErrors()).containsExactly("userId:not_space_member");
        team.owner.post("/api/v1/admin/members/" + userId(HERVE) + "/enable");
        assertThat(team.eli.put(owner, body("userId", userId(HERVE))).text("owner.displayName")).isEqualTo("Hervé Coulibaly");
        // The same person again changes nothing.
        assertThat(team.eli.put(owner, body("userId", userId(HERVE))).status()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'document.owner_changed'").query(Integer.class)
                .single()).isEqualTo(1);
    }

    // The order of the library.

    @Test
    void theLibraryIsSortedWithinEachFolder() {
        Team team = team();
        team.eli.upload(documents(team.space), "beta.txt", "bb".getBytes(StandardCharsets.UTF_8));
        team.eli.upload(documents(team.space), "alpha.txt", "aaaa".getBytes(StandardCharsets.UTF_8));
        team.eli.upload(documents(team.space), "gamma.txt", "g".getBytes(StandardCharsets.UTF_8));

        assertThat(titles(team.elfried.get(documents(team.space)).at("items"))).containsExactly("Gamma", "Alpha", "Beta");
        assertThat(titles(team.elfried.get(documents(team.space) + "?sort=title").at("items")))
                .containsExactly("Alpha", "Beta", "Gamma");
        assertThat(titles(team.elfried.get(documents(team.space) + "?sort=title&direction=desc").at("items")))
                .containsExactly("Gamma", "Beta", "Alpha");
        assertThat(titles(team.elfried.get(documents(team.space) + "?sort=size").at("items")))
                .containsExactly("Gamma", "Beta", "Alpha");
        assertThat(team.elfried.get(documents(team.space) + "?sort=popularity&direction=up").fieldErrors())
                .containsExactlyInAnyOrder("sort:invalid_value", "direction:invalid_value");
    }

    // Helpers.

    private record Team(Browser owner, Browser eli, Browser herve, Browser elfried, String space) {
    }

    /** The owner, Eli maintainer of Infrastructure, Hervé contributor, Elfried reader. */
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

    private void record(String documentId, Rendering rendering) {
        results.extracted(version(documentId), new Extraction(false, "fr", null, false, null,
                List.of(new ExtractedChunk(0, rendering.places().getFirst().anchor(), null, List.of(), "Texte.")), rendering));
    }

    private UUID version(String documentId) {
        return jdbc.sql("select current_version_id from document where id = ?::uuid").param(documentId).query(UUID.class).single();
    }

    private static String documents(String space) {
        return "/api/v1/spaces/" + space + "/documents";
    }

    private static String file(String id) {
        return "/api/v1/documents/" + id + "/versions/1/file";
    }

    private static String rendering(String id) {
        return "/api/v1/documents/" + id + "/versions/1/rendering";
    }

    private String sha(String documentId) {
        return jdbc.sql("select v.sha256 from document_version v where v.document_id = ?::uuid")
                .param(documentId).query(String.class).single();
    }

    private Path stored(String sha256) {
        UUID organization = jdbc.sql("select id from organization").query(UUID.class).single();
        return FILES.resolve(organization.toString()).resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4))
                .resolve(sha256);
    }

    private static List<String> titles(Response list) {
        return titles(list.json());
    }

    private static List<String> titles(tools.jackson.databind.JsonNode items) {
        List<String> titles = new ArrayList<>();
        items.forEach(item -> titles.add(item.get("title").asString()));
        return titles;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
