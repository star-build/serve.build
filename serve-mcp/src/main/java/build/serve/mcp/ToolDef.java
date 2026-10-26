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
import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * A {@link McpTool} with typed parameter declarations that drive schema generation and error wrapping.
 *
 * <p>Implement directly for named tool classes:
 *
 * <pre>{@code
 * class WeatherTool implements ToolDef {
 *     private static final ToolParam<String>  LOCATION = ToolParam.string("location", "City name");
 *     private static final ToolParam<Integer> DAYS     = ToolParam.integer("days", "Days").optional(3);
 *
 *     @Override public String name()              { return "get_weather"; }
 *     @Override public String description()       { return "Get a weather forecast"; }
 *     @Override public List<ToolParam<?>> params() { return List.of(LOCATION, DAYS); }
 *
 *     @Override
 *     public McpToolResult handle(JsonValue args) {
 *         return McpToolResult.text("Forecast for " + LOCATION.extract(args));
 *     }
 * }
 * }</pre>
 *
 * <p>Or use the fluent builder for inline tool definitions:
 *
 * <pre>{@code
 * ToolDef.of("get_weather", "Get a weather forecast")
 *     .param(LOCATION)
 *     .param(DAYS)
 *     .handle(args -> McpToolResult.text("Forecast for " + LOCATION.extract(args)));
 * }</pre>
 *
 * <p>{@code inputSchema()} is derived from {@code params()}. {@code call()} wraps {@code handle()}
 * with null-argument normalization and turns an {@link IllegalArgumentException} into an error result;
 * any other exception propagates to the server, which reports a generic failure to the client.
 *
 * @author reed.vonredwitz
 * @since May-2026
 */
public interface ToolDef extends McpTool {

    List<ToolParam<?>> params();

    /**
     * Params describing the shape of {@link McpToolResult#structuredContent()}. Empty by default —
     * override to derive {@link #outputSchema()} the same way {@link #inputSchema()} is derived from
     * {@link #params()}.
     */
    default List<ToolParam<?>> outputParams() {
        return List.of();
    }

    McpToolResult handle(JsonValue args) throws Exception;

    @Override
    default JsonObject inputSchema() {
        return schemaOf(params());
    }

    @Override
    default Optional<JsonObject> outputSchema() {
        final var outputParams = outputParams();
        return outputParams.isEmpty() ? Optional.empty() : Optional.of(schemaOf(outputParams));
    }

    private static JsonObject schemaOf(final List<ToolParam<?>> params) {
        final var props = JsonObject.builder();
        final var required = JsonArray.builder();
        final var allDefs = new LinkedHashMap<String, JsonObject>();
        for (final ToolParam<?> param : params) {
            props.put(param.name(), param.propertySchema());
            if (param.isRequired()) {
                required.add(param.name());
            }
            Defs.mergeInto(allDefs, param.defs());
        }
        final var builder = JsonObject.builder()
            .put("type", "object")
            .put("properties", props.build())
            .put("required", required.build());
        if (!allDefs.isEmpty()) {
            final var defsBuilder = JsonObject.builder();
            allDefs.forEach(defsBuilder::put);
            builder.put("$defs", defsBuilder.build());
        }
        return builder.build();
    }

    @Override
    default McpToolResult call(final JsonValue arguments) throws Exception {
        try {
            // "arguments" is optional on tools/call; the server passes JsonNull when it is omitted
            final JsonValue args = arguments == null || arguments instanceof JsonNull
                ? JsonObject.builder().build()
                : arguments;
            ParamBounds.validate(params(), args);
            return handle(args);
        } catch (final IllegalArgumentException e) {
            return McpToolResult.error(sanitize(e.getMessage()));
        }
    }

    private static String sanitize(final String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\r\\n\\t\\x00-\\x1F\\x7F]", " ");
    }

    static Builder of(final String name, final String description) {
        return new Builder(name, description);
    }

    final class Builder {

        private final String name;
        private final String description;
        private final List<ToolParam<?>> params = new ArrayList<>();
        private final List<ToolParam<?>> outputParams = new ArrayList<>();
        private McpToolAnnotations annotations = null;

        private Builder(final String name, final String description) {
            this.name = name;
            this.description = description;
        }

        public Builder param(final ToolParam<?> param) {
            params.add(param);
            return this;
        }

        public Builder outputParam(final ToolParam<?> param) {
            outputParams.add(param);
            return this;
        }

        public Builder annotations(final McpToolAnnotations annotations) {
            this.annotations = annotations;
            return this;
        }

        public ToolDef handle(final Handler handler) {
            return new BuiltTool(name, description, List.copyOf(params), List.copyOf(outputParams), handler,
                Optional.ofNullable(annotations));
        }
    }

    @FunctionalInterface
    interface Handler {
        McpToolResult handle(JsonValue args) throws Exception;
    }
}
