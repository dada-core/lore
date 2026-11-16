package com.dadacore.lore.library;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * F6: the members of a space, and the spaces to join with an invitation.
 */
class SpaceMembersApiTests extends ApiTest {

    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";
    private static final String CHRIST = "christ.koffi@dada.group";

    @Test
    void maintainersAddMembersOfTheOrganizationWithARole() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        join(owner, "MEMBER", "Christ Koffi", CHRIST);
        String members = "/api/v1/spaces/" + eli.post("/api/v1/spaces", body("name", "Infrastructure")).text("id")
                + "/members";

        Response candidates = eli.get(members + "/candidates?q=herve");
        assertThat(candidates.json().size()).isEqualTo(1);
        assertThat(candidates.text("0.email")).isEqualTo(HERVE);
        assertThat(eli.get(members + "/candidates").json().size()).isEqualTo(3);

        Response added = eli.post(members, body("userId", userId(HERVE), "role", "CONTRIBUTOR"));
        assertThat(added.status()).as(added.toString()).isEqualTo(201);
        assertThat(added.text("role")).isEqualTo("CONTRIBUTOR");
        assertThat(added.flag("active")).isTrue();
        assertThat(eli.get(members + "/candidates?q=herve").json().size()).isZero();

        assertThat(eli.post(members, body("userId", userId(HERVE), "role", "READER")).code())
                .isEqualTo("already_space_member");
        assertThat(eli.post(members, body("userId", UUID.randomUUID(), "role", "READER")).fieldErrors())
                .containsExactly("userId:unknown_member");
        assertThat(eli.post(members, body("userId", userId(CHRIST), "role", "OWNER")).fieldErrors())
                .containsExactly("role:invalid_value");

        owner.post("/api/v1/admin/members/" + userId(CHRIST) + "/disable");
        assertThat(eli.post(members, body("userId", userId(CHRIST), "role", "READER")).fieldErrors())
                .containsExactly("userId:unknown_member");

