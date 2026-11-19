package com.dadacore.lore.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.dadacore.lore.shared.RequestInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The reverse proxies of {@code LORE_TRUSTED_PROXIES}, set right and never left out in silence.
 *
 * <p>A list of addresses without any "/", or a host name, would be read by Tomcat as a regular
 * expression, never as a list of addresses: Lore refuses to start with it.
 *
 * <p>A request that carries {@code X-Forwarded-For} from a private address that is not declared
 * most likely comes through a reverse proxy left out, after an upgrade for example: every request
 * then has the address of the proxy, in the audit log, in the sessions and for the sign-in lockout.
 * The logs say so, once per address. A request that came through a declared proxy is left aside:
 * the rest of its header was written by the person's own machine, or a proxy of their network.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReverseProxyCheck extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ReverseProxyCheck.class);
    static final String PROPERTY = "server.tomcat.remoteip.internal-proxies";
    /** Set by Tomcat on a request that came through a declared proxy. */
    static final String FORWARDED = "org.apache.tomcat.request.forwarded";
    private static final int MAX_WARNINGS = 20;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    ReverseProxyCheck(Environment environment) {
        check(environment.getProperty(PROPERTY));
    }

    /**
     * Refuses a list of addresses written without their "/", or of host names: Tomcat would read it as a pattern,
     * compared with IP addresses only. A real pattern, such as {@code 10\.0\.0\.\d+}, stays possible.
     */
    static void check(String trustedProxies) {
        if (trustedProxies != null && !trustedProxies.isBlank() && trustedProxies.indexOf('/') < 0
                && trustedProxies.matches("[0-9A-Za-z._:,\\s-]+")) {
            throw new IllegalStateException("LORE_TRUSTED_PROXIES must give IP address ranges, each with its \"/\","
                    + " for example 10.0.0.5/32, 10.0.0.6/32, and no host name: without any \"/\", Tomcat reads the"
                    + " value as a regular expression, never as a list of addresses.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("X-Forwarded-For") != null && request.getAttribute(FORWARDED) == null) {
            warnOnce(RequestInfo.clientIp(request));
        }
        chain.doFilter(request, response);
    }

    void warnOnce(String address) {
        if (warned.size() < MAX_WARNINGS && isPrivate(address) && warned.add(address)) {
            log.warn("Requests from {} carry an X-Forwarded-For header, but {} is not declared in LORE_TRUSTED_PROXIES:"
                    + " Lore gives {} to each of them, in the audit log and for the sign-in lockout. If it is your"
                    + " reverse proxy, declare it: see \"HTTPS and reverse proxy\" in the documentation.",
                    address, address, address);
        }
    }

    /** Loopback, private, link-local or unique local: where a reverse proxy usually is. */
    static boolean isPrivate(String address) {
        if (address == null || RequestInfo.INVALID_IP.equals(address)) {
            return false;
        }
        try {
            // An address already checked as an IP literal by RequestInfo: no name is looked up.
            InetAddress ip = InetAddress.getByName(address);
            byte[] bytes = ip.getAddress();
            boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || uniqueLocal;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
