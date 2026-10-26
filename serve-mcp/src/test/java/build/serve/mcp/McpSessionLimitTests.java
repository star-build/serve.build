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
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the limits on MCP sessions hold under concurrent {@code initialize} calls.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpSessionLimitTests {

    private static final int MAX_SESSIONS = 5;
    private static final int CALLERS = 100;
    private static final String INITIALIZE = JsonObject.builder()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "initialize")
        .put("params", JsonObject.builder().build())
        .build()
        .toJsonString();

    private TestServer server;

    @BeforeEach
    void setUp() {
        final var mcpServer = McpServer.builder("limit-server", "1.0.0")
            .maxSessions(MAX_SESSIONS)
            .build();
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @Timeout(30)
    void shouldReleaseTheReservedSessionWhenInitializeGetsNoResponse() {
        // an initialize without an id is a notification, answered with 202 and no session
        final var notification = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{}}";

        for (int i = 0; i < MAX_SESSIONS * 3; i++) {
            server.post("/mcp")
                .header("Content-Type", "application/json")
                .body(notification)
                .send()
                .assertStatus(202);
        }

        server.post("/mcp")
            .header("Content-Type", "application/json")
            .body(INITIALIZE)
            .send()
            .assertStatus(200);
    }

    @RepeatedTest(5)
    @Timeout(30)
    void shouldNotExceedMaxSessionsUnderConcurrentInitialize() throws Exception {
        final var start = new CountDownLatch(1);
        final var body = INITIALIZE;

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < CALLERS; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    final var response = server.post("/mcp")
                        .header("Content-Type", "application/json")
                        .body(body)
                        .send();
                    return response.header("Mcp-Session-Id") != null;
                }));
            }
            start.countDown();

            int created = 0;
            for (final var result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    created++;
                }
            }

            assertThat(created).isEqualTo(MAX_SESSIONS);
        }
    }
}
