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
package build.serve.mcp.tool;

import build.base.json.JsonArray;
import build.base.json.JsonNull;
import build.base.json.JsonNumber;
import build.base.json.JsonObject;
import build.base.json.JsonString;
import build.base.json.JsonValue;

import java.util.List;

/**
 * Default bounds applied to string and integer parameters, and the check that enforces the bounds a
 * parameter's schema advertises.
 *
 * <p>The defaults are written into the parameter's schema, so declaring {@code maxLength}, {@code min} or
 * {@code max} replaces them. Enforcement reads the schema rather than the extractor chain for that reason.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class ParamBounds {

    static final int DEFAULT_MAX_STRING_LENGTH = 65_536;
    static final int DEFAULT_MIN_INTEGER = -1_000_000;
    static final int DEFAULT_MAX_INTEGER = 1_000_000;

    private ParamBounds() {
    }

    /**
     * Validates {@code args} against every param: presence, type, declared constraints, and the bounds
     * advertised in each param's schema.
     *
     * @throws IllegalArgumentException if any argument is invalid
     */
    static void validate(final List<ToolParam<?>> params,
                         final JsonValue args) {
        if (params.isEmpty()) {
            return;
        }
        final var obj = args.asObject();
        for (final ToolParam<?> param : params) {
            param.extract(obj);
            if (!obj.has(param.name()) || obj.get(param.name()) instanceof JsonNull) {
                continue;
            }
            checkSchemaBounds(param.name(), param.propertySchema(), obj.get(param.name()));
        }
    }

    private static void checkSchemaBounds(final String name,
                                          final JsonObject schema,
                                          final JsonValue value) {
        final var members = schema.members();
        if (!(members.get("type") instanceof JsonString type)) {
            return;
        }
        switch (type.value()) {
            case "string" -> {
                if (members.get("maxLength") instanceof JsonNumber max && value instanceof JsonString string) {
                    final var limit = max.toNumber().intValue();
                    final var text = string.value();
                    // JSON Schema counts code points, not UTF-16 units; the cheap length check rules out most values
                    if (text.length() > limit && text.codePointCount(0, text.length()) > limit) {
                        throw new IllegalArgumentException(
                            "'" + name + "' must be at most " + limit + " character(s).");
                    }
                }
            }
            case "integer" -> {
                if (value instanceof JsonNumber number) {
                    final var actual = number.toNumber().doubleValue();
                    if (members.get("minimum") instanceof JsonNumber min
                        && actual < min.toNumber().doubleValue()) {
                        throw new IllegalArgumentException("'" + name + "' must be >= " + min.toNumber() + ".");
                    }
                    if (members.get("maximum") instanceof JsonNumber max
                        && actual > max.toNumber().doubleValue()) {
                        throw new IllegalArgumentException("'" + name + "' must be <= " + max.toNumber() + ".");
                    }
                }
            }
            case "object" -> {
                if (members.get("properties") instanceof JsonObject properties && value instanceof JsonObject object) {
                    properties.members().forEach((key, propertySchema) -> {
                        final var member = object.members().get(key);
                        if (member != null && !(member instanceof JsonNull) && propertySchema instanceof JsonObject nested) {
                            checkSchemaBounds(name + "." + key, nested, member);
                        }
                    });
                }
            }
            case "array" -> {
                if (members.get("items") instanceof JsonObject items && value instanceof JsonArray array) {
                    int index = 0;
                    for (final JsonValue element : array.values()) {
                        checkSchemaBounds(name + "[" + index++ + "]", items, element);
                    }
                }
            }
            default -> {
            }
        }
    }
}
