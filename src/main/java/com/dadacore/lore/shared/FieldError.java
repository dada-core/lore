package com.dadacore.lore.shared;

import java.util.Map;

/**
 * A field the client must correct, for example
 * {@code {"field": "password", "code": "too_short", "params": {"min": 12}}}.
 */
public record FieldError(String field, String code, Map<String, Object> params) {

    public FieldError {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
