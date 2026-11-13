package com.dadacore.lore.identity.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the errors raised by the security filters (no session, CSRF token missing) in the same
 * format as the rest of the API.
 */
@Component
class ProblemResponses {

    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    void write(HttpServletResponse response, int status, String code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), Map.of("status", status, "code", code));
    }
}
