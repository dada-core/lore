package com.dadacore.lore.shared;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Information about the HTTP request, kept in the audit log and in session details.
 */
public final class RequestInfo {

    /** The client address given when the one received is not an IP address. */
    public static final String INVALID_IP = "invalid";

    /** The longest IPv6 address written out: {@code ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255}. */
    private static final int MAX_IP_LENGTH = 45;

    private RequestInfo() {
    }

    /**
     * The client's address. Behind a reverse proxy listed in {@code LORE_TRUSTED_PROXIES}, the
     * server takes it from the {@code X-Forwarded-For} header
     * ({@code server.forward-headers-strategy=native}), which such a proxy fills.
     *
     * <p>Only an IPv4 or IPv6 address is kept, checked without any name lookup; anything else becomes
     * {@link #INVALID_IP}. A forwarded header holding any text, of any length, therefore never
     * reaches the sign-in counters or the audit log.
     */
    public static String clientIp(HttpServletRequest request) {
        return request == null ? null : ipAddress(request.getRemoteAddr());
    }

    /**
     * The address, IPv6 in lower case, when it is one, otherwise {@link #INVALID_IP}. Some proxies
     * add the port of the client: {@code 203.0.113.7:5678}, {@code [2001:db8::1]:5678}; the address
     * alone is kept.
     */
    static String ipAddress(String address) {
        if (address == null || address.isEmpty() || address.length() > MAX_IP_LENGTH + 8) {
            return INVALID_IP;
        }
        String ip = address;
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end < 0 || !isPort(ip.substring(end + 1), true)) {
                return INVALID_IP;
            }
            ip = ip.substring(1, end);
        } else {
            int colon = ip.indexOf(':');
            if (colon > 0 && colon == ip.lastIndexOf(':') && isIpv4(ip.substring(0, colon))
                    && isPort(ip.substring(colon), false)) {
                ip = ip.substring(0, colon);
            }
        }
        // An IPv6 address of the local network may carry the interface it came through: fe80::1%eth0.
        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }
        if (ip.isEmpty() || ip.length() > MAX_IP_LENGTH) {
            return INVALID_IP;
        }
        if (isIpv4(ip)) {
            return ip;
        }
        return isIpv6(ip) ? ip.toLowerCase(Locale.ROOT) : INVALID_IP;
    }

    /** {@code :} and a port number, or nothing at all when that is allowed. */
    private static boolean isPort(String text, boolean mayBeEmpty) {
        if (text.isEmpty()) {
            return mayBeEmpty;
        }
        if (text.charAt(0) != ':' || text.length() < 2 || text.length() > 6) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!isDigit(text.charAt(i))) {
                return false;
            }
        }
        return Integer.parseInt(text.substring(1)) <= 65535;
    }

    /** Four numbers from 0 to 255, separated by dots. */
    private static boolean isIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Eight groups of one to four hexadecimal digits separated by colons, fewer when {@code ::}
     * stands for the missing ones (once at most), the last two possibly written as an IPv4 address.
     */
    private static boolean isIpv6(String ip) {
        // An IPv4 part only ends the address: nothing after it, not even the gap.
        int dot = ip.indexOf('.');
        if (dot >= 0 && dot < ip.lastIndexOf(':')) {
            return false;
        }
        int gap = ip.indexOf("::");
        if (gap >= 0 && ip.indexOf("::", gap + 1) >= 0) {
            return false;
        }
        List<String> groups = new ArrayList<>();
        for (String side : gap >= 0 ? new String[] {ip.substring(0, gap), ip.substring(gap + 2)} : new String[] {ip}) {
            if (!side.isEmpty()) {
                groups.addAll(Arrays.asList(side.split(":", -1)));
            }
        }
        int count = 0;
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            if (i == groups.size() - 1 && group.indexOf('.') >= 0) {
                if (!isIpv4(group)) {
                    return false;
                }
                count += 2;
                continue;
            }
            if (group.isEmpty() || group.length() > 4) {
                return false;
            }
            for (int j = 0; j < group.length(); j++) {
                char c = group.charAt(j);
                if (!isDigit(c) && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) {
                    return false;
                }
            }
            count++;
        }
        return gap >= 0 ? count <= 7 : count == 8;
    }

    /** A digit from 0 to 9 only: not the digits of other scripts that Java also takes for digits. */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
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
