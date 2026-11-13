package com.dadacore.lore.identity.login;

import com.dadacore.lore.identity.security.LoginSessions;
import com.dadacore.lore.shared.ApiException;
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

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Session")
class AuthController {

    private final LoginService loginService;
    private final LoginSessions sessions;
    private final SessionInfos sessionInfos;

    AuthController(LoginService loginService, LoginSessions sessions, SessionInfos sessionInfos) {
        this.loginService = loginService;
        this.sessions = sessions;
        this.sessionInfos = sessionInfos;
    }

    record LoginRequest(String email, String password) {
    }

    /**
     * Errors: {@code 401 invalid_credentials} with {@code remainingAttempts} and
     * {@code lockoutMinutes}; {@code 429 login_locked} with {@code retryAfterMinutes};
     * {@code 403 account_disabled}, only when the password is right.
     */
    @PostMapping("/login")
    @Operation(summary = "Sign in with an e-mail address and a password")
    SessionInfo login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        LoginService.LoginResult result = loginService.attempt(body.email(), body.password(), RequestInfo.clientIp(request));
        return switch (result) {
            case LoginService.LoginResult.Success success -> {
                sessions.open(success.userId(), success.organizationId(), success.sessionTimeoutMinutes(),
                        request, response);
                yield sessionInfos.current(request);
            }
            case LoginService.LoginResult.Invalid invalid -> throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials")
                    .with("remainingAttempts", invalid.remainingAttempts())
                    .with("lockoutMinutes", invalid.lockoutMinutes());
            case LoginService.LoginResult.Locked locked -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "login_locked")
                    .with("retryAfterMinutes", locked.retryAfterMinutes());
            case LoginService.LoginResult.Disabled disabled -> throw ApiException.forbidden("account_disabled");
        };
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out: the session is deleted on the server")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.close(request, response);
    }
}
