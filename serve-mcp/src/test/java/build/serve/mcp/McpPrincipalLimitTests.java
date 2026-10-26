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

import build.base.json.JsonObject;
import build.serve.foundation.Handler;
import build.serve.foundation.context.RequestContext;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestResponse;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that one authenticated principal cannot take every MCP session.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpPrincipalLimitTests {

    private static final String USER_HEADER = "X-Test-User";

    private TestServer server;

    @BeforeEach
    void setUp() {
        final var mcpServer = McpServer.builder("principal-limit-server", "1.0.0")
            .maxSessions(10)
            .maxSessionsPerPrincipal(2)
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
    void shouldRefuseASessionBeyondThePrincipalsCap() {
        initialize("alice");
        initialize("alice");

        assertThat(initialize("alice").status()).isEqualTo(503);
    }

    @Test
    void shouldLeaveRoomForOtherPrincipalsWhenOnePrincipalIsAtItsCap() {
        initialize("alice");
        initialize("alice");

        assertThat(initialize("bob").status()).isEqualTo(200);
    }

    @Test
    void shouldFreeCapacityWhenAPrincipalDeletesASession() {
        initialize("alice");
        final var sessionId = initialize("alice").header("Mcp-Session-Id");
        server.delete("/mcp").header(USER_HEADER, "alice").header("Mcp-Session-Id", sessionId).send();

        assertThat(initialize("alice").status()).isEqualTo(200);
    }

    private TestResponse initialize(final String user) {
        final var body = JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "initialize")
            .put("params", JsonObject.builder().build())
            .build()
            .toJsonString();
        return server.post("/mcp")
            .header("Content-Type", "application/json")
            .header(USER_HEADER, user)
            .body(body)
            .send();
    }
}
