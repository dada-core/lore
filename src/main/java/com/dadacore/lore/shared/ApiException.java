package com.dadacore.lore.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An error returned to the API client as {@code application/problem+json}.
 *
 * <p>{@link #code()} is a stable identifier that the interface translates into a message in the
 * reader's language: the server never sends interface text.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    public ApiException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    /** Missing, or not readable by the current person: both answer the same way. */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    public static ApiException forbidden(String code) {
        return new ApiException(HttpStatus.FORBIDDEN, code);
    }

    public static ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code);
    }

    public static ApiException badRequest(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated");
    }
}
