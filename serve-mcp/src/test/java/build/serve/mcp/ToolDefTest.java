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

import build.base.json.Json;
import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDefTest {

    // --- Schema generation ---

    @Test
    void shouldIncludeRequiredParamsInSchema() {
        final var name = ToolParam.string("name", "Voice name");
        final var notes = ToolParam.string("notes", "Note sequence");
        final var tool = ToolDef.of("voice.create", "Create a voice")
            .param(name).param(notes)
            .handle(args -> McpToolResult.text("ok"));

        final JsonObject schema = tool.inputSchema();
        assertThat(schema.getString("type")).isEqualTo("object");
        assertThat(schema.get("properties").asObject().has("name")).isTrue();
        assertThat(schema.get("properties").asObject().has("notes")).isTrue();
        assertThat(schema.get("required").asArray().values()).hasSize(2);
    }

    @Test
    void shouldExcludeOptionalParamsFromRequiredArray() {
        final var name = ToolParam.string("name", "Voice name");
        final var octave = ToolParam.integer("octave", "Octave").optional(3);
        final var tool = ToolDef.of("test", "Test tool")
            .param(name).param(octave)
            .handle(args -> McpToolResult.text("ok"));

        final var required = tool.inputSchema().get("required").asArray();
        assertThat(required.values()).hasSize(1);
        assertThat(required.element(0).asString().value()).isEqualTo("name");
    }

    @Test
    void shouldProduceEmptyRequiredArrayWhenNoParams() {
        final var tool = ToolDef.of("noop", "No params").handle(args -> McpToolResult.text("ok"));
        assertThat(tool.inputSchema().get("required").asArray().values()).isEmpty();
    }

    // --- Output schema generation ---

    @Test
    void shouldHaveNoOutputSchemaByDefault() {
        final var tool = ToolDef.of("noop", "No output params").handle(args -> McpToolResult.text("ok"));
        assertThat(tool.outputSchema()).isEmpty();
    }

    @Test
    void shouldDeriveOutputSchemaFromOutputParams() {
        final var summary = ToolParam.string("summary", "Result summary");
        final var count = ToolParam.integer("count", "Item count");
        final var tool = ToolDef.of("search", "Search something")
            .outputParam(summary).outputParam(count)
            .handle(args -> McpToolResult.text("ok"));

        final var schema = tool.outputSchema().orElseThrow();
        assertThat(schema.getString("type")).isEqualTo("object");
        assertThat(schema.get("properties").asObject().has("summary")).isTrue();
        assertThat(schema.get("properties").asObject().has("count")).isTrue();
        assertThat(schema.get("required").asArray().values()).hasSize(2);
    }

    // --- call() ---

    @Test
    void shouldInvokeHandlerOnCall() throws Exception {
        final var location = ToolParam.string("location", "City");
        final var tool = ToolDef.of("weather", "Get weather")
            .param(location)
            .handle(args -> McpToolResult.text("Sunny in " + location.extract(args)));

        final var result = tool.call(Json.parse("{\"location\":\"Rome\"}"));
        assertThat(result.isError()).isFalse();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).isEqualTo("Sunny in Rome");
    }

    @Test
    void shouldConvertIllegalArgumentExceptionToError() throws Exception {
        final var required = ToolParam.string("x", "x");
        final var tool = ToolDef.of("t", "t")
            .param(required)
            .handle(args -> McpToolResult.text(required.extract(args)));

        final var result = tool.call(Json.parse("{}"));
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).contains("x");
    }

    @Test
    void shouldReportMissingRequiredArgumentWhenArgumentsAreOmitted() throws Exception {
        final var required = ToolParam.string("x", "x");
        final var tool = ToolDef.of("t", "t")
            .param(required)
            .handle(args -> McpToolResult.text(required.extract(args)));

        final var result = tool.call(JsonNull.INSTANCE);
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).contains("Missing required argument 'x'");
    }

    @Test
    void shouldLetUnexpectedExceptionsPropagate() {
        final var tool = ToolDef.of("t", "t")
            .handle(args -> {
                throw new RuntimeException("boom");
            });

        assertThatThrownBy(() -> tool.call(Json.parse("{}"))).hasMessage("boom");
    }

    @Test
    void shouldLetAnInterruptPropagate() {
        final var tool = ToolDef.of("t", "t")
            .handle(args -> {
                throw new InterruptedException("stop");
            });

        assertThatThrownBy(() -> tool.call(Json.parse("{}"))).isInstanceOf(InterruptedException.class);
    }

    @Test
    void shouldLetAnErrorPropagate() {
        final var tool = ToolDef.of("t", "t")
            .handle(args -> {
                throw new AssertionError("fatal");
            });

        assertThatThrownBy(() -> tool.call(Json.parse("{}"))).isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldSanitizeCRLFInExceptionMessage() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .handle(args -> {
                throw new IllegalArgumentException("msg\r\nX-Injected: evil");
            });

        final var result = tool.call(Json.parse("{}"));
        assertThat(result.isError()).isTrue();
        final var text = ((McpContent.Text) result.content().getFirst()).text();
        assertThat(text).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void shouldHandleNullExceptionMessageWithoutThrowingOrLiteralNull() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .handle(args -> {
                throw new IllegalArgumentException();
            });

        final var result = tool.call(Json.parse("{}"));
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).doesNotContain("null");
    }

    @Test
    void shouldNormalizeNullArgumentsToEmptyObject() throws Exception {
        final var octave = ToolParam.integer("octave", "Octave").optional(4);
        final var tool = ToolDef.of("t", "t")
            .param(octave)
            .handle(args -> McpToolResult.text(String.valueOf(octave.extract(args))));

        final var result = tool.call(null);
        assertThat(result.isError()).isFalse();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).isEqualTo("4");
    }

    // --- Metadata ---

    @Test
    void shouldExposeNameAndDescription() {
        final var tool = ToolDef.of("my.tool", "Does something").handle(args -> McpToolResult.text("ok"));
        assertThat(tool.name()).isEqualTo("my.tool");
        assertThat(tool.description()).isEqualTo("Does something");
    }

    // --- Named class implementation ---

    @Test
    void shouldSupportNamedClassImplementation() throws Exception {
        final var tool = new EchoTool();
        assertThat(tool.name()).isEqualTo("echo");
        assertThat(tool.inputSchema().get("properties").asObject().has("message")).isTrue();

        final var result = tool.call(Json.parse("{\"message\":\"hello\"}"));
        assertThat(result.isError()).isFalse();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).isEqualTo("hello");
    }

    private static class EchoTool implements ToolDef {
        private static final ToolParam<String> MESSAGE = ToolParam.string("message", "Text to echo");

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echo a message";
        }

        @Override
        public List<ToolParam<?>> params() {
            return List.of(MESSAGE);
        }

        @Override
        public McpToolResult handle(final JsonValue args) {
            return McpToolResult.text(MESSAGE.extract(args));
        }
    }
}
