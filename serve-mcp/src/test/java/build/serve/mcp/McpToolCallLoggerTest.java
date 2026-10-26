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

import build.base.json.JsonNull;
import build.base.telemetry.Diagnostic;
import build.base.telemetry.Information;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.Warning;
import build.base.telemetry.foundation.AbstractTelemetryRecorder;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpToolCallLogger}.
 *
 * @author reed.vonredwitz
 * @since Jun-2026
 */
class McpToolCallLoggerTest {

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void shouldLogStartedAtDiagnosticLevel() {
        final var capturing = new CapturingTelemetryRecorder();
        final var logger = McpToolCallLogger.of(capturing);

        logger.onNext(ToolCallEvent.started(1L, "local", "my_tool", JsonNull.INSTANCE));

        assertThat(capturing.diagnostics()).hasSize(1);
        assertThat(capturing.diagnostics().getFirst()).contains("my_tool").contains("started");
    }

    @Test
    void shouldLogSucceededAtInfoLevel() {
        final var capturing = new CapturingTelemetryRecorder();
        final var logger = McpToolCallLogger.of(capturing);

        logger.onNext(ToolCallEvent.succeeded(
            1L, "local", "my_tool", JsonNull.INSTANCE,
            McpToolResult.text("ok"), Duration.ofMillis(42)));

        assertThat(capturing.infos()).hasSize(1);
        assertThat(capturing.infos().getFirst()).contains("my_tool").contains("42");
    }

    @Test
    void shouldLogSlowSucceededAtWarnLevel() {
        final var capturing = new CapturingTelemetryRecorder();
        final var logger = McpToolCallLogger.of(capturing, Duration.ofSeconds(1));

        logger.onNext(ToolCallEvent.succeeded(
            1L, "local", "slow_tool", JsonNull.INSTANCE,
            McpToolResult.text("ok"), Duration.ofSeconds(3)));

        assertThat(capturing.warns()).hasSize(1);
        assertThat(capturing.warns().getFirst()).contains("slow_tool").contains("slow");
        assertThat(capturing.infos()).isEmpty();
    }

    @Test
    void shouldLogFailedAtWarnLevelWithException() {
        final var capturing = new CapturingTelemetryRecorder();
        final var logger = McpToolCallLogger.of(capturing);
        final var cause = new RuntimeException("disk full");

        logger.onNext(ToolCallEvent.failed(
            1L, "local", "my_tool", JsonNull.INSTANCE, cause, Duration.ofMillis(10)));

        assertThat(capturing.warns()).hasSize(1);
        assertThat(capturing.warns().getFirst()).contains("my_tool").contains("failed");
    }

    @Test
    void shouldWireUpViaLogToolCallsBuilder() {
        final var capturing = new CapturingTelemetryRecorder();
        final var mcpServer = McpServer.builder("test", "1.0")
            .recorder(capturing)
            .logToolCalls()
            .tool(ToolDef.of("ping", "ping").handle(args -> McpToolResult.text("pong")))
            .build();
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        final var initialize = server.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}")
            .send();
        server.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", initialize.header("Mcp-Session-Id"))
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ping\",\"arguments\":{}}}")
            .send()
            .assertStatus(200);

        assertThat(capturing.diagnostics()).anyMatch(m -> m.contains("ping") && m.contains("started"));
        assertThat(capturing.infos()).anyMatch(m -> m.contains("ping") && m.contains("succeeded"));
    }

    @Test
    void shouldWireUpViaStdioWithLogToolCalls() {
        final var capturing = new CapturingTelemetryRecorder();
        final var mcpServer = McpServer.builder("test", "1.0")
            .recorder(capturing)
            .logToolCalls()
            .tool(ToolDef.of("ping", "ping").handle(args -> McpToolResult.text("pong")))
            .build();

        try (var client = McpStdioClient.of(mcpServer)) {
            client.call("ping", Map.of());
        }

        assertThat(capturing.diagnostics()).anyMatch(m -> m.contains("ping") && m.contains("started"));
        assertThat(capturing.infos()).anyMatch(m -> m.contains("ping") && m.contains("succeeded"));
    }

    private static final class CapturingTelemetryRecorder extends AbstractTelemetryRecorder {

        private final List<Telemetry> captured = new CopyOnWriteArrayList<>();

        CapturingTelemetryRecorder() {
            super(URI.create("test://capture"));
        }

        @Override
        protected <T extends Telemetry> T record(final T telemetry) {
            captured.add(telemetry);
            return telemetry;
        }

        @Override
        public TelemetryRecorderFactory factory() {
            return uri -> this;
        }

        List<String> diagnostics() {
            return captured.stream().filter(t -> t instanceof Diagnostic).map(Object::toString).toList();
        }

        List<String> infos() {
            return captured.stream().filter(t -> t instanceof Information).map(Object::toString).toList();
        }

        List<String> warns() {
            return captured.stream().filter(t -> t instanceof Warning).map(Object::toString).toList();
        }
    }
}
