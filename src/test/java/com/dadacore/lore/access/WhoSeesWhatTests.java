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

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import com.dadacore.lore.support.RouteLog;
import com.dadacore.lore.support.RouteMatrix;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Who sees what: every route of the spaces, of their members and of their folders, for every kind
 * of person. A space the person cannot see answers {@code 404}, whatever the action, so that its
 * existence is not revealed; an action their role does not allow on a space they see answers
 * {@code 403}.
 */
class WhoSeesWhatTests extends ApiTest {

    private static final String AMENAN = "amenan.souaga@dada.group";
    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String ELFRIED = "elfried.yaba@dada.group";
    private static final String CHRIST = "christ.koffi@dada.group";

    /**
     * The people of the matrix, in the order of the expected statuses. The space is created by the
     * maintainer; the owner and the administrator see it without being members; the outsider is a
     * member of the organization without a role in the space.
     */
    private enum Person { OWNER, ADMIN, MAINTAINER, CONTRIBUTOR, READER, OUTSIDER, ANONYMOUS }

    private record Probe(String route, Function<Person, Response> call, int[] expected) {
    }

    private final Map<Person, Browser> browsers = new EnumMap<>(Person.class);
    private String infrastructure;
    private String otherOrganizationSpace;
    private UUID amenanId;
    private UUID elfriedId;
    private UUID christId;
    private int counter;

