package com.dadacore.lore.identity;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * An administrator who sets {@code LORE_LOG_LEVEL=DEBUG}, or even {@code TRACE}, to investigate a
 * problem gets no password, link token, setup code, search or session cookie in the logs.
 *
 * <p>The root logger is set to TRACE for the requests under test only, and put back afterwards:
 * the loggers that {@code application.yml} sets keep their level, as they would with the variable.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretsInLogsTests extends ApiTest {

    private static final String HERVE = "herve.coulibaly@dada.group";

    @Autowired
    LoggingSystem logging;

    @Test
    void passwordsAndTokensStayOutOfTheTraceLogs(CapturedOutput output) {
        Browser owner = setUpOrganization();
        join(owner, "MEMBER", "Hervé Coulibaly", HERVE);
        String token = token(owner.post("/api/v1/admin/members/" + userId(HERVE) + "/password-reset-link").text("url"));
        String witnessPassword = "witness-password-7Qm4";
        String witnessNewPassword = "witness-new-password-9Zt2";
        String witnessCode = "WITNESS-SETUP-CODE-3Kp8";

        LogLevel before = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel();
        // TRACE writes all that DEBUG writes, and more.
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        Response login;
        Response reset;
        try {
            login = browser().post("/api/v1/auth/login", body("email", OWNER_EMAIL, "password", witnessPassword));
            reset = browser().post("/api/v1/password-reset/complete", body("token", token, "password", witnessNewPassword));
            browser().post("/api/v1/setup/verify-code", body("setupCode", witnessCode));
        } finally {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, before);
        }

        assertThat(login.status()).isEqualTo(401);
        assertThat(reset.status()).as(reset.toString()).isEqualTo(200);
        assertThat(output.getAll()).as("the requests were logged at the TRACE level").contains("TRACE");
        assertThat(output.getAll()).doesNotContain(witnessPassword, witnessNewPassword, token, witnessCode);
    }

    @Test
    void searchesAndSessionsStayOutOfTheTraceLogs(CapturedOutput output) {
        Browser owner = setUpOrganization();
        String witnessSearch = "witnesssearch7Hq3";
        String session = owner.cookie("LORE_SESSION");

        LogLevel before = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel();
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        try {
            owner.get("/api/v1/search?q=" + witnessSearch);
            owner.get("/api/v1/search/titles?q=" + witnessSearch);
        } finally {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, before);
        }

        assertThat(output.getAll()).as("the requests were logged at the TRACE level").contains("TRACE");
        assertThat(output.getAll()).doesNotContain(witnessSearch);
        // The cookie holds the session identifier in base64: neither form is written.
        assertThat(session).isNotBlank();
        String sessionId = new String(java.util.Base64.getDecoder().decode(session), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(output.getAll()).doesNotContain(session, sessionId);
    }
}
