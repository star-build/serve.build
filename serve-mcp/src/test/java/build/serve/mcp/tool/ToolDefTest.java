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

import build.base.json.Json;
import build.base.json.JsonArray;
import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import build.serve.mcp.McpContent;
import build.serve.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // --- Argument validation against the advertised schema ---

    @Test
    void shouldRejectMissingRequiredArgumentWithoutInvokingHandler() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("x", "x"))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{}"));
        assertThat(result.isError()).isTrue();
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldRejectWrongTypeWithoutInvokingHandler() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.integer("times", "times"))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"times\":\"three\"}"));
        assertThat(result.isError()).isTrue();
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldRejectViolatedConstraintEvenWhenHandlerNeverExtractsTheParam() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("title", "title").maxLength(5))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"title\":\"too long\"}"));
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).contains("title");
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldInvokeHandlerWhenArgumentsSatisfyTheSchema() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("title", "title").maxLength(5))
            .param(ToolParam.integer("times", "times").optional(1))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"title\":\"short\"}"));
        assertThat(result.isError()).isFalse();
        assertThat(invoked).isTrue();
    }

    // --- Default bounds ---

    @Test
    void shouldRejectStringLongerThanDefaultMaximumLength() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("body", "body"))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"body\":\"" + "a".repeat(65_537) + "\"}"));
        assertThat(result.isError()).isTrue();
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldAcceptStringAtDefaultMaximumLength() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("body", "body"))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"body\":\"" + "a".repeat(65_536) + "\"}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldRejectIntegerAboveDefaultMaximum() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.integer("times", "times").min(1))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"times\":2147483647}"));
        assertThat(result.isError()).isTrue();
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldRejectIntegerBelowDefaultMinimum() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.integer("offset", "offset"))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"offset\":-2147483648}"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldAdvertiseDefaultBoundsInSchema() {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("body", "body"))
            .param(ToolParam.integer("times", "times"))
            .handle(args -> McpToolResult.text("ok"));

        final var props = tool.inputSchema().get("properties").asObject();
        assertThat(props.get("body").asObject().has("maxLength")).isTrue();
        assertThat(props.get("times").asObject().has("maximum")).isTrue();
        assertThat(props.get("times").asObject().has("minimum")).isTrue();
    }

    @Test
    void shouldLetAToolOptOutOfDefaultBoundsByDeclaringItsOwn() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("body", "body").maxLength(Integer.MAX_VALUE))
            .param(ToolParam.integer("times", "times").max(Integer.MAX_VALUE))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse(
            "{\"body\":\"" + "a".repeat(65_537) + "\",\"times\":2147483647}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldRejectOversizedStringNestedInObject() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.object("doc", "doc", List.of(ToolParam.string("body", "body"))))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"doc\":{\"body\":\"" + "a".repeat(65_537) + "\"}}"));
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).contains("doc.body");
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldRejectOversizedStringInArrayItems() throws Exception {
        final var invoked = new AtomicBoolean();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.array("lines", "lines", ToolParam.string("line", "line")))
            .handle(args -> {
                invoked.set(true);
                return McpToolResult.text("ok");
            });

        final var result = tool.call(Json.parse("{\"lines\":[\"ok\",\"" + "a".repeat(65_537) + "\"]}"));
        assertThat(result.isError()).isTrue();
        assertThat(invoked).isFalse();
    }

    @Test
    void shouldRejectIntegerOutOfRangeNestedInObject() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.object("doc", "doc", List.of(ToolParam.integer("n", "n"))))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"doc\":{\"n\":2147483647}}"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldCountMaxLengthInCodePoints() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("title", "title").maxLength(2))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"title\":\"😀😀\"}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldIgnoreSchemaWhoseTypeIsNotASingleString() throws Exception {
        final var schema = JsonObject.builder()
            .put("type", JsonArray.builder().add("string").add("null").build())
            .build();
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.<String>of("v", "v", schema, val -> val.asString().value()))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"v\":\"x\"}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldRejectStringWhoseCodePointCountExceedsMaximum() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("title", "title").maxLength(2))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"title\":\"😀😀😀\"}"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldAcceptIntegersExactlyAtDefaultBounds() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.integer("high", "high"))
            .param(ToolParam.integer("low", "low"))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"high\":1000000,\"low\":-1000000}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldRejectIntegerJustAboveDefaultMaximum() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.integer("times", "times"))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"times\":1000001}"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldRejectIntegerBeyondIntRangeEvenWhenDeclaredMaximumIsIntMax() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.integer("times", "times").max(Integer.MAX_VALUE))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"times\":5000000000}"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldSkipBoundsForOptionalParamMissingOrNull() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("body", "body").optional("x"))
            .param(ToolParam.integer("times", "times").optional(1))
            .handle(args -> McpToolResult.text("ok"));

        assertThat(tool.call(Json.parse("{}")).isError()).isFalse();
        assertThat(tool.call(Json.parse("{\"body\":null,\"times\":null}")).isError()).isFalse();
    }

    @Test
    void shouldReportArrayIndexInErrorForOversizedItem() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.array("lines", "lines", ToolParam.string("line", "line")))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"lines\":[\"ok\",\"" + "a".repeat(65_537) + "\"]}"));
        assertThat(((McpContent.Text) result.content().getFirst()).text()).contains("lines[1]");
    }

    @Test
    void shouldRejectIntegerOutOfRangeInArrayItems() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.array("ids", "ids", ToolParam.integer("id", "id")))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"ids\":[1,2,2147483647]}"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldAcceptEmptyArray() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.array("lines", "lines", ToolParam.string("line", "line")))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"lines\":[]}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldRejectOversizedStringInObjectsWithinArray() throws Exception {
        final var item = ToolParam.object("entry", "entry", List.of(ToolParam.string("body", "body")));
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.array("entries", "entries", item))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"entries\":[{\"body\":\"" + "a".repeat(65_537) + "\"}]}"));
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).contains("entries[0].body");
    }

    @Test
    void shouldSkipNullAndAbsentMembersOfNestedObject() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.object("doc", "doc", List.of(
                ToolParam.string("a", "a").optional("x"),
                ToolParam.string("b", "b").optional("x"))))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"doc\":{\"a\":null}}"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldNotLeakOversizedValueInErrorMessage() throws Exception {
        final var tool = ToolDef.of("t", "t")
            .param(ToolParam.string("body", "body"))
            .handle(args -> McpToolResult.text("ok"));

        final var result = tool.call(Json.parse("{\"body\":\"" + "secret".repeat(20_000) + "\"}"));
        assertThat(result.isError()).isTrue();
        assertThat(((McpContent.Text) result.content().getFirst()).text()).doesNotContain("secret");
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