    @TestFactory
    Stream<DynamicTest> everyRouteAnswersAccordingToTheRoleOfThePerson() {
        organization();
        String folders = infrastructure + "/folders";
        String members = infrastructure + "/members";
        List<Probe> probes = List.of(
                // The spaces: each person lists those they see (what they see, below).
                probe("GET /spaces", p -> as(p).get("/api/v1/spaces"), 200, 200, 200, 200, 200, 200, 401),
                // The space
                probe("GET /spaces/{id}", p -> as(p).get(infrastructure),
                        200, 200, 200, 200, 200, 404, 401),
                probe("GET /spaces/by-slug/{slug}", p -> as(p).get("/api/v1/spaces/by-slug/infrastructure"),
                        200, 200, 200, 200, 200, 404, 401),
                probe("PATCH /spaces/{id}", p -> as(p).patch(infrastructure, body()),
                        200, 200, 200, 403, 403, 404, 401),
                probe("POST /spaces/{id}/archive", p -> as(p).post(path(newSpace()) + "/archive"),
                        200, 200, 200, 403, 403, 404, 401),
                probe("GET /spaces/{id} once archived", p -> as(p).get(path(archivedSpace())),
                        200, 200, 200, 404, 404, 404, 401),
                probe("POST /spaces/{id}/unarchive", p -> as(p).post(path(archivedSpace()) + "/unarchive"),
                        200, 200, 200, 404, 404, 404, 401),
                probe("DELETE /spaces/{id}", p -> as(p).delete(path(newSpace())),
                        204, 204, 204, 403, 403, 404, 401),
                // Its members
                probe("GET /spaces/{id}/members", p -> as(p).get(members),
                        200, 200, 200, 200, 200, 404, 401),
                probe("GET /spaces/{id}/members/candidates", p -> as(p).get(members + "/candidates"),
                        200, 200, 200, 403, 403, 404, 401),
                probe("POST /spaces/{id}/members", p -> {
                    as(Person.OWNER).delete(members + "/" + amenanId);
                    return as(p).post(members, body("userId", amenanId, "role", "READER"));
                }, 201, 201, 201, 403, 403, 404, 401),
                probe("PATCH /spaces/{id}/members/{userId}",
                        p -> as(p).patch(members + "/" + elfriedId, body("role", "READER")),
                        200, 200, 200, 403, 403, 404, 401),
                // The reader can leave the space; they are added back before each call.
                probe("DELETE /spaces/{id}/members/{userId}", p -> {
                    as(Person.OWNER).post(members, body("userId", elfriedId, "role", "READER"));
                    return as(p).delete(members + "/" + elfriedId);
                }, 204, 204, 204, 403, 204, 404, 401),
                // Its folders
                probe("GET /spaces/{id}/folders", p -> as(p).get(folders),
                        200, 200, 200, 200, 200, 404, 401),
                probe("POST /spaces/{id}/folders", p -> as(p).post(folders, body("name", "Dossier " + next())),
                        201, 201, 201, 201, 403, 404, 401),
                probe("PATCH /spaces/{id}/folders/{folderId}",
                        p -> as(p).patch(folders + "/" + newFolder(), body("name", "Renommé " + next())),
                        200, 200, 200, 200, 403, 404, 401),
                probe("DELETE /spaces/{id}/folders/{folderId}", p -> as(p).delete(folders + "/" + newFolder()),
                        204, 204, 204, 204, 403, 404, 401),
                // A space of another organization, even with a membership recorded there
                probe("GET /spaces/{other}", p -> as(p).get(otherOrganizationSpace),
                        404, 404, 404, 404, 404, 404, 401),
                probe("GET /spaces/by-slug/{other}", p -> as(p).get("/api/v1/spaces/by-slug/confidentiel"),
                        404, 404, 404, 404, 404, 404, 401),
                probe("PATCH /spaces/{other}", p -> as(p).patch(otherOrganizationSpace, body("name", "Pris")),
                        404, 404, 404, 404, 404, 404, 401),
                probe("POST /spaces/{other}/archive", p -> as(p).post(otherOrganizationSpace + "/archive"),
                        404, 404, 404, 404, 404, 404, 401),
                probe("DELETE /spaces/{other}", p -> as(p).delete(otherOrganizationSpace),
                        404, 404, 404, 404, 404, 404, 401),
                probe("GET /spaces/{other}/members", p -> as(p).get(otherOrganizationSpace + "/members"),
                        404, 404, 404, 404, 404, 404, 401),
                probe("POST /spaces/{other}/members", p -> as(p).post(otherOrganizationSpace + "/members",
                        body("userId", christId, "role", "MAINTAINER")),
                        404, 404, 404, 404, 404, 404, 401),
                probe("GET /spaces/{other}/folders", p -> as(p).get(otherOrganizationSpace + "/folders"),
                        404, 404, 404, 404, 404, 404, 401),
                probe("POST /spaces/{other}/folders", p -> as(p).post(otherOrganizationSpace + "/folders",
                        body("name", "Intrus")),
                        404, 404, 404, 404, 404, 404, 401));

        Stream<DynamicTest> answers = probes.stream().flatMap(probe -> Arrays.stream(Person.values()).map(person -> {
            int expected = probe.expected()[person.ordinal()];
            String name = probe.route() + " as " + person.name().toLowerCase() + " gives " + expected;
            return DynamicTest.dynamicTest(name, () -> {
                Response response = RouteLog.probe(WhoSeesWhatTests.class, () -> probe.call().apply(person));
                assertThat(response.status()).as(name + ", got " + response).isEqualTo(expected);
            });
        }));
        // Last: every route this matrix answers for in RouteMatrix was probed above.
        return Stream.concat(answers, Stream.of(DynamicTest.dynamicTest("every route of the matrix is probed",
                () -> RouteMatrix.assertProbed(WhoSeesWhatTests.class))));
    }

    @Test
    void listsOnlyShowTheSpacesThePersonCanSee() {
        organization();
        UUID archived = space("Ancien réseau");
        assertThat(as(Person.MAINTAINER).post(path(archived) + "/archive").status()).isEqualTo(200);

        for (Person person : List.of(Person.OWNER, Person.ADMIN, Person.MAINTAINER)) {
            assertThat(names(person, false)).as(person.name()).containsExactly("Infrastructure");
            assertThat(names(person, true)).as(person.name()).containsExactly("Ancien réseau");
        }
        for (Person person : List.of(Person.CONTRIBUTOR, Person.READER)) {
            assertThat(names(person, false)).as(person.name()).containsExactly("Infrastructure");
            assertThat(names(person, true)).as(person.name()).isEmpty();
        }
        assertThat(names(Person.OUTSIDER, false)).isEmpty();
        assertThat(names(Person.OUTSIDER, true)).isEmpty();

        Response forOwner = as(Person.OWNER).get("/api/v1/spaces");
        assertThat(forOwner.text("0.role")).isEqualTo("MAINTAINER");
        assertThat(forOwner.number("0.memberCount")).isEqualTo(3);
        assertThat(as(Person.CONTRIBUTOR).get("/api/v1/spaces").text("0.role")).isEqualTo("CONTRIBUTOR");
        assertThat(as(Person.READER).get("/api/v1/spaces").text("0.role")).isEqualTo("READER");
    }

