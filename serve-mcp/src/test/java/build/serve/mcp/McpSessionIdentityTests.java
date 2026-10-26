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
import build.serve.foundation.Handler;
import build.serve.foundation.context.RequestContext;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestResponse;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that an MCP session is bound to the principal that created it, and that requests carrying no
 * session are not pooled into a shared one.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpSessionIdentityTests {

    private static final String USER_HEADER = "X-Test-User";

    private TestServer server;

    @BeforeEach
    void setUp() {
        final var mcpServer = McpServer.builder("identity-server", "1.0.0")
            .tool(ToolDef.of("session", "Reports the session id bound to the call")
                .handle(args -> McpToolResult.text(
                    McpServer.SESSION_ID.isBound() ? McpServer.SESSION_ID.get() : "unbound")))
            .build();
        final var mcp = mcpServer.handler();
        // stands in for AuthMiddleware: the X-Test-User header is the authenticated principal
        final Handler authenticated = exchange -> {
            final var user = exchange.request().header(USER_HEADER);
            if (user.isEmpty()) {
                mcp.handle(exchange);
                return;
            }
            ScopedValue.where(RequestContext.PRINCIPAL, user.get()).call(() -> {
                mcp.handle(exchange);
                return null;
            });
        };
        server = TestServer.of(RouterBuilder.create().route("/mcp", authenticated).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void shouldNotLetAnotherPrincipalPostOnASession() {
        final var sessionId = initialize("alice");

        final var response = post("bob", sessionId, rpc("tools/call", 2,
            "{\"name\":\"session\",\"arguments\":{}}"));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void shouldNotLetAnotherPrincipalDeleteASession() {
        final var sessionId = initialize("alice");

        server.delete("/mcp").header(USER_HEADER, "bob").header("Mcp-Session-Id", sessionId).send();

        final var response = post("alice", sessionId, rpc("ping", 2, "{}"));
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void shouldNotLetAnotherPrincipalOpenTheNotificationStream() {
        final var sessionId = initialize("alice");

        // a rejected request closes the stream at once; an accepted one stays open
        try (final var stream = server.sse("/mcp", Map.of(USER_HEADER, "bob", "Mcp-Session-Id", sessionId))) {
            assertThat(stream.collectAll(Duration.ofSeconds(2))).isEmpty();
        }
    }

    @Test
    void shouldLetTheOwningPrincipalUseItsSession() {
        final var sessionId = initialize("alice");

        final var response = post("alice", sessionId, rpc("tools/call", 2,
            "{\"name\":\"session\",\"arguments\":{}}"));

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void shouldNotLetAnUnauthenticatedCallerUseAnAuthenticatedSession() {
        final var sessionId = initialize("alice");

        final var response = post(null, sessionId, rpc("ping", 2, "{}"));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void shouldRejectRequestsWithoutASessionOtherThanInitialize() {
        final var response = post("alice", null, rpc("tools/call", 1,
            "{\"name\":\"session\",\"arguments\":{}}"));

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void shouldRejectABatchWithoutASessionEvenWhenItInitializes() {
        final var response = post("alice", null, "[" + rpc("initialize", 1, "{}") + "]");

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void shouldNotShareASessionBetweenCallersWhoOmitTheHeader() {
        final var alice = post("alice", null, rpc("tools/call", 1,
            "{\"name\":\"session\",\"arguments\":{}}"));
        final var bob = post("bob", null, rpc("tools/call", 1,
            "{\"name\":\"session\",\"arguments\":{}}"));

        assertThat(alice.body()).doesNotContain("\"anonymous\"");
        assertThat(bob.body()).doesNotContain("\"anonymous\"");
    }

    private String initialize(final String user) {
        final var response = post(user, null, rpc("initialize", 1, "{}"));
        assertThat(response.status()).isEqualTo(200);
        final var sessionId = response.header("Mcp-Session-Id");
        assertThat(sessionId).isNotNull();
        return sessionId;
    }

    private TestResponse post(final String user, final String sessionId, final String body) {
        var request = server.post("/mcp").header("Content-Type", "application/json");
        if (user != null) {
            request = request.header(USER_HEADER, user);
        }
        if (sessionId != null) {
            request = request.header("Mcp-Session-Id", sessionId);
        }
        return request.body(body).send();
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
