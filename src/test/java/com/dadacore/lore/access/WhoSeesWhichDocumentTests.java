package com.dadacore.lore.access;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import com.dadacore.lore.support.SampleFiles;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Who sees which document: every route of the documents, for every kind of person, on open and
 * restricted documents. A document the person cannot see answers {@code 404} and appears in no
 * list and no count; an action their role does not allow on a document they see answers
 * {@code 403}.
 *
 * <p>In the space Infrastructure: A, open, imported by the contributor; B, open, by the
 * maintainer; R1, restricted to the reader, by the maintainer; R2, restricted to nobody, by the
 * maintainer; R3, restricted to nobody, by the contributor. And a document of another organization.
 */
class WhoSeesWhichDocumentTests extends ApiTest {

    private static final String AMENAN = "amenan.souaga@dada.group";
    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String ELFRIED = "elfried.yaba@dada.group";
    private static final String CHRIST = "christ.koffi@dada.group";

    /** In the order of the expected statuses. */
    private enum Person { OWNER, ADMIN, MAINTAINER, CONTRIBUTOR, READER, OUTSIDER, ANONYMOUS }

    private record Probe(String route, Function<Person, Response> call, int[] expected) {
    }

    @Autowired
    ExtractionResults results;

    private final Map<Person, Browser> browsers = new EnumMap<>(Person.class);
    private String space;
    private String a;
    private String b;
    private String r1;
    private String r2;
    private String r3;
    private String other;
    private byte[] r2Content;
    private int counter;

