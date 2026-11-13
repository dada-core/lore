package com.dadacore.lore.identity.members;

import com.dadacore.lore.identity.login.SessionInfo;
import com.dadacore.lore.identity.login.SessionInfos;
import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.shared.RequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page opened from an invitation link. The token travels in the request body, never in an
 * address.
 */
@RestController
@RequestMapping("/api/v1/invitations")
@Tag(name = "Invitations")
class InvitationController {

    private final Invitations invitations;
    private final LoginSessions sessions;
    private final SessionInfos sessionInfos;

    InvitationController(Invitations invitations, LoginSessions sessions, SessionInfos sessionInfos) {
        this.invitations = invitations;
        this.sessions = sessions;
        this.sessionInfos = sessionInfos;
    }

    record InspectRequest(String token) {
    }

    /** {@code 404 invalid_link} when the link is unknown, expired, used or revoked. */
    @PostMapping("/inspect")
    @Operation(summary = "Organization, author and role of an invitation link")
    Invitations.InvitationDetails inspect(@RequestBody InspectRequest body) {
        return invitations.inspect(body.token());
    }

    /**
     * Field errors {@code email_taken} and {@code invitation_email_mismatch};
     * {@code 404 invalid_link}.
     */
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account from an invitation link and sign in")
    SessionInfo accept(@RequestBody Invitations.JoinRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Invitations.Joined joined = invitations.accept(body, RequestInfo.clientIp(request));
        sessions.open(joined.userId(), joined.organizationId(), joined.sessionTimeoutMinutes(), request, response);
        return sessionInfos.current(request);
    }
}
