/*-
 * #%L
 * Serve MCP
 * %%
 * Copyright (C) 2026 Reed von Redwitz
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package build.serve.mcp;

import build.base.json.JsonArray;
import build.base.json.JsonObject;
import build.base.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A typed tool parameter declaration that drives both JSON Schema generation and type-safe argument extraction.
 *
 * <p>Use the static factories to declare parameters; call {@link #optional()} or {@link #optional(Object)}
 * to make them optional. Use {@code var} or the concrete type to retain access to constraint methods:
 *
 * <pre>{@code
 * var name   = ToolParam.string("name",  "Voice name");
 * var octave = ToolParam.integer("octave", "Bass octave").optional(3);
 * var dir    = ToolParam.string("direction", "...").values(List.of("up","down")).optional("up");
 * var tempo  = ToolParam.number("tempo", "BPM").min(20.0).max(300.0);
 * var title  = ToolParam.string("title", "Track title").minLength(1).maxLength(200);
 * }</pre>
 *
 * <p>Object and array schemas compose from other params:
 *
 * <pre>{@code
 * var type     = ToolParam.string("type", "Step type").values(List.of("seed", "filter", "terminal"));
 * var id       = ToolParam.string("id", "Step ID");
 * var step     = ToolParam.object("step", "A pipeline step", List.of(type, id));
 * var pipeline = ToolParam.array("pipeline", "Ordered steps", step);
 * }</pre>
 *
 * <p><b>Self-referential / recursive types:</b> {@link #object} and {@link #array} both build their
 * schema eagerly, by calling {@code propertySchema()} on their field/item arguments at construction
 * time. A builder method that calls itself to construct its own field or item schema (a tree node
 * whose children are themselves tree nodes, for example) therefore recurses without bound at the Java
 * call-stack level and crashes with {@link StackOverflowError} — no amount of library-side cycle
 * detection can catch this, since the recursive call happens while evaluating the argument, before
 * {@code object}/{@code array} are ever entered. Self-reference (direct or mutual) must go through
 * {@link #lazy}, deferring construction behind a {@link java.util.function.Supplier} instead of
 * building it inline. See {@code ToolParamTest.shouldSupportSelfReferentialRecursiveSchema} for the
 * pattern.
 *
 * @param <T> the extracted Java type
 * @author reed.vonredwitz
 * @since May-2026
 */
public interface ToolParam<T> {

    String name();

    String description();

    boolean isRequired();

    JsonObject propertySchema();

    /**
     * Returns the {@code $defs} entries that must be hoisted into the root schema. Empty by default.
     */
    default Map<String, JsonObject> defs() {
        return Map.of();
    }

    T extract(JsonValue args);

    /**
     * Extracts a value directly from {@code value} without looking it up by name in a parent object.
     */
    T extractSelf(JsonValue value);

    ToolParam<T> optional();

    ToolParam<T> optional(T defaultValue);

    ToolParam<T> refine(Predicate<T> predicate, String message);

