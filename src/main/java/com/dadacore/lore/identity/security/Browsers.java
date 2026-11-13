package com.dadacore.lore.identity.security;

/**
 * Names the browser and the system of a session from its User-Agent header, to display
 * "Chrome on Windows" in the list of open sessions. No location is derived from the address.
 */
final class Browsers {

    private Browsers() {
    }

    static String browser(String agent) {
        if (agent == null) {
            return null;
        }
        if (agent.contains("Edg/")) {
            return "Edge";
        }
        if (agent.contains("OPR/") || agent.contains("Opera")) {
            return "Opera";
        }
        if (agent.contains("Firefox/")) {
            return "Firefox";
        }
        if (agent.contains("Chrome/") || agent.contains("CriOS/")) {
            return "Chrome";
        }
        if (agent.contains("Safari/")) {
            return "Safari";
        }
        if (agent.startsWith("curl/")) {
            return "curl";
        }
        return null;
    }

    static String system(String agent) {
        if (agent == null) {
            return null;
        }
        if (agent.contains("Windows")) {
            return "Windows";
        }
        if (agent.contains("Android")) {
            return "Android";
        }
        if (agent.contains("iPhone") || agent.contains("iPad")) {
            return "iOS";
        }
        if (agent.contains("Mac OS X") || agent.contains("Macintosh")) {
            return "macOS";
        }
        if (agent.contains("CrOS")) {
            return "ChromeOS";
        }
        if (agent.contains("Ubuntu")) {
            return "Ubuntu";
        }
        if (agent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
