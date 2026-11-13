package com.dadacore.lore.support;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base of the API tests: the whole application on a random port, a real PostgreSQL, and an empty
 * database before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class ApiTest {

    protected static final String SETUP_CODE = "test-setup-code";
    protected static final String OWNER_EMAIL = "wilfried.kouakou@dada.group";
    protected static final String OWNER_NAME = "Wilfried Kouakou";
    protected static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.sql("""
                truncate table audit_event, login_throttle, password_reset_token, invitation, membership,
                    user_account, organization_settings, organization, spring_session_attributes, spring_session
                """).update();
    }

    protected Browser browser() {
        return new Browser("http://localhost:" + port);
    }

    /** Runs the initial setup and returns the owner, signed in. */
    protected Browser setUpOrganization() {
        Browser owner = browser();
        Response response = owner.post("/api/v1/setup", body(
                "setupCode", SETUP_CODE,
                "organizationName", "DadaGroup",
                "locale", "fr",
                "timeZone", "GMT",
                "displayName", OWNER_NAME,
                "email", OWNER_EMAIL,
                "password", PASSWORD));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return owner;
    }

    protected Browser signIn(String email, String password) {
        Browser browser = browser();
        Response response = browser.post("/api/v1/auth/login", body("email", email, "password", password));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return browser;
    }

    /** An administrator invites someone, who accepts with a new browser, signed in. */
    protected Browser join(Browser admin, String role, String name, String email) {
        Response invitation = admin.post("/api/v1/admin/invitations", body("role", role));
        assertThat(invitation.status()).as(invitation.toString()).isEqualTo(201);
        Browser member = browser();
        Response joined = member.post("/api/v1/invitations/accept", body(
                "token", token(invitation.text("url")),
                "displayName", name,
                "email", email,
                "password", PASSWORD));
        assertThat(joined.status()).as(joined.toString()).isEqualTo(201);
        return member;
    }

    protected UUID userId(String email) {
        return jdbc.sql("select id from user_account where email = ?").param(email).query(UUID.class).single();
    }

    /** The token at the end of a link: {@code https://.../join#token}. */
    protected static String token(String url) {
        return url.substring(url.indexOf('#') + 1);
    }

    protected List<String> auditActions() {
        return jdbc.sql("select action from audit_event order by id").query(String.class).list();
    }

    protected int sessionCount(String email) {
        return jdbc.sql("select count(*) from spring_session where principal_name = ?")
                .param(userId(email).toString())
                .query(Integer.class)
                .single();
    }
}
