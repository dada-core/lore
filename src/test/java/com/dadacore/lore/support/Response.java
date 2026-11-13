package com.dadacore.lore.support;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

public record Response(int status, JsonNode json, HttpHeaders headers) {

    /** The {@code code} of an error, for example {@code invalid_credentials}. */
    public String code() {
        return text("code");
    }

    public String text(String field) {
        JsonNode node = at(field);
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    public int number(String field) {
        return at(field).asInt();
    }

    public boolean flag(String field) {
        return at(field).asBoolean();
    }

    /** A value by its path, for example {@code "user.role"} or {@code "items.0.email"}. */
    public JsonNode at(String path) {
        JsonNode node = json;
        for (String part : path.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = part.matches("\\d+") ? node.path(Integer.parseInt(part)) : node.path(part);
        }
        return node;
    }

    /** The field errors of a {@code 400}, as "field:code". */
    public List<String> fieldErrors() {
        List<String> errors = new ArrayList<>();
        if (json != null && json.has("errors")) {
            json.get("errors").forEach(error ->
                    errors.add(error.get("field").asString() + ":" + error.get("code").asString()));
        }
        return errors;
    }

    public String contentType() {
        return headers.firstValue("Content-Type").orElse("");
    }

    @Override
    public String toString() {
        return status + " " + json;
    }
}
