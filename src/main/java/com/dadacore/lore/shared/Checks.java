package com.dadacore.lore.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * Collects the errors of a request, field by field, then rejects it in one go with a {@code 400}
 * response that lists every field to correct.
 */
public final class Checks {

    public static final int MAX_PASSWORD_LENGTH = 128;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final List<FieldError> errors = new ArrayList<>();

    public static Checks start() {
        return new Checks();
    }

    public Checks fail(String field, String code) {
        errors.add(new FieldError(field, code, Map.of()));
        return this;
    }

    public Checks fail(String field, String code, Map<String, Object> params) {
        errors.add(new FieldError(field, code, params));
        return this;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Checks a required text and returns it trimmed, or null when it is invalid. */
    public String text(String field, String value, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            fail(field, "required");
            return null;
        }
        if (trimmed.length() > maxLength) {
            fail(field, "too_long", Map.of("max", maxLength));
            return null;
        }
        return trimmed;
    }

    /** Checks an e-mail address and returns it normalized (trimmed, lower case). */
    public String email(String field, String value) {
        String email = text(field, value, 254);
        if (email == null) {
            return null;
        }
        if (!EMAIL.matcher(email).matches()) {
            fail(field, "invalid_email");
            return null;
        }
        return email.toLowerCase(Locale.ROOT);
    }

    /** Checks a new password against the organization's minimum length. */
    public String password(String field, String value, int minLength) {
        if (value == null || value.isEmpty()) {
            fail(field, "required");
            return null;
        }
        if (value.length() < minLength) {
            fail(field, "too_short", Map.of("min", minLength));
            return null;
        }
        if (value.length() > MAX_PASSWORD_LENGTH) {
            fail(field, "too_long", Map.of("max", MAX_PASSWORD_LENGTH));
            return null;
        }
        return value;
    }

    /** Checks a value from a fixed list, for example a role. */
    public <E extends Enum<E>> E oneOf(String field, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            fail(field, "required");
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            fail(field, "invalid_value");
            return null;
        }
    }

    public void orThrow() {
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request")
                    .with("errors", List.copyOf(errors));
        }
    }
}