    @TestFactory
    Stream<DynamicTest> everyRouteAnswersAccordingToTheRoleOfThePerson() {
        organization();
        List<Probe> probes = List.of(
                probe("GET A", p -> as(p).get(a), 200, 200, 200, 200, 200, 404, 401),
                probe("GET B", p -> as(p).get(b), 200, 200, 200, 200, 200, 404, 401),
                probe("GET R1, restricted to the reader", p -> as(p).get(r1), 200, 200, 200, 404, 200, 404, 401),
                probe("GET R2, restricted to nobody", p -> as(p).get(r2), 200, 200, 200, 404, 404, 404, 401),
                probe("GET R3, owned by the contributor", p -> as(p).get(r3), 200, 200, 200, 200, 404, 404, 401),
                probe("GET a document of another organization", p -> as(p).get(other), 404, 404, 404, 404, 404, 404, 401),
                probe("GET R1/access", p -> as(p).get(r1 + "/access"), 200, 200, 200, 404, 200, 404, 401),
                probe("POST import", p -> as(p).upload(space + "/documents", "note-" + next() + ".txt",
                        ("Note " + next()).getBytes()), 201, 201, 201, 201, 403, 404, 401),
                probe("PATCH A", p -> as(p).patch(a, body("title", "A " + next())), 200, 200, 200, 200, 403, 404, 401),
                probe("PATCH R2", p -> as(p).patch(r2, body("title", "R2 " + next())), 200, 200, 200, 404, 404, 404, 401),
                probe("POST A/move", p -> as(p).post(a + "/move", body("folderId", null)), 200, 200, 200, 200, 403, 404, 401),
                probe("PUT A/access, owned by the contributor",
                        p -> as(p).put(a + "/access", body("restricted", false)), 200, 200, 200, 200, 403, 404, 401),
                probe("PUT B/access", p -> as(p).put(b + "/access", body("restricted", false)), 200, 200, 200, 403, 403, 404, 401),
                probe("DELETE a document imported by the contributor",
                        p -> as(p).delete(fresh(Person.CONTRIBUTOR)), 204, 204, 204, 204, 403, 404, 401),
                probe("DELETE a document imported by the maintainer",
                        p -> as(p).delete(fresh(Person.MAINTAINER)), 204, 204, 204, 403, 403, 404, 401),
                probe("POST A/extraction/retry", p -> as(p).post(a + "/extraction/retry"), 409, 409, 409, 409, 403, 404, 401),
                probe("GET a document of another organization, access", p -> as(p).get(other + "/access"),
                        404, 404, 404, 404, 404, 404, 401),
                // The reader: the rendering and the file follow the same rule as the page.
                probe("GET A rendering", p -> as(p).get(a + "/versions/1/rendering"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET B file", p -> file(p, b + "/versions/1/file"), 200, 200, 200, 200, 200, 404, 401),
                probe("HEAD B file", p -> file(p, "HEAD", b + "/versions/1/file"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET R1 rendering", p -> as(p).get(r1 + "/versions/1/rendering"), 200, 200, 200, 404, 200, 404, 401),
                probe("GET R1 file", p -> file(p, r1 + "/versions/1/file"), 200, 200, 200, 404, 200, 404, 401),
                probe("GET R2 rendering", p -> as(p).get(r2 + "/versions/1/rendering"), 200, 200, 200, 404, 404, 404, 401),
                probe("GET R2 file", p -> file(p, r2 + "/versions/1/file"), 200, 200, 200, 404, 404, 404, 401),
                probe("GET R3 rendering", p -> as(p).get(r3 + "/versions/1/rendering"), 200, 200, 200, 200, 404, 404, 401),
                probe("GET R3 file", p -> file(p, r3 + "/versions/1/file"), 200, 200, 200, 200, 404, 404, 401),
                probe("GET A file, inline for a text", p -> as(p).get(a + "/versions/1/file?disposition=inline"),
                        400, 400, 400, 400, 400, 404, 401),
                probe("GET A, a version it does not have", p -> as(p).get(a + "/versions/9/file"), 404, 404, 404, 404, 404, 404, 401),
                // The document of another organization has no file on the disk: the 404 comes first.
                probe("GET a document of another organization, rendering", p -> as(p).get(other + "/versions/1/rendering"),
                        404, 404, 404, 404, 404, 404, 401),
                probe("GET a document of another organization, file", p -> file(p, other + "/versions/1/file"),
                        404, 404, 404, 404, 404, 404, 401),
                probe("PUT owner of a document imported by the contributor",
                        p -> as(p).put(fresh(Person.CONTRIBUTOR) + "/owner", body("userId", userId(ELI))),
                        200, 200, 200, 200, 403, 404, 401),
                probe("PUT owner of a document imported by the maintainer",
                        p -> as(p).put(fresh(Person.MAINTAINER) + "/owner", body("userId", userId(HERVE))),
                        200, 200, 200, 403, 403, 404, 401),
                probe("PUT owner of a document restricted to nobody",
                        p -> as(p).put(document(Person.MAINTAINER, "R-" + next() + ".txt", "restricted", "true") + "/owner",
                                body("userId", userId(ELI))),
                        200, 200, 200, 404, 404, 404, 401));

        return probes.stream().flatMap(probe -> Arrays.stream(Person.values()).map(person -> {
            int expected = probe.expected()[person.ordinal()];
            String name = probe.route() + " as " + person.name().toLowerCase() + " gives " + expected;
            return DynamicTest.dynamicTest(name, () -> {
                Response response = probe.call().apply(person);
                assertThat(response.status()).as(name + ", got " + response).isEqualTo(expected);
            });
        }));
    }

    @Test
    void listsAndCountsOnlyShowTheDocumentsThePersonCanSee() {
        organization();
        Map<Person, List<String>> expected = Map.of(
                Person.OWNER, List.of("A", "B", "R1", "R2", "R3"),
                Person.ADMIN, List.of("A", "B", "R1", "R2", "R3"),
                Person.MAINTAINER, List.of("A", "B", "R1", "R2", "R3"),
                Person.CONTRIBUTOR, List.of("A", "B", "R3"),
                Person.READER, List.of("A", "B", "R1"));
        expected.forEach((person, titles) -> {
            Response list = as(person).get(space + "/documents");
            assertThat(titles(list)).as(person.name()).containsExactlyInAnyOrderElementsOf(titles);
            assertThat(list.number("total")).as(person.name()).isEqualTo(titles.size());
            assertThat(as(person).get(space + "/documents/summary").number("total")).as(person.name()).isEqualTo(titles.size());
            assertThat(as(person).get(space).number("documentCount")).as(person.name()).isEqualTo(titles.size());
            // Asking for a document by its identifier does not reveal it either.
            List<String> all = List.of(id(a), id(b), id(r1), id(r2), id(r3));
            assertThat(titles(as(person).get(space + "/documents?ids=" + String.join(",", all)))).as(person.name())
                    .containsExactlyInAnyOrderElementsOf(titles);
        });
        assertThat(as(Person.OUTSIDER).get(space + "/documents").status()).isEqualTo(404);

        // The documents imported by the contributor that are still extracting: on their home page only.
        document(Person.CONTRIBUTOR, "C1.txt");
        document(Person.CONTRIBUTOR, "C2.txt", "restricted", "true");
        assertThat(as(Person.CONTRIBUTOR).get("/api/v1/imports/attention").json().size()).isEqualTo(2);
        assertThat(as(Person.READER).get("/api/v1/imports/attention").json().size()).isZero();
    }

    @Test
    void aDuplicateNeverPointsToADocumentThePersonCannotSee() {
        organization();

        // The contributor imports the very file of R2, which is hidden from them.
        Response imported = as(Person.CONTRIBUTOR).upload(space + "/documents", "copie.txt", r2Content);

        assertThat(imported.status()).isEqualTo(201);
        assertThat(imported.text("duplicateOf")).isNull();
        // The maintainer, who sees R2, is told.
        assertThat(as(Person.MAINTAINER).upload(space + "/documents", "autre-copie.txt", r2Content).text("duplicateOf.title"))
                .isEqualTo("R2");
    }

    @Test
    void anArchivedSpaceHidesItsDocumentsAndRefusesChanges() {
        organization();
        assertThat(as(Person.MAINTAINER).post(space + "/archive").status()).isEqualTo(200);

        assertThat(as(Person.CONTRIBUTOR).get(a).status()).isEqualTo(404);
        assertThat(as(Person.READER).get(a).status()).isEqualTo(404);
        assertThat(as(Person.MAINTAINER).get(a).status()).isEqualTo(200);
        assertThat(as(Person.MAINTAINER).patch(a, body("title", "Changé")).code()).isEqualTo("space_archived");
        assertThat(as(Person.OWNER).delete(a).code()).isEqualTo("space_archived");
        // Reading is no change: those who still see the documents read and download them.
        assertThat(as(Person.MAINTAINER).get(a + "/versions/1/rendering").status()).isEqualTo(200);
        assertThat(file(Person.MAINTAINER, a + "/versions/1/file").status()).isEqualTo(200);
        assertThat(file(Person.OWNER, a + "/versions/1/file").status()).isEqualTo(200);
        assertThat(file(Person.CONTRIBUTOR, a + "/versions/1/file").status()).isEqualTo(404);
        assertThat(as(Person.READER).get(a + "/versions/1/rendering").status()).isEqualTo(404);
        assertThat(as(Person.MAINTAINER).put(a + "/owner", body("userId", userId(ELI))).code()).isEqualTo("space_archived");
    }

    @Test
    void leavingTheSpaceRemovesTheAccessToARestrictedDocument() {
        organization();
        assertThat(as(Person.READER).get(r1).status()).isEqualTo(200);
        UUID reader = userId(ELFRIED);

        assertThat(as(Person.MAINTAINER).delete(space + "/members/" + reader).status()).isEqualTo(204);
        as(Person.MAINTAINER).post(space + "/members", body("userId", reader, "role", "READER"));

        // Back in the space, but no longer among the people chosen.
        assertThat(as(Person.READER).get(r1).status()).isEqualTo(404);
        assertThat(jdbc.sql("select count(*) from document_grant").query(Integer.class).single()).isZero();
    }

    @Test
    void aFolderHoldingOnlyHiddenDocumentsStaysAsAKnownLimit() {
        organization();
        String folder = space + "/folders/" + created(as(Person.MAINTAINER).post(space + "/folders", body("name", "Direction")));
        as(Person.MAINTAINER).post(r2 + "/move", body("folderId", folder.substring(folder.lastIndexOf('/') + 1)));

        // The contributor sees no document in it, and yet cannot delete it: it is not empty.
        assertThat(as(Person.CONTRIBUTOR).get(space + "/documents?folder=" + folder.substring(folder.lastIndexOf('/') + 1))
                .number("total")).isZero();
        assertThat(as(Person.CONTRIBUTOR).delete(folder).code()).isEqualTo("folder_not_empty");
    }

    private void organization() {
        Browser owner = setUpOrganization();
        browsers.put(Person.OWNER, owner);
        browsers.put(Person.ADMIN, join(owner, "ADMIN", "Amenan Souaga", AMENAN));
        browsers.put(Person.MAINTAINER, join(owner, "MEMBER", "Eli Brou", ELI));
        browsers.put(Person.CONTRIBUTOR, join(owner, "MEMBER", "Hervé Coulibaly", HERVE));
        browsers.put(Person.READER, join(owner, "MEMBER", "Elfried Yaba", ELFRIED));
        browsers.put(Person.OUTSIDER, join(owner, "MEMBER", "Christ Koffi", CHRIST));
        browsers.put(Person.ANONYMOUS, browser());

        space = "/api/v1/spaces/" + created(as(Person.MAINTAINER).post("/api/v1/spaces", body("name", "Infrastructure")));
        created(as(Person.MAINTAINER).post(space + "/members", body("userId", userId(HERVE), "role", "CONTRIBUTOR")));
        created(as(Person.MAINTAINER).post(space + "/members", body("userId", userId(ELFRIED), "role", "READER")));

        a = document(Person.CONTRIBUTOR, "A.txt");
        b = document(Person.MAINTAINER, "B.txt");
        r1 = document(Person.MAINTAINER, "R1.txt", "restricted", "true", "userIds", userId(ELFRIED).toString());
        r2 = document(Person.MAINTAINER, "R2.txt", "restricted", "true");
        r3 = document(Person.CONTRIBUTOR, "R3.txt", "restricted", "true");
        // Their extraction, as it would end: each has its rendering.
        for (String document : List.of(a, b, r1, r2, r3)) {
            UUID version = jdbc.sql("select current_version_id from document where id = ?::uuid").param(id(document))
                    .query(UUID.class).single();
            results.extracted(version, new Extraction(false, "fr", null, false, null,
                    List.of(new ExtractedChunk(0, "part-1", null, List.of(), "Contenu")),
                    new Rendering(List.of("<section id=\"part-1\"><pre>Contenu</pre></section>"),
                            List.of(new Rendering.Place("part-1", 1, 0, null)), false)));
        }

        // A document of another organization, where everyone here is also a maintainer.
        UUID organization = jdbc.sql("insert into organization (name, slug) values ('Autre', 'autre') returning id")
                .query(UUID.class).single();
        UUID otherSpace = jdbc.sql("""
                insert into space (organization_id, name, slug, color) values (?, 'Confidentiel', 'confidentiel', '#13705E')
                returning id
                """).param(organization).query(UUID.class).single();
        for (String email : List.of(OWNER_EMAIL, AMENAN, ELI, HERVE, ELFRIED, CHRIST)) {
            jdbc.sql("insert into membership (organization_id, user_id, role) values (?, ?, 'MEMBER')")
                    .params(organization, userId(email)).update();
            jdbc.sql("insert into space_member (space_id, user_id, organization_id, role) values (?, ?, ?, 'MAINTAINER')")
                    .params(otherSpace, userId(email), organization).update();
        }
        UUID document = jdbc.sql("""
                insert into document (space_id, title, status) values (?, 'Secret', 'DRAFT') returning id
                """).param(otherSpace).query(UUID.class).single();
        UUID version = jdbc.sql("""
                insert into document_version (document_id, number, sha256, filename, media_type, size_bytes)
                values (?, 1, repeat('a', 64), 'secret.txt', 'text/plain', 6) returning id
                """).param(document).query(UUID.class).single();
        jdbc.sql("update document set current_version_id = ? where id = ?").params(version, document).update();
        other = "/api/v1/documents/" + document;
    }

    private String document(Person person, String filename, String... fields) {
        byte[] content = ("Contenu de " + filename + " " + next()).getBytes();
        if (filename.equals("R2.txt")) {
            r2Content = content;
        }
        return "/api/v1/documents/" + created(as(person).upload(space + "/documents", filename, content, fields));
    }

    /** A new document, for a probe that deletes one. */
    private String fresh(Person importer) {
        return document(importer, "temporaire-" + next() + ".txt");
    }

    private List<String> titles(Response list) {
        assertThat(list.status()).as(list.toString()).isEqualTo(200);
        List<String> titles = new ArrayList<>();
        list.json().path("items").forEach(item -> titles.add(item.get("title").asString()));
        return titles;
    }

    /** A file is read as bytes: only its status matters here. */
    private Response file(Person person, String path) {
        return file(person, "GET", path);
    }

    private Response file(Person person, String method, String path) {
        Browser.Raw raw = as(person).raw(method, path);
        return new Response(raw.status(), null, raw.headers());
    }

    private Browser as(Person person) {
        return browsers.get(person);
    }

    private int next() {
        return ++counter;
    }

    private static String id(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static UUID created(Response response) {
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.text("id"));
    }

    private static Probe probe(String route, Function<Person, Response> call, int... expected) {
        assertThat(expected).hasSize(Person.values().length);
        return new Probe(route, call, expected);
    }
}
