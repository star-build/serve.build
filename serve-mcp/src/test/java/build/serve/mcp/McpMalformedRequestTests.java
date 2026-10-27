package build.serve.mcp;

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

import build.base.json.Json;
import build.base.json.JsonNull;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link McpServer} answers a malformed HTTP POST body with a client error rather than a
 * server error.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpMalformedRequestTests {

    @Test
    void shouldRefuseUnparseableJsonWithBadRequest() {
        try (var server = TestServer.of(McpServer.builder("bad-json", "1.0.0").build().handler())) {
            final var response = server.post("/")
                .contentType("application/json")
                .body("{not json")
                .send();

            assertThat(response.status()).isEqualTo(400);
            final var error = Json.parse(response.body()).asObject().get("error").asObject();
            assertThat(error.get("code").asNumber().toNumber().intValue()).isEqualTo(-32700);
        }
    }

    @Test
    void shouldRefuseScalarJsonWithBadRequest() {
        try (var server = TestServer.of(McpServer.builder("scalar-json", "1.0.0").build().handler())) {
            final var response = server.post("/")
                .contentType("application/json")
                .body("42")
                .send();

            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Test
    void shouldAnswerBatchOfNonObjectsWithInvalidRequestPerElement() {
        try (var client = McpTestClient.start(McpServer.builder("bad-batch", "1.0.0").build())) {
            client.initialize();

            final var response = client.post("/mcp")
                .contentType("application/json")
                .header("Mcp-Session-Id", client.sessionId)
                .body("[1, \"x\"]")
                .send();

            assertThat(response.status()).isEqualTo(200);
            final var entries = Json.parse(response.body()).asArray().values();
            assertThat(entries).hasSize(2).allSatisfy(entry -> {
                assertThat(entry.asObject().get("id")).isEqualTo(JsonNull.INSTANCE);
                assertThat(entry.asObject().get("error").asObject().get("code").asNumber().toNumber().intValue())
                    .isEqualTo(-32600);
            });
        }
    }

    @Test
    void shouldRefuseEmptyBodyWithBadRequest() {
        try (var server = TestServer.of(McpServer.builder("empty-body", "1.0.0").build().handler())) {
            final var response = server.post("/")
                .contentType("application/json")
                .body("")
                .send();

            assertThat(response.status()).isEqualTo(400);
        }
    }
}
