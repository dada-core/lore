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
import com.dadacore.lore.support.RouteLog;
import com.dadacore.lore.support.RouteMatrix;
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
 * Each one holds a word that is only in it, and the word "commun". Some tests add AR, archived,
 * imported by the contributor, and O, obsolete, replaced by R2.
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

    /** The word only each document holds, for the search. */
    private static final Map<String, String> WORDS = Map.of("A", "aardvark", "B", "bonobo", "R1", "chinchilla",
            "R2", "dugong", "R3", "echidna", "Secret", "fennec");

    /** What each person sees, in the space Infrastructure. */
    private static final Map<Person, List<String>> VISIBLE = Map.of(
            Person.OWNER, List.of("A", "B", "R1", "R2", "R3"),
            Person.ADMIN, List.of("A", "B", "R1", "R2", "R3"),
            Person.MAINTAINER, List.of("A", "B", "R1", "R2", "R3"),
            Person.CONTRIBUTOR, List.of("A", "B", "R3"),
            Person.READER, List.of("A", "B", "R1"),
            Person.OUTSIDER, List.of());

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
    private String ar;
    private String o;
    private int counter;

    @TestFactory
    Stream<DynamicTest> everyRouteAnswersAccordingToTheRoleOfThePerson() {
        organization();
        archivedAndObsolete();
        List<Probe> probes = List.of(
                // The library of the space, and the home page of each person (what they list, below).
                probe("GET the documents of the space", p -> as(p).get(space + "/documents"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET the summary of the space", p -> as(p).get(space + "/documents/summary"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET recent documents", p -> as(p).get("/api/v1/documents/recent"), 200, 200, 200, 200, 200, 200, 401),
                probe("GET imports to handle", p -> as(p).get("/api/v1/imports/attention"), 200, 200, 200, 200, 200, 200, 401),
                probe("GET import limits", p -> as(p).get("/api/v1/imports/limits"), 200, 200, 200, 200, 200, 200, 401),
                // Versions and statuses: an archived document is seen by the maintainers only.
                probe("GET AR, archived", p -> as(p).get(ar), 200, 200, 200, 404, 404, 404, 401),
                probe("GET AR versions", p -> as(p).get(ar + "/versions"), 200, 200, 200, 404, 404, 404, 401),
                probe("GET AR activity", p -> as(p).get(ar + "/activity"), 200, 200, 200, 404, 404, 404, 401),
                probe("GET AR rendering", p -> as(p).get(ar + "/versions/1/rendering"), 200, 200, 200, 404, 404, 404, 401),
                probe("POST AR new version", p -> as(p).upload(ar + "/versions", "AR.txt", ("AR " + next()).getBytes()),
                        409, 409, 409, 404, 404, 404, 401),
                probe("PATCH AR", p -> as(p).patch(ar, body("title", "AR " + next())), 409, 409, 409, 404, 404, 404, 401),
                probe("POST A new version", p -> as(p).upload(a + "/versions", "A.txt", ("A " + next()).getBytes()),
                        201, 201, 201, 201, 403, 404, 401),
                probe("GET A versions", p -> as(p).get(a + "/versions"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET A version 1", p -> as(p).get(a + "/versions/1"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET A activity", p -> as(p).get(a + "/activity"), 200, 200, 200, 200, 200, 404, 401),
                probe("GET R2 versions", p -> as(p).get(r2 + "/versions"), 200, 200, 200, 404, 404, 404, 401),
                probe("GET R2 activity", p -> as(p).get(r2 + "/activity"), 200, 200, 200, 404, 404, 404, 401),
                probe("PUT A status", p -> as(p).put(a + "/status", body("status", next() % 2 == 0 ? "DRAFT" : "PUBLISHED")),
                        200, 200, 200, 200, 403, 404, 401),
                probe("PUT status Archived", p -> as(p).put(fresh(Person.CONTRIBUTOR) + "/status", body("status", "ARCHIVED")),
                        200, 200, 200, 403, 403, 404, 401),
                probe("PUT status out of Archived", p -> as(p).put(archived() + "/status", body("status", "PUBLISHED")),
                        200, 200, 200, 404, 404, 404, 401),
                probe("PUT R2 status", p -> as(p).put(r2 + "/status", body("status", "PUBLISHED")), 200, 200, 200, 404, 404, 404, 401),
                probe("GET home activity", p -> as(p).get("/api/v1/activity"), 200, 200, 200, 200, 200, 200, 401),
                probe("GET the audit log", p -> as(p).get("/api/v1/admin/audit"), 200, 200, 403, 403, 403, 403, 401),
                probe("GET the export of the audit log", p -> file(p, "/api/v1/admin/audit/export?category=status"),
                        200, 200, 403, 403, 403, 403, 401),
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
                        200, 200, 200, 404, 404, 404, 401),
                // The search answers every member: what it finds is checked below.
                probe("GET search", p -> as(p).get("/api/v1/search?q=dugong"), 200, 200, 200, 200, 200, 200, 401),
                probe("GET search/titles", p -> as(p).get("/api/v1/search/titles?q=R2"), 200, 200, 200, 200, 200, 200, 401),
                probe("PATCH A, language", p -> as(p).patch(a, body("language", next() % 2 == 0 ? "en" : "fr")),
                        200, 200, 200, 200, 403, 404, 401),
                probe("PATCH R2, language", p -> as(p).patch(r2, body("language", "en")), 200, 200, 200, 404, 404, 404, 401));

        Stream<DynamicTest> answers = probes.stream().flatMap(probe -> Arrays.stream(Person.values()).map(person -> {
            int expected = probe.expected()[person.ordinal()];
            String name = probe.route() + " as " + person.name().toLowerCase() + " gives " + expected;
            return DynamicTest.dynamicTest(name, () -> {
                Response response = RouteLog.probe(WhoSeesWhichDocumentTests.class, () -> probe.call().apply(person));
                assertThat(response.status()).as(name + ", got " + response).isEqualTo(expected);
            });
        }));
        // Last: every route this matrix answers for in RouteMatrix was probed above.
        return Stream.concat(answers, Stream.of(DynamicTest.dynamicTest("every route of the matrix is probed",
                () -> RouteMatrix.assertProbed(WhoSeesWhichDocumentTests.class))));
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
    void theSearchFindsOnlyTheDocumentsThePersonCanSee() {
        organization();
        for (Person person : VISIBLE.keySet()) {
            List<String> visible = VISIBLE.get(person);
            WORDS.forEach((title, word) -> {
                boolean found = visible.contains(title);
                Response search = as(person).get("/api/v1/search?q=" + word);
                assertThat(titlesFound(search)).as(person + " searching " + word).isEqualTo(found ? List.of(title) : List.of());
                assertThat(search.number("total")).isEqualTo(found ? 1 : 0);
                assertThat(search.json().path("facets").path("spaces").size()).isEqualTo(found ? 1 : 0);
                Response titles = as(person).get("/api/v1/search/titles?q=" + title);
                List<String> named = new ArrayList<>();
                titles.json().forEach(item -> named.add(item.get("title").asString()));
                assertThat(named).as(person + " typing " + title).isEqualTo(found ? List.of(title) : List.of());
            });
            // A word in every document: the total, the excerpts and the counts of the filters hold
            // the documents the person sees, and nothing else.
            Response common = as(person).get("/api/v1/search?q=commun");
            assertThat(titlesFound(common)).as(person.name()).containsExactlyInAnyOrderElementsOf(visible);
            assertThat(common.number("total")).isEqualTo(visible.size());
            long counted = 0;
            for (var status : common.json().path("facets").path("statuses")) {
                counted += status.get("count").asLong();
            }
            assertThat(counted).isEqualTo(visible.size());
        }
        assertThat(as(Person.ANONYMOUS).get("/api/v1/search?q=commun").status()).isEqualTo(401);

        // A document hidden from the reader answers exactly as a word nobody wrote; and a space
        // asked as a filter, that the person cannot see, as a space that does not exist.
        Response hidden = as(Person.READER).get("/api/v1/search?q=dugong");
        Response nobody = as(Person.READER).get("/api/v1/search?q=wapiti");
        assertThat(hidden.json()).isEqualTo(nobody.json());
        String otherSpace = jdbc.sql("select s.id::text from space s join document d on d.space_id = s.id where d.title = 'Secret'")
                .query(String.class).single();
        Response foreign = as(Person.OWNER).get("/api/v1/search?q=commun&space=" + otherSpace);
        Response invented = as(Person.OWNER).get("/api/v1/search?q=commun&space=" + UUID.randomUUID());
        assertThat(foreign.json()).isEqualTo(invented.json());

        // An access taken away: gone at the next search.
        assertThat(titlesFound(as(Person.READER).get("/api/v1/search?q=chinchilla"))).containsExactly("R1");
        assertThat(as(Person.MAINTAINER).put(r1 + "/access", body("restricted", true, "userIds", List.of())).status())
                .isEqualTo(200);
        assertThat(titlesFound(as(Person.READER).get("/api/v1/search?q=chinchilla"))).isEmpty();
    }

    @Test
    void anArchivedDocumentIsSeenByTheMaintainersOnlyAndOnlyWhenAsked() {
        organization();
        // Opened by the contributor, who imported it, before it was archived.
        as(Person.CONTRIBUTOR).get(document(Person.CONTRIBUTOR, "vu.txt"));
        archivedAndObsolete();
        as(Person.CONTRIBUTOR).get(ar);

        for (Person person : VISIBLE.keySet()) {
            boolean maintainer = List.of(Person.OWNER, Person.ADMIN, Person.MAINTAINER).contains(person);
            if (person == Person.OUTSIDER) {
                continue;
            }
            // Never in a list, a count, the search or the palette by default.
            assertThat(titles(as(person).get(space + "/documents"))).as(person.name()).doesNotContain("AR");
            assertThat(titlesFound(as(person).get("/api/v1/search?q=gerbil"))).as(person.name()).isEmpty();
            assertThat(as(person).get("/api/v1/search/titles?q=AR").json().size()).as(person.name()).isZero();
            // The documents each one sees, the one opened before and O; AR in no count.
            assertThat(as(person).get(space).number("documentCount")).as(person.name()).isEqualTo(VISIBLE.get(person).size() + 2);
            // Asked for: the maintainers find it, the others do not, not even in a count.
            assertThat(titles(as(person).get(space + "/documents?status=ARCHIVED"))).as(person.name())
                    .isEqualTo(maintainer ? List.of("AR") : List.of());
            Response search = as(person).get("/api/v1/search?q=gerbil&status=ARCHIVED");
            assertThat(titlesFound(search)).as(person.name()).isEqualTo(maintainer ? List.of("AR") : List.of());
            boolean offered = false;
            // Its word, searched without a filter: offered in the filter of the statuses, to the maintainers only.
            for (var status : as(person).get("/api/v1/search?q=gerbil").json().path("facets").path("statuses")) {
                offered |= status.get("value").asString().equals("ARCHIVED");
            }
            assertThat(offered).as(person.name()).isEqualTo(maintainer);
            assertThat(as(person).get(space + "/documents/summary").number("archived")).as(person.name())
                    .isEqualTo(maintainer ? 1 : 0);
            // Nor in the activity of the home page, for anyone.
            assertThat(as(person).get("/api/v1/activity").json().toString()).as(person.name()).doesNotContain(id(ar));
        }
        // "Resume": the contributor no longer finds it there.
        List<String> resumed = new ArrayList<>();
        as(Person.CONTRIBUTOR).get("/api/v1/documents/recent?size=10").json().forEach(item -> resumed.add(item.path("title").asString()));
        assertThat(resumed).doesNotContain("AR").isNotEmpty();
    }

    @Test
    void anObsoleteDocumentNamesItsReplacementOnlyToWhoReadsIt() {
        organization();
        archivedAndObsolete();
        for (Person person : List.of(Person.OWNER, Person.ADMIN, Person.MAINTAINER, Person.CONTRIBUTOR, Person.READER)) {
            boolean reads = VISIBLE.get(person).contains("R2");
            Response document = as(person).get(o);
            assertThat(document.text("status")).isEqualTo("OBSOLETE");
            Response found = as(person).get("/api/v1/search?q=okapi");
            Response activity = as(person).get(o + "/activity");
            if (reads) {
                assertThat(document.text("replacedBy.title")).isEqualTo("R2");
                assertThat(found.json().at("/items/0/document/replacedBy/title").asString()).isEqualTo("R2");
                assertThat(activity.json().at("/items/0/details/replacedBy/title").asString()).isEqualTo("R2");
            } else {
                // Nothing of R2: neither its title nor its identifier, anywhere.
                assertThat(document.at("replacedBy").isNull()).as(person.name()).isTrue();
                for (Response response : List.of(document, found, activity)) {
                    assertThat(response.json().toString()).as(person.name()).doesNotContain(id(r2)).doesNotContain("\"R2\"");
                }
            }
        }
        // Writing it: a replacement the person cannot read is answered as one that does not exist.
        Response hidden = as(Person.CONTRIBUTOR).put(a + "/status", body("status", "OBSOLETE", "replacedBy", id(r2)));
        Response invented = as(Person.CONTRIBUTOR).put(a + "/status", body("status", "OBSOLETE", "replacedBy",
                UUID.randomUUID().toString()));
        assertThat(hidden.json()).isEqualTo(invented.json());
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
        assertThat(as(Person.MAINTAINER).patch(a, body("language", "en")).code()).isEqualTo("space_archived");

        // Its documents are retired: out of the results by default, even for those who still see the
        // space, who find it unchecked in the filter of the spaces and may check it.
        Response maintainer = as(Person.MAINTAINER).get("/api/v1/search?q=bonobo");
        assertThat(maintainer.number("total")).isZero();
        assertThat(maintainer.json().path("facets").path("spaces").get(0).get("archived").asBoolean()).isTrue();
        assertThat(titlesFound(as(Person.MAINTAINER).get("/api/v1/search?q=bonobo&includeArchived=true"))).containsExactly("B");
        assertThat(titlesFound(as(Person.MAINTAINER).get("/api/v1/search?q=bonobo&space=" + id(space)))).containsExactly("B");
        assertThat(as(Person.READER).get("/api/v1/search?q=bonobo&includeArchived=true").number("total")).isZero();
        assertThat(as(Person.READER).get("/api/v1/search?q=bonobo").json().path("facets").path("spaces").size()).isZero();
        assertThat(as(Person.MAINTAINER).get("/api/v1/search/titles?q=B").json().size()).isZero();
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
        // Their extraction, as it would end: each has its rendering, and its own word.
        Map<String, String> titles = Map.of(a, "A", b, "B", r1, "R1", r2, "R2", r3, "R3");
        for (String document : List.of(a, b, r1, r2, r3)) {
            UUID version = jdbc.sql("select current_version_id from document where id = ?::uuid").param(id(document))
                    .query(UUID.class).single();
            jdbc.sql("update document set title = ? where id = ?::uuid").params(titles.get(document), id(document)).update();
            results.extracted(version, new Extraction(false, "fr", null, false, null,
                    List.of(new ExtractedChunk(0, "part-1", null, List.of(),
                            "Contenu commun " + WORDS.get(titles.get(document)))),
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
        // Extracted, with the words of the others: found by nobody here.
        results.extracted(version, new Extraction(false, "fr", null, false, null,
                List.of(new ExtractedChunk(0, "part-1", null, List.of(), "Secret commun fennec")), null));
        other = "/api/v1/documents/" + document;
    }

    private String document(Person person, String filename, String... fields) {
        byte[] content = ("Contenu de " + filename + " " + next()).getBytes();
        if (filename.equals("R2.txt")) {
            r2Content = content;
        }
        return "/api/v1/documents/" + created(as(person).upload(space + "/documents", filename, content, fields));
    }

    /**
     * AR, archived by the maintainer, imported by the contributor; O, obsolete, replaced by R2. Each
     * with its own word: "gerbil", "okapi".
     */
    private void archivedAndObsolete() {
        ar = document(Person.CONTRIBUTOR, "AR.txt");
        o = document(Person.MAINTAINER, "O.txt");
        for (String document : List.of(ar, o)) {
            String title = document.equals(ar) ? "AR" : "O";
            UUID version = jdbc.sql("select current_version_id from document where id = ?::uuid").param(id(document))
                    .query(UUID.class).single();
            jdbc.sql("update document set title = ? where id = ?::uuid").params(title, id(document)).update();
            results.extracted(version, new Extraction(false, "fr", null, false, null,
                    List.of(new ExtractedChunk(0, "part-1", null, List.of(),
                            "Contenu " + (document.equals(ar) ? "gerbil" : "okapi"))),
                    new Rendering(List.of("<section id=\"part-1\"><pre>Contenu</pre></section>"),
                            List.of(new Rendering.Place("part-1", 1, 0, null)), false)));
        }
        assertThat(as(Person.MAINTAINER).put(ar + "/status", body("status", "ARCHIVED")).status()).isEqualTo(200);
        assertThat(as(Person.MAINTAINER).put(o + "/status", body("status", "OBSOLETE", "replacedBy", id(r2))).status())
                .isEqualTo(200);
    }

    /** A new document, archived by the maintainer, for a probe that brings one back. */
    private String archived() {
        String document = fresh(Person.MAINTAINER);
        as(Person.MAINTAINER).put(document + "/status", body("status", "ARCHIVED"));
        return document;
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

    private List<String> titlesFound(Response search) {
        assertThat(search.status()).as(search.toString()).isEqualTo(200);
        List<String> titles = new ArrayList<>();
        search.json().path("items").forEach(item -> titles.add(item.path("document").get("title").asString()));
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
