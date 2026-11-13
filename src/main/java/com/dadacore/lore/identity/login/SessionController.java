package com.dadacore.lore.identity.login;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
@Tag(name = "Session")
class SessionController {

    private final SessionInfos sessionInfos;

    SessionController(SessionInfos sessionInfos) {
        this.sessionInfos = sessionInfos;
    }

    /**
     * Called by the interface when it starts. Also sets the {@code XSRF-TOKEN} cookie that every
     * request changing something must send back in the {@code X-XSRF-TOKEN} header.
     */
    @GetMapping
    @Operation(summary = "Installation state and signed-in person")
    SessionInfo session(HttpServletRequest request) {
        return sessionInfos.current(request);
    }
}
