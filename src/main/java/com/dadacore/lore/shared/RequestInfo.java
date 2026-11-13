package com.dadacore.lore.shared;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Information about the HTTP request, kept in the audit log and in session details.
 */
public final class RequestInfo {

    private RequestInfo() {
    }

    /**
     * The client's address. Behind a reverse proxy on the internal network, the server trusts the
     * {@code X-Forwarded-For} header ({@code server.forward-headers-strategy=native}).
     */
    public static String clientIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        return agent.length() > 400 ? agent.substring(0, 400) : agent;
    }
}
