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
import build.serve.foundation.routing.RouterBuilder;
import build.serve.mcp.tool.ToolDef;
import build.serve.testing.TestResponse;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the server-wide bounds on in-flight requests and on stdio input.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpRequestLimitTests {

    private static final int MAX_CONCURRENT = 2;

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}";

    private final Semaphore entered = new Semaphore(0);
    private final CountDownLatch release = new CountDownLatch(1);
    private TestServer server;

    @BeforeEach
    void setUp() {
        final var mcpServer = McpServer.builder("request-limit-server", "1.0.0")
            .maxConcurrentRequests(MAX_CONCURRENT)
            .tool(ToolDef.of("block", "Blocks until released")
                .handle(args -> {
                    entered.release();
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return McpToolResult.text("done");
                }))
            .build();
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.close();
    }

    @Test
    @Timeout(30)
    void shouldRefuseRequestsBeyondTheConcurrencyLimit() throws Exception {
        final var sessionId = initialize();

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holdRequests(executor, sessionId, MAX_CONCURRENT);
            try {
                assertThat(post(sessionId, "ping", "{}").status()).isEqualTo(503);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(30)
    void shouldCountDeleteAgainstTheLimitButNotGet() throws Exception {
        final var sessionId = initialize();

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holdRequests(executor, sessionId, MAX_CONCURRENT);
            try {
                assertThat(server.delete("/mcp").header("Mcp-Session-Id", sessionId).send().status())
                    .isEqualTo(503);
                // a GET without a session is answered by the handler itself, so it got past the limit
                assertThat(server.get("/mcp").send().status()).isEqualTo(400);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(30)
    void shouldCountEachBatchItemAgainstTheConcurrencyLimit() throws Exception {
        final var sessionId = initialize();

        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holdRequests(executor, sessionId, 1);
            try {
                assertThat(postBatch(sessionId, 2).status()).isEqualTo(503);
                assertThat(postBatch(sessionId, 1).status()).isEqualTo(200);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(30)
    void shouldRunABatchLargerThanTheLimitAndReleaseEveryPermit() {
        final var sessionId = initialize();

        // a leaked permit per batch would leave too few for the next one within a few rounds
        for (int i = 0; i < MAX_CONCURRENT * 3; i++) {
            final var response = postBatch(sessionId, MAX_CONCURRENT + 1).assertStatus(200);
            assertThat(Json.parse(response.body()).asArray().values()).hasSize(MAX_CONCURRENT + 1);
        }
        assertThat(post(sessionId, "ping", "{}").status()).isEqualTo(200);
    }

    @Test
    @Timeout(30)
    void shouldAcceptRequestsAgainOnceInFlightRequestsFinish() throws Exception {
        final var sessionId = initialize();

        for (int i = 0; i < MAX_CONCURRENT * 3; i++) {
            assertThat(post(sessionId, "ping", "{}").status()).isEqualTo(200);
        }
    }

    @Test
    @Timeout(30)
    void shouldSkipAStdioLineLongerThanTheLimitAndKeepServing() {
        final var mcpServer = McpServer.builder("stdio-limit-server", "1.0.0")
            .maxStdioLineLength(1_000)
            .build();
        final var oversized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
            + "x".repeat(10_000) + "\"}}";
        final var ping = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}";
        final var in = new ByteArrayInputStream((oversized + "\n" + ping + "\n").getBytes(StandardCharsets.UTF_8));
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(in, out);

        final var output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":2");
        assertThat(output).doesNotContain("\"id\":1");
    }

    @Test
    @Timeout(30)
    void shouldKeepStdioToolCallsWithinTheConcurrencyLimit() {
        final var peak = new AtomicInteger();
        final var mcpServer = trackingServer(MAX_CONCURRENT, peak);
        final var lines = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            lines.append(trackCall(i)).append('\n');
        }
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(toStream(lines.toString()), out);

        assertThat(responseLines(out)).hasSize(10);
        assertThat(peak.get()).isEqualTo(MAX_CONCURRENT);
    }

    @Test
    @Timeout(30)
    void shouldCountEachStdioBatchItemAgainstTheConcurrencyLimit() {
        final var peak = new AtomicInteger();
        final var mcpServer = trackingServer(4, peak);
        final var batch = "[" + trackCall(1) + "," + trackCall(2) + "]";
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(toStream(String.join("\n", batch, batch, batch) + "\n"), out);

        assertThat(responseLines(out)).hasSize(3);
        assertThat(peak.get()).isBetween(2, 4);
    }

    @Test
    @Timeout(30)
    void shouldAcceptAStdioLineOfExactlyTheLimit() {
        final var line = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}";
        final var mcpServer = McpServer.builder("stdio-limit-server", "1.0.0")
            .maxStdioLineLength(line.length())
            .build();
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(toStream(line + "\n"), out);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"id\":2");
    }

    @Test
    @Timeout(30)
    void shouldDiscardAnOversizedFinalStdioLineWithoutANewline() {
        final var mcpServer = McpServer.builder("stdio-limit-server", "1.0.0")
            .maxStdioLineLength(1_000)
            .build();
        final var ping = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}";
        final var oversized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
            + "x".repeat(10_000) + "\"}}";
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(toStream(ping + "\n" + oversized), out);

        final var output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":2");
        assertThat(output).doesNotContain("\"id\":1");
    }

    @Test
    @Timeout(30)
    void shouldAcceptCrlfTerminatedStdioLinesAtExactlyTheLimit() {
        final var line = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}";
        final var mcpServer = McpServer.builder("stdio-limit-server", "1.0.0")
            .maxStdioLineLength(line.length())
            .build();
        final var out = new ByteArrayOutputStream();

        mcpServer.stdioLoop(toStream(line + "\r\n"), out);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"id\":2");
    }

    /**
     * Starts {@code count} {@code block} calls and returns once all of them are inside the tool.
     */
    private void holdRequests(final ExecutorService executor,
                              final String sessionId,
                              final int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            executor.submit(() -> post(sessionId, "tools/call", "{\"name\":\"block\",\"arguments\":{}}"));
        }
        assertThat(entered.tryAcquire(count, 10, TimeUnit.SECONDS)).isTrue();
    }

    private TestResponse postBatch(final String sessionId, final int size) {
        return server.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", sessionId)
            .body("[" + String.join(",", Collections.nCopies(size, PING)) + "]")
            .send();
    }

    /**
     * A server whose {@code track} tool holds briefly, recording in {@code peak} the most calls
     * that were ever running at once.
     */
    private static McpServer trackingServer(final int maxConcurrentRequests, final AtomicInteger peak) {
        final var running = new AtomicInteger();
        return McpServer.builder("tracking-server", "1.0.0")
            .maxConcurrentRequests(maxConcurrentRequests)
            .tool(ToolDef.of("track", "Records how many calls overlap")
                .handle(args -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running.decrementAndGet();
                    return McpToolResult.text("done");
                }))
            .build();
    }

    private static String trackCall(final int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"track\",\"arguments\":{}}}";
    }

    private static ByteArrayInputStream toStream(final String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> responseLines(final ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
    }

    private String initialize() {
        final var response = post(null, "initialize", "{}");
        assertThat(response.status()).isEqualTo(200);
        return response.header("Mcp-Session-Id");
    }

    private TestResponse post(final String sessionId, final String method, final String params) {
        final var body = JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", Json.parse(params))
            .build()
            .toJsonString();
        var request = server.post("/mcp").header("Content-Type", "application/json");
        if (sessionId != null) {
            request = request.header("Mcp-Session-Id", sessionId);
        }
        return request.body(body).send();
    }
}