    /**
     * The organization of the matrix: a space "Infrastructure" created by the maintainer, with a
     * contributor, a reader and a folder; and a space of another organization, where everyone here
     * is also a member and a maintainer, which must change nothing.
     */
    private void organization() {
        Browser owner = setUpOrganization();
        browsers.put(Person.OWNER, owner);
        browsers.put(Person.ADMIN, join(owner, "ADMIN", "Amenan Souaga", AMENAN));
        browsers.put(Person.MAINTAINER, join(owner, "MEMBER", "Eli Brou", ELI));
        browsers.put(Person.CONTRIBUTOR, join(owner, "MEMBER", "Hervé Coulibaly", HERVE));
        browsers.put(Person.READER, join(owner, "MEMBER", "Elfried Yaba", ELFRIED));
        browsers.put(Person.OUTSIDER, join(owner, "MEMBER", "Christ Koffi", CHRIST));
        browsers.put(Person.ANONYMOUS, browser());
        amenanId = userId(AMENAN);
        elfriedId = userId(ELFRIED);
        christId = userId(CHRIST);

        infrastructure = path(space("Infrastructure"));
        created(owner.post(infrastructure + "/folders", body("name", "Procédures")));

        UUID otherOrganization = jdbc.sql("insert into organization (name, slug) values ('Autre', 'autre') returning id")
                .query(UUID.class).single();
        UUID otherSpace = jdbc.sql("""
                insert into space (organization_id, name, slug, color)
                values (?, 'Confidentiel', 'confidentiel', '#13705E') returning id
                """).param(otherOrganization).query(UUID.class).single();
        for (String email : List.of(OWNER_EMAIL, AMENAN, ELI, HERVE, ELFRIED, CHRIST)) {
            jdbc.sql("insert into membership (organization_id, user_id, role) values (?, ?, 'MEMBER')")
                    .params(otherOrganization, userId(email)).update();
            jdbc.sql("insert into space_member (space_id, user_id, organization_id, role) values (?, ?, ?, 'MAINTAINER')")
                    .params(otherSpace, userId(email), otherOrganization).update();
        }
        otherOrganizationSpace = path(otherSpace);
    }

    /** A space created by the maintainer, with the contributor and the reader. */
    private UUID space(String name) {
        UUID id = created(as(Person.MAINTAINER).post("/api/v1/spaces", body("name", name)));
        created(as(Person.MAINTAINER).post(path(id) + "/members", body("userId", userId(HERVE), "role", "CONTRIBUTOR")));
        created(as(Person.MAINTAINER).post(path(id) + "/members", body("userId", elfriedId, "role", "READER")));
        return id;
    }

    private UUID newSpace() {
        return space("Éphémère " + next());
    }

    private UUID archivedSpace() {
        UUID id = newSpace();
        assertThat(as(Person.MAINTAINER).post(path(id) + "/archive").status()).isEqualTo(200);
        return id;
    }

    private UUID newFolder() {
        return created(as(Person.OWNER).post(infrastructure + "/folders", body("name", "Temporaire " + next())));
    }

    private List<String> names(Person person, boolean archived) {
        Response response = as(person).get("/api/v1/spaces?archived=" + archived);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        response.json().forEach(space -> names.add(space.get("name").asString()));
        return names;
    }

    private Browser as(Person person) {
        return browsers.get(person);
    }

    private int next() {
        return ++counter;
    }

    private static String path(UUID spaceId) {
        return "/api/v1/spaces/" + spaceId;
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