    /**
     * Extracts the parameter value, returning {@link Optional#empty()} instead of throwing if the
     * value is absent or fails validation. Most useful for required parameters where callers prefer
     * an {@code Optional} over a try/catch. For optional parameters with no default, prefer
     * {@link #extract} directly — both paths return the same empty result and the distinction is lost.
     */
    default Optional<T> safeParse(final JsonValue args) {
        try {
            return Optional.ofNullable(extract(args));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // --- Factories ---

    static StringParam string(final String name,
                              final String description) {
        return new StringParam(name, description, true, null,
            val -> val.asString().value(),
            JsonObject.builder()
                .put("type", "string")
                .put("description", description)
                .put("maxLength", ParamBounds.DEFAULT_MAX_STRING_LENGTH)
                .build());
    }

    static NumberParam<Integer> integer(final String name,
                                        final String description) {
        return new NumberParam<>(name, description, true, null,
            val -> val.asNumber().toNumber().intValue(),
            JsonObject.builder()
                .put("type", "integer")
                .put("description", description)
                .put("minimum", ParamBounds.DEFAULT_MIN_INTEGER)
                .put("maximum", ParamBounds.DEFAULT_MAX_INTEGER)
                .build());
    }

    static BoolParam bool(final String name,
                          final String description) {
        return new BoolParam(name, description, true, null,
            val -> val.asBoolean().value(),
            JsonObject.builder().put("type", "boolean").put("description", description).build());
    }

    static NumberParam<Double> number(final String name,
                                      final String description) {
        return new NumberParam<>(name, description, true, null,
            val -> val.asNumber().toNumber().doubleValue(),
            JsonObject.builder().put("type", "number").put("description", description).build());
    }

    static ArrayParam<JsonValue> array(final String name,
                                       final String description) {
        return new ArrayParam<>(name, description, true, null,
            val -> val.asArray().values(),
            JsonObject.builder().put("type", "array").put("description", description).build());
    }

    static <T> ArrayParam<T> array(final String name,
                                   final String description,
                                   final ToolParam<T> itemSchema) {
        return new ArrayParam<>(name, description, true, null,
            val -> val.asArray().values().stream().map(itemSchema::extractSelf).toList(),
            JsonObject.builder()
                .put("type", "array")
                .put("description", description)
                .put("items", itemSchema.propertySchema())
                .build(),
            itemSchema);
    }

    static ObjectParam object(final String name,
                              final String description,
                              final List<ToolParam<?>> fields) {
        final var props = JsonObject.builder();
        final var requiredArr = JsonArray.builder();
        for (final ToolParam<?> field : fields) {
            props.put(field.name(), field.propertySchema());
            if (field.isRequired()) {
                requiredArr.add(field.name());
            }
        }
        final var schema = JsonObject.builder()
            .put("type", "object")
            .put("description", description)
            .put("properties", props.build())
            .put("required", requiredArr.build())
            .build();
        return new ObjectParam(name, description, true, null,
            val -> {
                final var obj = val.asObject();
                for (final ToolParam<?> field : fields) {
                    field.extract(obj);
                }
                return obj;
            },
            schema, fields);
    }

    static <T> ToolParam<T> of(final String name,
                               final String description,
                               final JsonObject schema,
                               final Extractor<T> extractor) {
        return new GenericParam<>(name, description, true, null, extractor, schema);
    }

    /**
     * A string-keyed enum that maps JSON string values to typed Java objects.
     *
     * <p>The generated schema is a {@code string} with an {@code enum} array of the entry keys.
     * Extraction looks up the incoming string in the entry list and returns the paired Java value.
     */
    static <T> ToolParam<T> enumParam(final String name,
                                      final String description,
                                      final List<Map.Entry<String, T>> entries) {
        final var enumArray = JsonArray.builder();
        entries.forEach(e -> enumArray.add(e.getKey()));
        final var schema = JsonObject.builder()
            .put("type", "string")
            .put("description", description)
            .put("enum", enumArray.build())
            .build();
        return new GenericParam<>(name, description, true, null,
            val -> {
                final var key = val.asString().value();
                for (final var entry : entries) {
                    if (entry.getKey().equals(key)) {
                        return entry.getValue();
                    }
                }
                throw new IllegalArgumentException(
                    "'" + name + "' has unknown value '" + key + "'.");
            },
            schema);
    }

    /**
     * An untyped {@code oneOf} — emits a JSON Schema {@code oneOf} hint and returns the raw object.
     *
     * <p>Use when dispatch logic is handled by the caller. For typed dispatch keyed on a discriminator
     * field, use {@link #oneOf(String, String, String, List)} instead.
     */
    static ToolParam<JsonObject> oneOf(final String name,
                                       final String description,
                                       final List<ObjectParam> variants) {
        final var oneOfArray = JsonArray.builder();
        variants.forEach(v -> oneOfArray.add(v.propertySchema()));
        final var schema = JsonObject.builder()
            .put("description", description)
            .put("oneOf", oneOfArray.build())
            .build();
        return new GenericParam<>(name, description, true, null,
            val -> val.asObject(),
            schema,
            () -> {
                final var merged = new LinkedHashMap<String, JsonObject>();
                variants.forEach(v -> Defs.mergeInto(merged, v.defs()));
                return merged;
            });
    }

    /**
     * A typed discriminated {@code oneOf} — injects a {@code const} for the discriminator field into
     * each variant's schema, then dispatches extraction to the matching variant at runtime.
     *
     * <p>Each entry pairs a discriminator string value with the param that handles that variant.
     * Use {@link ToolParamBase#map(java.util.function.Function)} on a variant param to convert its
     * raw extraction result to a shared type {@code T}.
     *
     * @throws IllegalArgumentException if a variant schema has no {@code properties} block
     */
    static <T> ToolParam<T> oneOf(final String name,
                                  final String description,
                                  final String discriminator,
                                  final List<Map.Entry<String, ToolParam<T>>> variants) {
        final var oneOfArray = JsonArray.builder();
        final var lookup = new LinkedHashMap<String, ToolParam<T>>();
        for (final var entry : variants) {
            oneOfArray.add(injectDiscriminator(entry.getValue().propertySchema(), discriminator, entry.getKey()));
            lookup.put(entry.getKey(), entry.getValue());
        }
        final var schema = JsonObject.builder()
            .put("description", description)
            .put("oneOf", oneOfArray.build())
            .build();
        return new GenericParam<>(name, description, true, null,
            val -> {
                final var obj = val.asObject();
                final var discValue = obj.getString(discriminator);
                final var variant = lookup.get(discValue);
                if (variant == null) {
                    throw new IllegalArgumentException(
                        "Unknown '" + discriminator + "' value '" + discValue + "'.");
                }
                return variant.extractSelf(val);
            },
            schema,
            () -> {
                final var merged = new LinkedHashMap<String, JsonObject>();
                lookup.values().forEach(v -> Defs.mergeInto(merged, v.defs()));
                return merged;
            });
    }

    /**
     * A lazy reference for recursive schemas — emits {@code {"$ref":"#/$defs/<defName>"}} as the
     * property schema, and resolves the supplier on first use to populate the {@code $defs} entry.
     *
     * <p>{@code defs()} walks the resolved schema transitively, so nesting {@code lazy} params inside
     * other lazy suppliers (including mutual recursion, A referencing B and B referencing A) is
     * supported: every reachable {@code $defs} entry is hoisted by {@code ToolDef.inputSchema()}, not
     * just the top-level param's own def.
     */
    static <T> ToolParam<T> lazy(final String name,
                                 final String description,
                                 final String defName,
                                 final Supplier<ToolParam<T>> supplier) {
        return new LazyParam<>(name, description, defName, supplier);
    }

    private static JsonObject injectDiscriminator(final JsonObject schema,
                                                  final String discriminator,
                                                  final String value) {
        final var members = schema.members();
        final var propsValue = members.get("properties");
        if (propsValue == null) {
            throw new IllegalArgumentException(
                "Variant schema for discriminated oneOf must have 'properties'.");
        }
        final var newProps = JsonObject.builder();
        propsValue.asObject().members().forEach((k, v) -> newProps.put(k, v));
        newProps.put(discriminator, JsonObject.builder().put("const", value).build());
        final var newRequired = JsonArray.builder();
        final var existingRequired = members.get("required");
        if (existingRequired != null) {
            existingRequired.asArray().values().forEach(v -> newRequired.add(v.asString().value()));
        }
        newRequired.add(discriminator);
        final var builder = JsonObject.builder();
        members.forEach((k, v) -> {
            if (!k.equals("properties") && !k.equals("required")) {
                builder.put(k, v);
            }
        });
        builder.put("properties", newProps.build());
        builder.put("required", newRequired.build());
        return builder.build();
    }

    // --- Extractor ---

    @FunctionalInterface
    interface Extractor<T> {
        T extract(JsonValue value);
    }
}