        Response list = eli.get(members);
        assertThat(list.text("0.displayName")).isEqualTo("Eli Brou");
        assertThat(list.text("1.displayName")).isEqualTo("Hervé Coulibaly");
        assertThat(auditActions()).contains("space.member_added");
    }

    @Test
    void aSpaceAlwaysKeepsAMaintainer() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String space = "/api/v1/spaces/" + eli.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        String eliMember = space + "/members/" + userId(ELI);

        assertThat(eli.patch(eliMember, body("role", "READER")).code()).isEqualTo("last_maintainer");
        assertThat(owner.delete(eliMember).code()).isEqualTo("last_maintainer");
        assertThat(eli.delete(eliMember).code()).isEqualTo("last_maintainer");

        eli.post(space + "/members", body("userId", userId(HERVE), "role", "MAINTAINER"));
        assertThat(eli.patch(eliMember, body("role", "CONTRIBUTOR")).text("role")).isEqualTo("CONTRIBUTOR");
        assertThat(eli.delete(eliMember).status()).isEqualTo(204);
        assertThat(eli.get(space).status()).isEqualTo(404);

        String left = jdbc.sql("select details::text from audit_event where action = 'space.member_removed'")
                .query(String.class).single();
        assertThat(left).contains("\"left\": true", "Eli Brou");
        assertThat(auditActions()).contains("space.member_role_changed");
    }

    @Test
    void aDisabledMaintainerDoesNotCountAsTheOneWhoRemains() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String space = "/api/v1/spaces/" + eli.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        eli.post(space + "/members", body("userId", userId(HERVE), "role", "MAINTAINER"));
        owner.post("/api/v1/admin/members/" + userId(HERVE) + "/disable");

        assertThat(eli.delete(space + "/members/" + userId(ELI)).code()).isEqualTo("last_maintainer");
        // The disabled maintainer can still be withdrawn: an active one remains.
        assertThat(eli.patch(space + "/members/" + userId(HERVE), body("role", "READER")).text("role")).isEqualTo("READER");
    }

    @Test
    void aSpaceArchivedAfterTheInvitationIsNeitherAnnouncedNorGiven() {
        Browser owner = setUpOrganization();
        String infrastructure = owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        String support = owner.post("/api/v1/spaces", body("name", "Support")).text("id");
        Response invitation = owner.post("/api/v1/admin/invitations", body("role", "MEMBER", "spaces", List.of(
                Map.of("spaceId", infrastructure, "role", "READER"),
                Map.of("spaceId", support, "role", "READER"))));
        owner.post("/api/v1/spaces/" + support + "/archive");
        String token = token(invitation.text("url"));

        Response details = browser().post("/api/v1/invitations/inspect", body("token", token));
        assertThat(details.json().get("spaces").size()).isEqualTo(1);
        assertThat(details.text("spaces.0.name")).isEqualTo("Infrastructure");

        browser().post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Christ Koffi", "email", CHRIST, "password", PASSWORD));
        assertThat(jdbc.sql("select count(*) from space_member where user_id = ?").param(userId(CHRIST))
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void someoneRemovedFromTheOrganizationLeavesItsSpaces() {
        Browser owner = setUpOrganization();
        Browser eli = join(owner, "MEMBER", "Eli Brou", ELI);
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String members = "/api/v1/spaces/" + eli.post("/api/v1/spaces", body("name", "Infrastructure")).text("id")
                + "/members";
        eli.post(members, body("userId", userId(HERVE), "role", "CONTRIBUTOR"));
        // Hervé is also a reader in a space of another organization, which must stay.
        UUID otherOrganization = jdbc.sql("insert into organization (name, slug) values ('Autre', 'autre') returning id")
                .query(UUID.class).single();
        jdbc.sql("insert into membership (organization_id, user_id, role) values (?, ?, 'MEMBER')")
                .params(otherOrganization, userId(HERVE)).update();
        UUID otherSpace = jdbc.sql("""
                insert into space (organization_id, name, slug, color)
                values (?, 'Confidentiel', 'confidentiel', '#13705E') returning id
                """).param(otherOrganization).query(UUID.class).single();
        jdbc.sql("insert into space_member (space_id, user_id, organization_id, role) values (?, ?, ?, 'READER')")
                .params(otherSpace, userId(HERVE), otherOrganization).update();

        assertThat(owner.delete("/api/v1/admin/members/" + userId(HERVE)).status()).isEqualTo(204);

        assertThat(eli.get(members).json().size()).isEqualTo(1);
        assertThat(jdbc.sql("select space_id from space_member where user_id = ?").param(userId(HERVE))
                .query(UUID.class).list()).containsExactly(otherSpace);
    }

    @Test
    void anInvitationAddsThePersonToTheChosenSpaces() {
        Browser owner = setUpOrganization();
        String infrastructure = owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        String support = owner.post("/api/v1/spaces", body("name", "Support")).text("id");

        Response invitation = owner.post("/api/v1/admin/invitations", body("role", "MEMBER", "spaces", List.of(
                Map.of("spaceId", support, "role", "READER"),
                Map.of("spaceId", infrastructure, "role", "CONTRIBUTOR"))));
        assertThat(invitation.status()).as(invitation.toString()).isEqualTo(201);
        assertThat(invitation.text("invitation.spaces.0.name")).isEqualTo("Support");
        assertThat(owner.get("/api/v1/admin/invitations").json().get(0).get("spaces").size()).isEqualTo(2);

        String token = token(invitation.text("url"));
        Response details = browser().post("/api/v1/invitations/inspect", body("token", token));
        assertThat(details.text("spaces.0.name")).isEqualTo("Infrastructure");
        assertThat(details.text("spaces.0.role")).isEqualTo("CONTRIBUTOR");
        assertThat(details.text("spaces.1.name")).isEqualTo("Support");

        Browser christ = browser();
        Response joined = christ.post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Christ Koffi", "email", CHRIST, "password", PASSWORD));
        assertThat(joined.status()).as(joined.toString()).isEqualTo(201);

        Response spaces = christ.get("/api/v1/spaces");
        assertThat(spaces.text("0.name")).isEqualTo("Infrastructure");
        assertThat(spaces.text("0.role")).isEqualTo("CONTRIBUTOR");
        assertThat(spaces.text("1.name")).isEqualTo("Support");
        assertThat(spaces.text("1.role")).isEqualTo("READER");
        String audit = jdbc.sql("select details::text from audit_event where action = 'member.joined'")
                .query(String.class).single();
        assertThat(audit).contains("Infrastructure", "Support");
    }

    @Test
    void anInvitationOnlyOffersActiveSpacesOfTheOrganization() {
        Browser owner = setUpOrganization();
        String archived = owner.post("/api/v1/spaces", body("name", "Ancien réseau")).text("id");
        owner.post("/api/v1/spaces/" + archived + "/archive");
        String active = owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");

        assertThat(invite(owner, UUID.randomUUID().toString(), "READER").fieldErrors())
                .containsExactly("spaces:unknown_space");
        UUID otherOrganization = jdbc.sql("insert into organization (name, slug) values ('Autre', 'autre') returning id")
                .query(UUID.class).single();
        UUID otherSpace = jdbc.sql("""
                insert into space (organization_id, name, slug, color)
                values (?, 'Confidentiel', 'confidentiel', '#13705E') returning id
                """).param(otherOrganization).query(UUID.class).single();
        assertThat(invite(owner, otherSpace.toString(), "READER").fieldErrors())
                .containsExactly("spaces:unknown_space");
        assertThat(invite(owner, archived, "READER").fieldErrors()).containsExactly("spaces:space_archived");
        assertThat(invite(owner, active, "OWNER").fieldErrors()).containsExactly("spaces:invalid_value");
        assertThat(owner.get("/api/v1/admin/invitations").json().size()).isZero();
    }

    private static Response invite(Browser admin, String spaceId, String role) {
        return admin.post("/api/v1/admin/invitations",
                body("role", "MEMBER", "spaces", List.of(Map.of("spaceId", spaceId, "role", role))));
    }
}
