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
        RouteLog.record(method, path);
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

    /**
     * Sends a file as the interface does: a form with the file under {@code file}, and fields
     * given as {@code "name", "value"} pairs (a name may come back, for a list).
     */
    public Response upload(String path, String filename, byte[] content, String... fields) {
        RouteLog.record("POST", path);
        String boundary = "----lore-test-" + System.nanoTime();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        for (int i = 0; i < fields.length; i += 2) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fields[i] + "\"\r\n\r\n"
                    + fields[i + 1] + "\r\n").getBytes(utf8));
        }
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(utf8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(utf8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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

    /** An answer read as bytes, its headers as they came: nothing decompressed or parsed. */
    public record Raw(int status, java.net.http.HttpHeaders headers, byte[] body) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** A request with the headers given as {@code "name", "value"} pairs, the answer read as bytes. */
    public Raw raw(String method, String path, String... headers) {
        RouteLog.record(method, path);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("User-Agent", userAgent)
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Raw(response.statusCode(), response.headers(), response.body());
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
