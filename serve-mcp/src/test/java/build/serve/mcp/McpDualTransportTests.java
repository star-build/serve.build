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
import build.base.json.JsonArray;
import build.base.json.JsonObject;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for a single {@link McpServer} serving its HTTP handler and stdio loop at the same time.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpDualTransportTests {

    @Test
    void shouldNotSubscribeStdioSessionFromSessionlessHttpRequest() {
        final var server = McpServer.builder("dual-server", "1.0.0").build();
        final var notifications = new CopyOnWriteArrayList<JsonObject>();

        try (var stdio = McpStdioClient.of(server, Duration.ofSeconds(5), notifications::add);
             var http = TestServer.of(RouterBuilder.create().route("/mcp", server.handler()).build())) {

            stdio.send("ping", Map.of()); // ensures the stdio session exists

            http.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\","
                    + "\"params\":{\"uri\":\"test://data\"}}")
                .send()
                .assertStatus(400);

            server.notifyResourceChanged("test://data");

            stdio.send("ping", Map.of()); // flushes any notification queued ahead of the response
            assertThat(notifications).isEmpty();
        }
    }

    @Test
    void shouldNotBindStdioSessionIdForHttpToolCall() {
        final var server = McpServer.builder("dual-server", "1.0.0")
            .tool(ToolDef.of("whoami", "Returns the bound session id")
                .handle(args -> McpToolResult.text(McpServer.SESSION_ID.orElse("unbound"))))
            .build();

        try (var http = TestServer.of(RouterBuilder.create().route("/mcp", server.handler()).build())) {
            http.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"whoami\",\"arguments\":{}}}")
                .send()
                .assertStatus(400);

            final var initialize = http.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .send()
                .assertStatus(200);
            final var response = http.post("/mcp")
                .header("Content-Type", "application/json")
                .header("Mcp-Session-Id", initialize.header("Mcp-Session-Id"))
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"whoami\",\"arguments\":{}}}")
                .send()
                .assertStatus(200);

            final var text = ((JsonArray) Json.parse(response.body()).asObject()
                .get("result").asObject().get("content")).values().get(0).asObject().getString("text");
            assertThat(text).isNotEqualTo("local");
        }
    }
}
