package com.dadacore.lore.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the API the way the web interface does: cookies kept between requests, CSRF token read
 * from the {@code XSRF-TOKEN} cookie and sent back in the {@code X-XSRF-TOKEN} header.
 */
public final class Browser {

    public static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    public static final String FIREFOX_UBUNTU =
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private String userAgent = CHROME_WINDOWS;
    private String forwardedFor;
    private boolean sendCsrfToken = true;

    public Browser(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Browser userAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /** Pretends the request comes from another address, through a trusted reverse proxy. */
    public Browser from(String ip) {
        this.forwardedFor = ip;
        return this;
    }

    public Browser withoutCsrfToken() {
        this.sendCsrfToken = false;
        return this;
    }

    public Response get(String path) {
        return send("GET", path, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, body);
    }

    public Response post(String path) {
        return send("POST", path, null);
    }

    public Response put(String path, Object body) {
        return send("PUT", path, body);
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body);
    }

    public Response delete(String path) {
        return send("DELETE", path, null);
    }

    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public Response send(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        if (!method.equals("GET") && sendCsrfToken) {
            request.header("X-XSRF-TOKEN", csrfToken());
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(),
                    response.body() == null || response.body().isBlank() ? null : JSON.readTree(response.body()),
                    response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String csrfToken() {
        String token = cookie("XSRF-TOKEN");
        if (token == null) {
            get("/api/v1/session");
            token = cookie("XSRF-TOKEN");
        }
        return token;
    }

    /** Small helper to write request bodies: {@code body("email", "a@b.c", "password", "...")}. */
    public static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
