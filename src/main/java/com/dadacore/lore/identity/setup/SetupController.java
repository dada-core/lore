package com.dadacore.lore.identity.setup;

import com.dadacore.lore.identity.login.SessionInfo;
import com.dadacore.lore.identity.login.SessionInfos;
import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.shared.RequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/setup")
@Tag(name = "Initial setup")
class SetupController {

    private final SetupService setupService;
    private final LoginSessions sessions;
    private final SessionInfos sessionInfos;

    SetupController(SetupService setupService, LoginSessions sessions, SessionInfos sessionInfos) {
        this.setupService = setupService;
        this.sessions = sessions;
        this.sessionInfos = sessionInfos;
    }

    @GetMapping
    @Operation(summary = "Is the initial setup still to be done")
    SetupService.SetupStatus status() {
        return setupService.status();
    }

    record VerifyCodeRequest(String setupCode) {
    }

    /** {@code 400} with the field error {@code invalid_setup_code}; {@code 409 setup_done}. */
    @PostMapping("/verify-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Check the setup code printed in the server logs")
    void verifyCode(@RequestBody VerifyCodeRequest body) {
        setupService.verifyCode(body.setupCode());
    }

    /** Creates the organization and its owner, then opens the owner's session. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the organization and the first administrator account")
    SessionInfo setup(@RequestBody SetupService.SetupRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        SetupService.SetupResult result = setupService.setup(body, RequestInfo.clientIp(request));
        sessions.open(result.userId(), result.organizationId(), result.sessionTimeoutMinutes(), request, response);
        return sessionInfos.current(request);
    }
}
