package com.dadacore.lore.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dadacore.lore.LoreProperties;
import com.dadacore.lore.shared.RequestInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Opens and closes sessions. Sessions live in PostgreSQL, indexed by account: closing them all,
 * after a password reset or a deactivation, takes effect on the next request of each device.
 */
@Component
public class LoginSessions {

    static final String USER_AGENT = "lore.userAgent";
    static final String IP = "lore.ip";

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final CsrfTokenRepository csrfTokens;
    private final LoreProperties properties;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public LoginSessions(FindByIndexNameSessionRepository<? extends Session> sessions,
            CsrfTokenRepository csrfTokens, LoreProperties properties) {
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.properties = properties;
    }

    /**
     * Opens a session under a new identifier (against session fixation) and renews the CSRF token.
     *
     * @param timeoutMinutes inactivity allowed by the organization, capped by
     *                       {@code LORE_SESSION_TIMEOUT}
     */
    public void open(UUID userId, UUID organizationId, int timeoutMinutes,
            HttpServletRequest request, HttpServletResponse response) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        long ceiling = Math.max(1, properties.sessionTimeout().toMinutes());
        session.setMaxInactiveInterval((int) Math.min(timeoutMinutes, ceiling) * 60);
        session.setAttribute(USER_AGENT, RequestInfo.userAgent(request));
        session.setAttribute(IP, RequestInfo.clientIp(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LorePrincipal(userId, organizationId), null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        renewCsrfToken(request, response);
    }

    /** Signs out the current session. */
    public void close(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        renewCsrfToken(request, response);
    }

    /**
     * Closes every session of an account, except the one given (may be null).
     *
     * @return the number of sessions closed
     */
    public int closeAll(UUID userId, String exceptSessionId) {
        int closed = 0;
        for (String id : sessions.findByPrincipalName(userId.toString()).keySet()) {
            if (!id.equals(exceptSessionId)) {
                sessions.deleteById(id);
                closed++;
            }
        }
        return closed;
    }

    public List<OpenSession> list(UUID userId, String currentSessionId) {
        List<OpenSession> result = new ArrayList<>();
        for (Map.Entry<String, ? extends Session> entry : sessions.findByPrincipalName(userId.toString()).entrySet()) {
            Session session = entry.getValue();
            String agent = session.getAttribute(USER_AGENT);
            result.add(new OpenSession(
                    handle(entry.getKey()),
                    Browsers.browser(agent),
                    Browsers.system(agent),
                    session.getAttribute(IP),
                    session.getCreationTime(),
                    session.getLastAccessedTime(),
                    entry.getKey().equals(currentSessionId)));
        }
        result.sort(Comparator.comparing(OpenSession::current).reversed()
                .thenComparing(OpenSession::lastActiveAt, Comparator.reverseOrder()));
        return result;
    }

    /**
     * Closes one session of the account, designated by its handle.
     *
     * @return false when no session of this account has this handle
     */
    public boolean closeOne(UUID userId, String handle) {
        for (String id : sessions.findByPrincipalName(userId.toString()).keySet()) {
            if (handle(id).equals(handle)) {
                sessions.deleteById(id);
                return true;
            }
        }
        return false;
    }

    private void renewCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokens.saveToken(null, request, response);
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    }

    static String handle(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
