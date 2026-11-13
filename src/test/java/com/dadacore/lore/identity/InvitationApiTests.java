package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;

/**
 * A4 and B3, organization part: join through an invitation link. The spaces to join arrive with
 * the spaces themselves.
 */
class InvitationApiTests extends ApiTest {

    private static final String ELI = "eli.brou@dada.group";
    private static final String HERVE = "herve.coulibaly@dada.group";

    @Test
    void anInvitedPersonCreatesTheirAccountAndArrivesSignedIn() {
        Browser owner = setUpOrganization();

        Response created = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("url")).startsWith("http://localhost:" + port + "/join#");
        assertThat(created.text("invitation.createdBy")).isEqualTo(OWNER_NAME);
        String token = token(created.text("url"));

        Browser eli = browser();
        Response details = eli.post("/api/v1/invitations/inspect", body("token", token));
        assertThat(details.text("organizationName")).isEqualTo("DadaGroup");
        assertThat(details.text("invitedBy")).isEqualTo(OWNER_NAME);
        assertThat(details.text("role")).isEqualTo("MEMBER");

        Response joined = eli.post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Eli Brou", "email", ELI, "password", PASSWORD));
        assertThat(joined.status()).isEqualTo(201);
        assertThat(joined.text("user.role")).isEqualTo("MEMBER");
        assertThat(joined.text("user.locale")).isEqualTo("fr");
        assertThat(eli.get("/api/v1/account").status()).isEqualTo(200);

        // Joining and setting up both sign in: Administration shows when, for the owner as for Eli.
        Response members = owner.get("/api/v1/admin/members");
        assertThat(members.text("items.0.lastLoginAt")).isNotNull();
        assertThat(members.text("items.1.lastLoginAt")).isNotNull();

        Response again = browser().post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Someone", "email", "someone@dada.group", "password", PASSWORD));
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.code()).isEqualTo("invalid_link");
        assertThat(auditActions()).contains("invitation.created", "member.joined");
    }

    @Test
    void aFormerMemberComesBackWithTheirAccountThroughAReservedInvitation() {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        UUID herve = userId(HERVE);
        Response resetLink = owner.post("/api/v1/admin/members/" + herve + "/password-reset-link");
        assertThat(owner.delete("/api/v1/admin/members/" + herve).status()).isEqualTo(204);

        // An open link is not enough: whoever holds it could take over the former account.
        Response open = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));
        Response refused = browser().post("/api/v1/invitations/accept", body(
                "token", token(open.text("url")), "displayName", "Hervé Coulibaly", "email", HERVE, "password", PASSWORD));
        assertThat(refused.fieldErrors()).containsExactly("email:former_member");

        Response reserved = owner.post("/api/v1/admin/invitations", body("role", "ADMIN", "email", HERVE));
        assertThat(reserved.status()).isEqualTo(201);
        Response back = browser().post("/api/v1/invitations/accept", body(
                "token", token(reserved.text("url")), "displayName", "Hervé Coulibaly",
                "email", HERVE, "password", "a-brand-new-password"));
        assertThat(back.status()).as(back.toString()).isEqualTo(201);
        assertThat(back.text("user.id")).isEqualTo(herve.toString());
        assertThat(back.text("user.role")).isEqualTo("ADMIN");
        assertThat(signIn(HERVE, "a-brand-new-password").get("/api/v1/account").status()).isEqualTo(200);

        // The reset link generated before the departure did not come back with the account.
        Response oldLink = browser().post("/api/v1/password-reset/inspect", body("token", token(resetLink.text("url"))));
        assertThat(oldLink.code()).isEqualTo("invalid_link");
        assertThat(jdbc.sql("select details->>'returning' from audit_event where action = 'member.joined' order by id desc limit 1")
                .query(String.class).single()).isEqualTo("true");
    }

    @Test
    void anAddressReservesTheLinkAndShowsItAsPending() {
        Browser owner = setUpOrganization();
        Response created = owner.post("/api/v1/admin/invitations", body("role", "ADMIN", "email", "Eli.Brou@dada.group"));
        String token = token(created.text("url"));

        Response pending = owner.get("/api/v1/admin/invitations");
        assertThat(pending.json().size()).isEqualTo(1);
        assertThat(pending.text("0.email")).isEqualTo(ELI);
        assertThat(pending.text("0.role")).isEqualTo("ADMIN");

        Response otherAddress = browser().post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Eli Brou", "email", "eli@dada.group", "password", PASSWORD));
        assertThat(otherAddress.fieldErrors()).containsExactly("email:invitation_email_mismatch");

        Response joined = browser().post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Eli Brou", "email", ELI, "password", PASSWORD));
        assertThat(joined.text("user.role")).isEqualTo("ADMIN");
        assertThat(owner.get("/api/v1/admin/invitations").json().size()).isZero();
    }

    @Test
    void aRevokedOrExpiredLinkNoLongerWorks() {
        Browser owner = setUpOrganization();
        Response revoked = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));
        Response expired = owner.post("/api/v1/admin/invitations", body("role", "MEMBER"));

        assertThat(owner.delete("/api/v1/admin/invitations/" + revoked.text("invitation.id")).status()).isEqualTo(204);
        jdbc.sql("update invitation set expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(expired.text("invitation.id")).update();

        assertThat(browser().post("/api/v1/invitations/inspect", body("token", token(revoked.text("url")))).code())
                .isEqualTo("invalid_link");
        assertThat(browser().post("/api/v1/invitations/inspect", body("token", token(expired.text("url")))).code())
                .isEqualTo("invalid_link");
        assertThat(owner.get("/api/v1/admin/invitations").json().size()).isZero();
    }

    @Test
    void anAddressAlreadyInUseIsReported() {
        Browser owner = setUpOrganization();

        Response alreadyMember = owner.post("/api/v1/admin/invitations", body("role", "MEMBER", "email", OWNER_EMAIL));
        assertThat(alreadyMember.fieldErrors()).containsExactly("email:already_member");

        Response ownerRole = owner.post("/api/v1/admin/invitations", body("role", "OWNER"));
        assertThat(ownerRole.fieldErrors()).containsExactly("role:invalid_value");

        String token = token(owner.post("/api/v1/admin/invitations", body("role", "MEMBER")).text("url"));
        Response taken = browser().post("/api/v1/invitations/accept", body(
                "token", token, "displayName", "Wilfried Bis", "email", OWNER_EMAIL, "password", PASSWORD));
        assertThat(taken.fieldErrors()).containsExactly("email:email_taken");
    }
}
