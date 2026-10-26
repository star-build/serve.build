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
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.mcp.tool.ToolDef;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that unexpected failures in tools, resources and prompts do not leak internal detail to the
 * client, and that interrupts and errors are not swallowed.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpErrorDisclosureTests {

    private static final String SECRET = "jdbc:postgresql://db.internal:5432/payroll";

    private McpServer mcpServer;
    private TestServer server;
    private String sessionId;

    @BeforeEach
    void setUp() {
        mcpServer = McpServer.builder("disclosure-server", "1.0.0")
            .tool(ToolDef.of("boom", "Fails unexpectedly").handle(args -> {
                throw new IllegalStateException(SECRET);
            }))
            .tool(ToolDef.of("invalid", "Rejects its input").handle(args -> {
                throw new IllegalArgumentException("days must be positive");
            }))
            .tool(ToolDef.of("fatal", "Throws an Error").handle(args -> {
                throw new AssertionError("fatal");
            }))
            .tool(new McpTool() {
                @Override
                public String name() {
                    return "raw";
                }

                @Override
                public String description() {
                    return "Fails outside ToolDef";
                }

                @Override
                public JsonObject inputSchema() {
                    return Json.parse("{\"type\":\"object\"}").asObject();
                }

                @Override
                public McpToolResult call(final JsonValue arguments) {
                    throw new IllegalStateException(SECRET);
                }
            })
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "test://broken";
                }

                @Override
                public String name() {
                    return "broken";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.empty();
                }

                @Override
                public Optional<Long> size() {
                    return Optional.empty();
                }

                @Override
                public McpResourceContent read() {
                    throw new IllegalStateException(SECRET);
                }
            })
            .template(new McpResourceTemplate() {
                @Override
                public String uriTemplate() {
                    return "test://items/{id}";
                }

                @Override
                public String name() {
                    return "items";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.empty();
                }

                @Override
                public McpResourceContent read(final String uri) {
                    throw new IllegalStateException(SECRET);
                }

                @Override
                public List<String> complete(final String argumentName, final String value) {
                    throw new IllegalStateException(SECRET);
                }
            })
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "broken";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public List<McpPromptArgument> arguments() {
                    return List.of(McpPromptArgument.required("arg").withCompleter(value -> {
                        throw new IllegalStateException(SECRET);
                    }));
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    throw new IllegalStateException(SECRET);
                }
            })
            .build();
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());
        final var init = server.post("/mcp").header("Content-Type", "application/json")
            .body(rpc("initialize", 1, "{}")).send();
        sessionId = init.header("Mcp-Session-Id");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void shouldNotLeakAToolExceptionMessage() {
        assertThat(call("tools/call", "{\"name\":\"boom\",\"arguments\":{}}")).doesNotContain("payroll");
    }

    @Test
    void shouldNotLeakAnExceptionMessageFromAToolOutsideToolDef() {
        assertThat(call("tools/call", "{\"name\":\"raw\",\"arguments\":{}}")).doesNotContain("payroll");
    }

    @Test
    void shouldStillReportAToolFailureAsAnError() {
        assertThat(call("tools/call", "{\"name\":\"boom\",\"arguments\":{}}")).contains("\"isError\":true");
    }

    @Test
    void shouldKeepTheMessageOfAnIllegalArgumentException() {
        assertThat(call("tools/call", "{\"name\":\"invalid\",\"arguments\":{}}"))
            .contains("days must be positive");
    }

    @Test
    void shouldNotLeakAResourceExceptionMessage() {
        assertThat(call("resources/read", "{\"uri\":\"test://broken\"}")).doesNotContain("payroll");
    }

    @Test
    void shouldNotLeakAPromptExceptionMessage() {
        assertThat(call("prompts/get", "{\"name\":\"broken\"}")).doesNotContain("payroll");
    }

    @Test
    void shouldNotLeakAResourceTemplateExceptionMessage() {
        assertThat(call("resources/read", "{\"uri\":\"test://items/1\"}")).doesNotContain("payroll");
    }

    @Test
    void shouldNotLeakAPromptCompletionExceptionMessage() {
        assertThat(call("completion/complete",
            "{\"ref\":{\"type\":\"ref/prompt\",\"name\":\"broken\"},\"argument\":{\"name\":\"arg\",\"value\":\"x\"}}"))
            .doesNotContain("payroll")
            .contains("Completion failed");
    }

    @Test
    void shouldNotLeakAResourceCompletionExceptionMessage() {
        assertThat(call("completion/complete",
            "{\"ref\":{\"type\":\"ref/resource\",\"uri\":\"test://items/{id}\"},"
                + "\"argument\":{\"name\":\"id\",\"value\":\"x\"}}"))
            .doesNotContain("payroll")
            .contains("Completion failed");
    }

    @Test
    void shouldAnswerARequestInABatchWhoseToolThrowsAnError() {
        final var batch = "[" + rpc("tools/call", 3, "{\"name\":\"fatal\",\"arguments\":{}}")
            + "," + rpc("ping", 4, "{}") + "]";

        final var body = server.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", sessionId)
            .body(batch)
            .send()
            .body();

        assertThat(body).contains("\"id\":3").contains("\"id\":4").contains("-32603");
    }

    @Test
    void shouldAnswerAStdioRequestWhoseToolThrowsAnError() {
        final var in = new ByteArrayInputStream(
            (rpc("tools/call", 7, "{\"name\":\"fatal\",\"arguments\":{}}") + "\n").getBytes(StandardCharsets.UTF_8));
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(in, out);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"id\":7").contains("-32603");
    }

    @Test
    void shouldPublishAFailedEventWhenAToolDefFails() {
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);

        call("tools/call", "{\"name\":\"boom\",\"arguments\":{}}");

        assertThat(received).extracting(ToolCallEvent::phase)
            .containsExactly(ToolCallEvent.Phase.STARTED, ToolCallEvent.Phase.FAILED);
    }

    private String call(final String method, final String params) {
        return server.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", sessionId)
            .body(rpc(method, 2, params))
            .send()
            .body();
    }

    private static String rpc(final String method, final int id, final String params) {
        return JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", Json.parse(params))
            .build()
            .toJsonString();
    }
}
