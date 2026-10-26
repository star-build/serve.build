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
import build.base.json.JsonNumber;
import build.base.json.JsonObject;
import build.base.json.JsonString;
import build.base.json.JsonValue;

import java.util.Collection;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The JSON shapes the MCP wire protocol uses: JSON-RPC envelopes, notifications, and the
 * descriptors and results for tools, resources and prompts.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class McpJson {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\r\\n\\t\\x00-\\x1F\\x7F]");

    private McpJson() {
    }

    // --- JSON-RPC ---

    static JsonObject envelope(final JsonValue id, final JsonValue result) {
        final var builder = JsonObject.builder().put("jsonrpc", "2.0");
        putId(builder, id);
        builder.put("result", result);
        return builder.build();
    }

    static JsonObject errorEnvelope(final JsonValue id, final int code, final String message) {
        final var error = JsonObject.builder()
            .put("code", code)
            .put("message", message)
            .build();
        final var builder = JsonObject.builder().put("jsonrpc", "2.0");
        putId(builder, id);
        builder.put("error", error);
        return builder.build();
    }

    static JsonObject notification(final String method) {
        return JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .build();
    }

    static JsonObject notification(final String method, final JsonObject params) {
        return JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .build();
    }

    private static void putId(final JsonObject.Builder builder, final JsonValue id) {
        if (id instanceof JsonNumber n) {
            builder.put("id", n.toNumber());
        } else if (id instanceof JsonString s) {
            builder.put("id", s.value());
        } else {
            builder.put("id", JsonNull.INSTANCE);
        }
    }

    // --- Reading ---

    /**
     * Returns {@code params} if it is an object, otherwise an empty object, so that handlers can
     * read members without first checking the shape the client sent.
     */
    static JsonObject paramsOf(final JsonValue params) {
        return params instanceof JsonObject object ? object : JsonObject.builder().build();
    }

    /**
     * Returns the string member {@code key}, or {@code ""} if it is absent or not a string.
     */
    static String string(final JsonObject object, final String key) {
        return object.members().get(key) instanceof JsonString s ? s.value() : "";
    }

    /**
     * Replaces control characters, so that client-supplied text can be logged or echoed in an
     * error message without forging lines.
     */
    static String sanitize(final String text) {
        return text == null ? "" : CONTROL_CHARACTERS.matcher(text).replaceAll(" ");
    }

    // --- Tools ---

    static JsonObject tool(final McpTool tool) {
        final var builder = JsonObject.builder()
            .put("name", tool.name())
            .put("description", tool.description())
            .put("inputSchema", tool.inputSchema());
        tool.outputSchema().ifPresent(schema -> builder.put("outputSchema", schema));
        tool.annotations().ifPresent(annotations -> builder.put("annotations", toolAnnotations(annotations)));
        return builder.build();
    }

    private static JsonObject toolAnnotations(final McpToolAnnotations annotations) {
        final var builder = JsonObject.builder();
        annotations.audience().ifPresent(roles -> builder.put("audience", array(roles, JsonString::of)));
        annotations.readOnlyHint().ifPresent(v -> builder.put("readOnlyHint", v));
        annotations.destructiveHint().ifPresent(v -> builder.put("destructiveHint", v));
        annotations.idempotentHint().ifPresent(v -> builder.put("idempotentHint", v));
        annotations.openWorldHint().ifPresent(v -> builder.put("openWorldHint", v));
        return builder.build();
    }

    static JsonObject toolResult(final McpToolResult result) {
        final var builder = JsonObject.builder()
            .put("content", array(result.content(), McpJson::toolContent))
            .put("isError", result.isError());
        result.structuredContent().ifPresent(structured -> builder.put("structuredContent", structured));
        return builder.build();
    }

    private static JsonObject toolContent(final McpContent content) {
        return switch (content) {
            case McpContent.Text text -> JsonObject.builder()
                .put("type", "text")
                .put("text", text.text())
                .build();
            case McpContent.Image image -> JsonObject.builder()
                .put("type", "image")
                .put("data", image.data())
                .put("mimeType", image.mimeType())
                .build();
            case McpContent.Audio audio -> JsonObject.builder()
                .put("type", "audio")
                .put("data", audio.data())
                .put("mimeType", audio.mimeType())
                .build();
            case McpContent.Resource resource -> JsonObject.builder()
                .put("type", "resource")
                .put("resource", resourceContent(resource.content()))
                .build();
        };
    }

    // --- Resources ---

    static JsonObject resource(final McpResource resource) {
        final var builder = JsonObject.builder()
            .put("uri", resource.uri())
            .put("name", resource.name());
        resource.description().ifPresent(d -> builder.put("description", d));
        resource.mimeType().ifPresent(m -> builder.put("mimeType", m));
        resource.size().ifPresent(s -> builder.put("size", s));
        return builder.build();
    }

    static JsonObject resourceTemplate(final McpResourceTemplate template) {
        final var builder = JsonObject.builder()
            .put("uriTemplate", template.uriTemplate())
            .put("name", template.name());
        template.description().ifPresent(d -> builder.put("description", d));
        template.mimeType().ifPresent(m -> builder.put("mimeType", m));
        return builder.build();
    }

    static JsonObject resourceContent(final McpResourceContent content) {
        return switch (content) {
            case McpResourceContent.Text text -> JsonObject.builder()
                .put("uri", text.uri())
                .put("mimeType", text.mimeType())
                .put("text", text.text())
                .build();
            case McpResourceContent.Blob blob -> JsonObject.builder()
                .put("uri", blob.uri())
                .put("mimeType", blob.mimeType())
                .put("blob", blob.blob())
                .build();
        };
    }

    // --- Prompts ---

    static JsonObject prompt(final McpPrompt prompt) {
        final var builder = JsonObject.builder().put("name", prompt.name());
        prompt.description().ifPresent(d -> builder.put("description", d));
        if (!prompt.arguments().isEmpty()) {
            builder.put("arguments", array(prompt.arguments(), McpJson::promptArgument));
        }
        return builder.build();
    }

    private static JsonObject promptArgument(final McpPromptArgument argument) {
        final var builder = JsonObject.builder()
            .put("name", argument.name())
            .put("required", argument.required());
        argument.description().ifPresent(d -> builder.put("description", d));
        return builder.build();
    }

    static JsonObject promptResult(final McpPromptResult result) {
        final var builder = JsonObject.builder();
        result.description().ifPresent(d -> builder.put("description", d));
        builder.put("messages", array(result.messages(), McpJson::promptMessage));
        return builder.build();
    }

    private static JsonObject promptMessage(final McpPromptMessage message) {
        final var content = switch (message) {
            case McpPromptMessage.Text text -> JsonObject.builder()
                .put("type", "text")
                .put("text", text.text())
                .build();
            case McpPromptMessage.Image image -> JsonObject.builder()
                .put("type", "image")
                .put("data", image.data())
                .put("mimeType", image.mimeType())
                .build();
        };
        return JsonObject.builder()
            .put("role", message.role())
            .put("content", content)
            .build();
    }

    private static <T> JsonArray array(final Collection<T> items,
                                       final Function<? super T, ? extends JsonValue> mapper) {
        final var builder = JsonArray.builder();
        for (final var item : items) {
            builder.add(mapper.apply(item));
        }
        return builder.build();
    }
}
