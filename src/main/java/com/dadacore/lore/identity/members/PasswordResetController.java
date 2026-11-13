package com.dadacore.lore.identity.members;

import com.dadacore.lore.identity.login.LoginThrottle;
import com.dadacore.lore.identity.login.SessionInfo;
import com.dadacore.lore.identity.login.SessionInfos;
import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.shared.RequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page opened from a reset link. The token travels in the request body, never in an address:
 * it does not end up in server logs or browser history.
 */
@RestController
@RequestMapping("/api/v1/password-reset")
@Tag(name = "Password reset")
class PasswordResetController {

    private final PasswordResets resets;
    private final LoginSessions sessions;
    private final LoginThrottle throttle;
    private final SessionInfos sessionInfos;

    PasswordResetController(PasswordResets resets, LoginSessions sessions, LoginThrottle throttle,
            SessionInfos sessionInfos) {
        this.resets = resets;
        this.sessions = sessions;
        this.throttle = throttle;
        this.sessionInfos = sessionInfos;
    }

    record InspectRequest(String token) {
    }

    record CompleteRequest(String token, String password) {
    }

    /** {@code 404 invalid_link} when the link is unknown, expired, already used or replaced. */
    @PostMapping("/inspect")
    @Operation(summary = "Account and author of a reset link")
    PasswordResets.ResetDetails inspect(@RequestBody InspectRequest body) {
        return resets.inspect(body.token());
    }

    /**
     * Saves the new password, closes every other session of the account, lifts a sign-in block
     * and opens a session.
     */
    @PostMapping("/complete")
    @Operation(summary = "Choose a new password and sign in")
    SessionInfo complete(@RequestBody CompleteRequest body, HttpServletRequest request, HttpServletResponse response) {
        PasswordResets.CompletedReset reset = resets.complete(body.token(), body.password(), RequestInfo.clientIp(request));
        sessions.closeAll(reset.userId(), null);
        throttle.clear(reset.email());
        sessions.open(reset.userId(), reset.organizationId(), reset.sessionTimeoutMinutes(), request, response);
        return sessionInfos.current(request);
    }
}
