package com.dadacore.lore.web;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Describes the records of the API precisely: every component is required, unless it is
 * annotated {@code @Nullable}, in which case it accepts {@code null}.
 *
 * <p>Without it, every field of the description is optional, and the types generated from it
 * (the web interface's among them) say nothing about what the server always sends.
 */
final class RecordSchemas implements ModelConverter {

    private static final String REF_PREFIX = "#/components/schemas/";

    @Override
    public @Nullable Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
            Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema<?> schema = chain.next().resolve(type, context, chain);
        Class<?> record = recordClass(type);
        if (schema == null || record == null) {
            return schema;
        }
        Schema<?> defined = definition(schema, context);
        describe(record, defined);
        if (defined != schema && schema.getProperties() != null) {
            describe(record, schema);
        }
        return schema;
    }

    private static @Nullable Class<?> recordClass(AnnotatedType type) {
        if (type.getType() == null) {
            return null;
        }
        Class<?> raw = TypeFactory.defaultInstance().constructType(type.getType()).getRawClass();
        return raw.isRecord() ? raw : null;
    }

    /** The schema registered under components, which the returned one may only point to. */
    private static Schema<?> definition(Schema<?> schema, ModelConverterContext context) {
        String ref = schema.get$ref();
        String name = ref != null && ref.startsWith(REF_PREFIX) ? ref.substring(REF_PREFIX.length()) : schema.getName();
        Schema<?> defined = name == null ? null : context.getDefinedModels().get(name);
        return defined != null ? defined : schema;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void describe(Class<?> record, Schema model) {
        if (model.getProperties() == null) {
            return;
        }
        List<String> required = model.getRequired() == null ? new ArrayList<>() : new ArrayList<>(model.getRequired());
        for (RecordComponent component : record.getRecordComponents()) {
            String name = component.getName();
            Schema<?> property = (Schema<?>) model.getProperties().get(name);
            if (property == null) {
                continue;
            }
            if (isNullable(component)) {
                model.getProperties().put(name, nullable(property));
            } else if (!required.contains(name)) {
                required.add(name);
            }
        }
        model.setRequired(required.isEmpty() ? null : required);
    }

    private static boolean isNullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class)
                || component.isAnnotationPresent(Nullable.class);
    }

    private static Schema<?> nullable(Schema<?> property) {
        if (property.get$ref() != null) {
            return new JsonSchema().anyOf(List.of(new JsonSchema().$ref(property.get$ref()), nullType()));
        }
        if (property.getTypes() != null && !property.getTypes().isEmpty()) {
            Set<String> types = new LinkedHashSet<>(property.getTypes());
            types.add("null");
            property.setTypes(types);
        } else {
            property.setNullable(true);
        }
        return property;
    }

    private static Schema<?> nullType() {
        JsonSchema schema = new JsonSchema();
        schema.setTypes(new LinkedHashSet<>(List.of("null")));
        return schema;
    }
}
