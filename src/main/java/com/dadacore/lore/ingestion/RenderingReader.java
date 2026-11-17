package com.dadacore.lore.ingestion;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a rendering from the result of an extraction process as it streams, and stops as soon as
 * it holds more parts or places than a rendering ever has: a process that a file took over could
 * write millions of empty parts in its result, built to fill the memory of the application before
 * any later check.
 */
final class RenderingReader extends ValueDeserializer<Rendering> {

    @Override
    public Rendering deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return context.reportInputMismatch(Rendering.class, "A rendering is an object");
        }
        List<String> parts = new ArrayList<>();
        List<Rendering.Place> places = new ArrayList<>();
        boolean truncated = false;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            switch (name) {
                case "parts" -> {
                    expectArray(parser, context, value);
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parts.size() >= Rendering.MAX_PARTS) {
                            return context.reportInputMismatch(Rendering.class, "Too many parts");
                        }
                        if (parser.currentToken() != JsonToken.VALUE_STRING) {
                            return context.reportInputMismatch(Rendering.class, "A part is a text");
                        }
                        parts.add(parser.getValueAsString());
                    }
                }
                case "places" -> {
                    expectArray(parser, context, value);
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (places.size() >= Rendering.MAX_PLACES) {
                            return context.reportInputMismatch(Rendering.class, "Too many places");
                        }
                        Rendering.Place place = context.readValue(parser, Rendering.Place.class);
                        if (place == null || place.anchor() == null) {
                            return context.reportInputMismatch(Rendering.class, "A place has an anchor");
                        }
                        places.add(place);
                    }
                }
                case "truncated" -> truncated = parser.getValueAsBoolean();
                default -> parser.skipChildren();
            }
        }
        return new Rendering(parts, places, truncated);
    }

    private static void expectArray(JsonParser parser, DeserializationContext context, JsonToken value) {
        if (value != JsonToken.START_ARRAY) {
            context.reportInputMismatch(Rendering.class, "Expected an array at " + parser.currentName());
        }
    }
}
