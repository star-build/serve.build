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
import build.base.json.JsonNumber;
import build.base.json.JsonObject;
import build.base.telemetry.Diagnostic;
import build.base.telemetry.Information;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.Warning;
import build.base.telemetry.foundation.AbstractTelemetryRecorder;
import build.serve.mcp.tool.ToolDef;
import build.serve.mcp.tool.ToolParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpServer#stdioLoop(java.io.InputStream, java.io.OutputStream)}.
 *
 * @author reed.vonredwitz
 * @since May-2026
 */
class McpStdioTests {

    private McpServer server;

    @BeforeEach
    void setUp() {
        final var location = ToolParam.string("location", "City name or zip code");
        server = McpServer.builder("test-server", "1.0.0")
            .tool(ToolDef.of("get_weather", "Get weather for a location")
                .param(location)
                .handle(args -> McpToolResult.text("Weather in " + location.extract(args) + ": sunny, 72°F")))
            .build();
    }

    @Test
    void shouldHandleInitialize() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertThat(response.getString("jsonrpc")).isEqualTo("2.0");
        assertThat(response.get("result").asObject().getString("protocolVersion")).isEqualTo("2025-03-26");
        assertThat(response.get("result").asObject().get("serverInfo").asObject().getString("name"))
            .isEqualTo("test-server");
    }

    @Test
    void shouldRespondWithSupportedVersionWhenClientRequestsUnknownVersion() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2099-01-01\"}}");
        assertThat(response.get("result").asObject().getString("protocolVersion")).isEqualTo("2025-03-26");
    }

    @Test
    void shouldHandleToolsList() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        final var tools = (JsonArray) response.get("result").asObject().get("tools");
        assertThat(tools.values()).hasSize(1);
        assertThat(tools.values().get(0).asObject().getString("name")).isEqualTo("get_weather");
    }

    @Test
    void shouldHandleToolsCall() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"get_weather\",\"arguments\":{\"location\":\"Berlin\"}}}");
        final var result = response.get("result").asObject();
        assertThat(result.get("isError").asBoolean().value()).isFalse();
        final var text = ((JsonArray) result.get("content")).values().get(0).asObject().getString("text");
        assertThat(text).isEqualTo("Weather in Berlin: sunny, 72°F");
    }

    @Test
    void shouldReturnErrorForUnknownTool() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}");
        assertThat(response.get("error").asObject().getString("message")).contains("Unknown tool");
    }

    @Test
    void shouldContinueAfterMalformedJsonLine() {
        final var lines = String.join("\n",
            "not valid json at all",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"
        ) + "\n";

        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream(lines), out);

        final var responses = out.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> !l.isBlank())
            .map(Json::parse)
            .toList();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).asObject().get("result").asObject()
            .get("tools")).isInstanceOf(JsonArray.class);
    }

    @Test
    void shouldSilentlyIgnoreNotifications() {
        final var out = new ByteArrayOutputStream();
        final var input = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
        server.stdioLoop(toStream(input), out);
        assertThat(out.toString(StandardCharsets.UTF_8).strip()).isEmpty();
    }

    @Test
    void shouldHandleMultipleRequestsInSequence() {
        final var lines = String.join("\n",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
        ) + "\n";

        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream(lines), out);

        final var responses = out.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> !l.isBlank())
            .map(l -> Json.parse(l).asObject())
            .toList();

        assertThat(responses).hasSize(2);
        assertThat(responses).anyMatch(r ->
            r.has("result") && r.get("result").asObject().has("protocolVersion")
                && r.get("result").asObject().getString("protocolVersion").equals("2025-03-26"));
        assertThat(responses).anyMatch(r ->
            r.has("result") && r.get("result").asObject().has("tools")
                && r.get("result").asObject().members().get("tools") instanceof JsonArray);
    }

    @Test
    void shouldUseLocalSessionId() {
        final var received = new java.util.concurrent.CopyOnWriteArrayList<ToolCallEvent>();
        server.toolCallEvents().subscribe(received::add);

        sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"get_weather\",\"arguments\":{\"location\":\"Paris\"}}}");

        assertThat(received).hasSize(2);
        assertThat(received.get(0).sessionId()).isEqualTo("local");
        assertThat(received.get(1).sessionId()).isEqualTo("local");
    }

    @Test
    void shouldRoundTripJsonThroughToolResult() {
        final var jsonServer = McpServer.builder("json-server", "1.0.0")
            .tool(ToolDef.of("list_users", "Returns a list of users")
                .handle(args -> McpToolResult.json(
                    JsonObject.builder()
                        .put("users", JsonArray.builder()
                            .add("alice")
                            .add("bob")
                            .build())
                        .build())))
            .build();

        try (var client = McpStdioClient.of(jsonServer)) {
            final var users = client.call("list_users", Map.of())
                .json().asObject().get("users").asArray();
            assertThat(users.values()).hasSize(2);
            assertThat(users.values().get(0).asString().value()).isEqualTo("alice");
            assertThat(users.values().get(1).asString().value()).isEqualTo("bob");
        }
    }

    /**
     * The default recorder writes info/diagnostic to System.out. In real stdio deployments,
     * System.out IS the protocol channel, so any non-JSON recorder output corrupts the NDJSON stream.
     * This test replicates that scenario by redirecting System.out before building the server,
     * then passing that same stream to stdioLoop.
     */
    @Test
    void shouldNotWriteNonJsonBytesToProtocolStream() {
        final var realOut = System.out;
        try {
            final var capturedOut = new ByteArrayOutputStream();
            final var capturedPrintStream = new java.io.PrintStream(capturedOut);
            System.setOut(capturedPrintStream);

            final var stdioServer = McpServer.builder("stdio-server", "1.0.0")
                .tool(ToolDef.of("ping", "ping").handle(args -> McpToolResult.text("pong")))
                .build();

            stdioServer.stdioLoop(
                toStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"),
                capturedPrintStream);

            capturedPrintStream.flush();
            final var output = capturedOut.toString(StandardCharsets.UTF_8);

            output.lines()
                .filter(l -> !l.isBlank())
                .forEach(line -> {
                    try {
                        Json.parse(line);
                    } catch (final RuntimeException e) {
                        org.junit.jupiter.api.Assertions.fail(
                            "Non-JSON line written to protocol stream: " + line);
                    }
                });
        } finally {
            System.setOut(realOut);
        }
    }

    @Test
    void shouldRecordDiagnosticsForEachDispatchedRequest() {
        final var capturing = new CapturingTelemetryRecorder();
        final var tracingServer = McpServer.builder("tracing-server", "1.0.0")
            .recorder(capturing)
            .build();

        final var out = new ByteArrayOutputStream();
        tracingServer.stdioLoop(
            toStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"),
            out);

        assertThat(capturing.diagnostics()).anyMatch(m -> m.contains("received: initialize"));
        assertThat(capturing.diagnostics()).anyMatch(m -> m.contains("sent response to: initialize"));
    }

    @Test
    void shouldRecordInfoOnCleanEof() {
        final var capturing = new CapturingTelemetryRecorder();
        final var tracingServer = McpServer.builder("tracing-server", "1.0.0")
            .recorder(capturing)
            .build();

        tracingServer.stdioLoop(
            toStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"),
            new ByteArrayOutputStream());

        assertThat(capturing.infos()).anyMatch(m -> m.contains("clean EOF"));
    }

    @Test
    void shouldRecordWarnOnUnexpectedIoException() {
        final var capturing = new CapturingTelemetryRecorder();
        final var tracingServer = McpServer.builder("tracing-server", "1.0.0")
            .recorder(capturing)
            .build();

        final var brokenStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset by peer");
            }
        };

        tracingServer.stdioLoop(brokenStream, new ByteArrayOutputStream());

        assertThat(capturing.warns()).anyMatch(m -> m.contains("unexpectedly"));
        assertThat(capturing.warnThrowables())
            .anyMatch(t -> t instanceof IOException && "connection reset by peer".equals(t.getMessage()));
    }

    @Test
    void shouldAnswerWithInternalErrorAndWarnWhenDispatchFails() {
        final var capturing = new CapturingTelemetryRecorder();
        final var failingServer = McpServer.builder("failing-server", "1.0.0")
            .recorder(capturing)
            .build();
        capturing.failOnDiagnostic();

        final var out = new ByteArrayOutputStream();
        failingServer.stdioLoop(
            toStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}\n"),
            out);

        final var response = Json.parse(out.toString(StandardCharsets.UTF_8).strip()).asObject();
        assertThat(response.get("error").asObject().get("code").asNumber().toNumber().intValue())
            .isEqualTo(-32603);
        assertThat(capturing.warns()).hasSize(1);
        assertThat(capturing.warns().getFirst()).contains("Failed to dispatch stdio request");
        assertThat(capturing.warnThrowables()).singleElement().satisfies(cause ->
            assertThat(cause).isInstanceOf(IllegalStateException.class).hasMessage("diagnostic sink failed"));
    }

    @Test
    void shouldWarnWithTheCauseWhenLineIsNotAnObject() {
        final var capturing = new CapturingTelemetryRecorder();
        final var tracingServer = McpServer.builder("tracing-server", "1.0.0")
            .recorder(capturing)
            .build();

        tracingServer.stdioLoop(toStream("42\n"), new ByteArrayOutputStream());

        assertThat(capturing.warns()).hasSize(1);
        assertThat(capturing.warns().getFirst()).contains("Failed to dispatch stdio request");
        assertThat(capturing.warnThrowables()).hasSize(1);
    }

    @Test
    void shouldWarnOnceWhenStdioOutputFails() {
        final var capturing = new CapturingTelemetryRecorder();
        final var tracingServer = McpServer.builder("tracing-server", "1.0.0")
            .recorder(capturing)
            .build();
        final var closedOutput = new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                throw new IOException("broken pipe");
            }
        };
        final var lines = String.join("\n",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{}}"
        ) + "\n";

        tracingServer.stdioLoop(toStream(lines), closedOutput);

        assertThat(capturing.warns()).filteredOn(m -> m.contains("stdio output failed")).hasSize(1);
    }

    @Test
    void shouldDispatchRequestsConcurrently() {
        final var slowServer = McpServer.builder("slow-server", "1.0.0")
            .tool(ToolDef.of("slow", "waits 200ms").handle(args -> {
                try {
                    Thread.sleep(200);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return McpToolResult.text("slow");
            }))
            .tool(ToolDef.of("fast", "returns immediately").handle(args -> McpToolResult.text("fast")))
            .build();

        // Send slow (id=1) then fast (id=2) in one batch. With concurrent dispatch fast
        // completes first, so its response should appear first in the output.
        final var lines = String.join("\n",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"slow\",\"arguments\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"fast\",\"arguments\":{}}}"
        ) + "\n";

        final var out = new ByteArrayOutputStream();
        slowServer.stdioLoop(toStream(lines), out);

        final var responses = out.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> !l.isBlank())
            .map(l -> Json.parse(l).asObject())
            .toList();

        assertThat(responses).hasSize(2);
        assertThat(((JsonNumber) responses.get(0).members().get("id")).toNumber().intValue()).isEqualTo(2);
        assertThat(((JsonNumber) responses.get(1).members().get("id")).toNumber().intValue()).isEqualTo(1);
    }

    @Test
    void shouldDeliverResourceNotificationsOverStdio() throws Exception {
        final var notifyServer = McpServer.builder("notify-server", "1.0.0")
            .tool(ToolDef.of("ping", "ping").handle(args -> McpToolResult.text("pong")))
            .build();

        final var clientToServer = new PipedOutputStream();
        final var serverToClient = new PipedOutputStream();
        final var serverIn = new PipedInputStream(clientToServer);
        final var clientIn = new PipedInputStream(serverToClient);

        final var loopThread = Thread.ofVirtual().start(
            () -> notifyServer.stdioLoop(serverIn, serverToClient));

        final var writer = new PrintWriter(clientToServer, true, StandardCharsets.UTF_8);
        final var reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

        // subscribe to a resource URI
        writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\","
            + "\"params\":{\"uri\":\"test://data\"}}");
        final var subResponse = Json.parse(reader.readLine()).asObject();
        assertThat(subResponse.members()).containsKey("result");

        // trigger notification from outside
        notifyServer.notifyResourceChanged("test://data");

        // the notification should arrive on the stdio channel
        final var notification = Json.parse(reader.readLine()).asObject();
        assertThat(notification.getString("method")).isEqualTo("notifications/resources/updated");
        assertThat(notification.get("params").asObject().getString("uri")).isEqualTo("test://data");

        writer.close();
        loopThread.join(5_000);
    }

    @Test
    void shouldNotDeliverNotificationAfterUnsubscribe() throws Exception {
        final var notifyServer = McpServer.builder("notify-server", "1.0.0")
            .tool(ToolDef.of("ping", "ping").handle(args -> McpToolResult.text("pong")))
            .build();

        final var clientToServer = new PipedOutputStream();
        final var serverToClient = new PipedOutputStream();
        final var serverIn = new PipedInputStream(clientToServer);
        final var clientIn = new PipedInputStream(serverToClient);

        final var loopThread = Thread.ofVirtual().start(
            () -> notifyServer.stdioLoop(serverIn, serverToClient));

        final var writer = new PrintWriter(clientToServer, true, StandardCharsets.UTF_8);
        final var reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\","
            + "\"params\":{\"uri\":\"test://data\"}}");
        assertThat(Json.parse(reader.readLine()).asObject().members()).containsKey("result");

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/unsubscribe\","
            + "\"params\":{\"uri\":\"test://data\"}}");
        assertThat(Json.parse(reader.readLine()).asObject().members()).containsKey("result");

        notifyServer.notifyResourceChanged("test://data");

        // send a ping to flush any pending output — if a spurious notification were queued
        // it would arrive before or instead of this response
        writer.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{}}");
        final var pingResponse = Json.parse(reader.readLine()).asObject();
        assertThat(pingResponse.members().get("id")).isInstanceOf(JsonNumber.class);
        assertThat(((JsonNumber) pingResponse.members().get("id")).toNumber().intValue()).isEqualTo(3);

        writer.close();
        loopThread.join(5_000);
    }

    @Test
    void shouldSilentlyDropNotificationsWhenStdioLoopIsNotRunning() {
        final var notifyServer = McpServer.builder("notify-server", "1.0.0").build();
        // stdioLoop has never been started — notifyResourceChanged must not throw
        notifyServer.notifyResourceChanged("test://data");
    }

    @Test
    void shouldSkipNotificationsWhenCorrelatingResponses() {
        final var notifyServer = McpServer.builder("skip-notify-server", "1.0.0")
            .tool(ToolDef.of("ping", "simple ping").handle(args -> McpToolResult.text("pong")))
            .build();

        try (var client = McpStdioClient.of(notifyServer)) {
            client.send("resources/subscribe", Map.of("uri", "test://x"));
            // Enqueue the notification synchronously from the test thread before sending the
            // next request. The FIFO writer queue guarantees the notification reaches the pipe
            // before the tools/call response, so client.send must skip it.
            notifyServer.notifyResourceChanged("test://x");
            final var response = client.send("tools/call",
                Map.of("name", "ping", "arguments", Map.of()));
            assertThat(response.get("result").asObject().get("content")).isInstanceOf(JsonArray.class);
        }
    }

    @Test
    void shouldAdvertiseLoggingCapability() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        final var capabilities = response.get("result").asObject().get("capabilities").asObject();
        assertThat(capabilities.members()).containsKey("logging");
    }

    @Test
    void shouldHandleLoggingSetLevel() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"logging/setLevel\","
            + "\"params\":{\"level\":\"info\"}}");
        assertThat(response.members()).containsKey("result");
    }

    @Test
    void shouldReturnErrorForInvalidLogLevel() {
        final var response = sendOne("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"logging/setLevel\","
            + "\"params\":{\"level\":\"bogus\"}}");
        assertThat(response.members()).containsKey("error");
        assertThat(response.get("error").asObject().getString("message")).contains("Invalid log level");
    }

    @Test
    void shouldDeliverLogNotificationsOverStdio() throws Exception {
        final var logServer = McpServer.builder("log-server", "1.0.0").build();

        final var clientToServer = new PipedOutputStream();
        final var serverToClient = new PipedOutputStream();
        final var serverIn = new PipedInputStream(clientToServer);
        final var clientIn = new PipedInputStream(serverToClient);

        final var loopThread = Thread.ofVirtual().start(
            () -> logServer.stdioLoop(serverIn, serverToClient));

        final var writer = new PrintWriter(clientToServer, true, StandardCharsets.UTF_8);
        final var reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"logging/setLevel\","
            + "\"params\":{\"level\":\"info\"}}");
        assertThat(Json.parse(reader.readLine()).asObject().members()).containsKey("result");

        logServer.log(McpLogLevel.info, "test", JsonObject.builder().put("msg", "hello").build());

        final var notification = Json.parse(reader.readLine()).asObject();
        assertThat(notification.getString("method")).isEqualTo("notifications/message");
        final var params = notification.get("params").asObject();
        assertThat(params.getString("level")).isEqualTo("info");
        assertThat(params.getString("logger")).isEqualTo("test");

        writer.close();
        loopThread.join(5_000);
    }

    @Test
    void shouldRespectLogLevelThreshold() throws Exception {
        final var logServer = McpServer.builder("log-server", "1.0.0")
            .tool(ToolDef.of("ping", "ping").handle(args -> McpToolResult.text("pong")))
            .build();

        final var clientToServer = new PipedOutputStream();
        final var serverToClient = new PipedOutputStream();
        final var serverIn = new PipedInputStream(clientToServer);
        final var clientIn = new PipedInputStream(serverToClient);

        final var loopThread = Thread.ofVirtual().start(
            () -> logServer.stdioLoop(serverIn, serverToClient));

        final var writer = new PrintWriter(clientToServer, true, StandardCharsets.UTF_8);
        final var reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

        writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"logging/setLevel\","
            + "\"params\":{\"level\":\"warning\"}}");
        assertThat(Json.parse(reader.readLine()).asObject().members()).containsKey("result");

        // debug is below the warning threshold — should be silently dropped
        logServer.log(McpLogLevel.debug, "test", null);

        // a ping should arrive with no preceding notification
        writer.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}");
        final var pingResponse = Json.parse(reader.readLine()).asObject();
        assertThat(pingResponse.members()).containsKey("result");
        assertThat(pingResponse.members()).doesNotContainKey("method");

        writer.close();
        loopThread.join(5_000);
    }

    @Test
    void shouldNotDeliverLogNotificationsWhenLevelNotSet() {
        final var logServer = McpServer.builder("log-server", "1.0.0").build();
        // no logging/setLevel called — log() must be a no-op
        final var out = new ByteArrayOutputStream();
        logServer.stdioLoop(
            toStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}\n"),
            out);
        // push after loop ends to verify no queue interaction
        logServer.log(McpLogLevel.debug, "test", null);
        final var lines = out.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(1);
        assertThat(Json.parse(lines.get(0)).asObject().members()).containsKey("result");
    }

    @Test
    void shouldHandleBatchRequest() {
        final var lines = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}},"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}]\n";

        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream(lines), out);

        final var arr = Json.parse(out.toString(StandardCharsets.UTF_8).strip()).asArray();
        assertThat(arr.values()).hasSize(2);
        final var ids = arr.values().stream()
            .map(v -> ((JsonNumber) v.asObject().members().get("id")).toNumber().intValue())
            .toList();
        assertThat(ids).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void shouldReturnInvalidRequestForBatchOverLimit() {
        final var items = String.join(",", Collections.nCopies(70_000, "{}"));

        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream("[" + items + "]\n"), out);

        final var response = Json.parse(out.toString(StandardCharsets.UTF_8).strip()).asObject();
        assertThat(response.get("error").asObject().get("code").asNumber().toNumber().intValue())
            .isEqualTo(-32600);
    }

    @Test
    void shouldHandleBatchWithNotificationsOnly() {
        final var lines = "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]\n";

        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream(lines), out);

        assertThat(out.toString(StandardCharsets.UTF_8).strip()).isEmpty();
    }

    @Test
    void shouldHandleBatchWithMixedRequestsAndNotifications() {
        final var lines = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}},"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]\n";

        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream(lines), out);

        final var arr = Json.parse(out.toString(StandardCharsets.UTF_8).strip()).asArray();
        assertThat(arr.values()).hasSize(1);
        assertThat(((JsonNumber) arr.values().get(0).asObject().members().get("id")).toNumber().intValue())
            .isEqualTo(1);
    }

    @Test
    void shouldDeliverProgressNotificationsOverStdio() {
        final var progressServer = McpServer.builder("progress-server", "1.0.0")
            .tool(ToolDef.of("multi_step", "runs three steps").handle(args -> {
                final var reporter = McpServer.PROGRESS_REPORTER.get();
                reporter.report(1, 3, "step one");
                reporter.report(2, 3, "step two");
                reporter.report(3, 3, "done");
                return McpToolResult.text("finished");
            }))
            .build();

        final var captured = new CopyOnWriteArrayList<JsonObject>();
        try (var client = McpStdioClient.of(progressServer, java.time.Duration.ofSeconds(5),
            notification -> {
                if ("notifications/progress".equals(notification.getString("method"))) {
                    captured.add(notification);
                }
            })) {
            final var result = client.send("tools/call",
                Map.of("name", "multi_step", "arguments", Map.of(),
                    "_meta", Map.of("progressToken", "tok-1")));
            assertThat(result.get("result").asObject().get("isError").asBoolean().value()).isFalse();
        }

        assertThat(captured).hasSize(3);
        assertThat(captured.get(0).get("params").asObject().getString("message")).isEqualTo("step one");
        assertThat(captured.get(1).get("params").asObject().getString("message")).isEqualTo("step two");
        assertThat(captured.get(2).get("params").asObject().getString("message")).isEqualTo("done");
        assertThat(captured.get(0).get("params").asObject().getString("progressToken")).isEqualTo("tok-1");
    }

    @Test
    void shouldNotBindProgressReporterWhenTokenAbsent() {
        final var bound = new java.util.concurrent.atomic.AtomicBoolean(false);
        final var probeServer = McpServer.builder("probe-server", "1.0.0")
            .tool(ToolDef.of("probe", "checks binding").handle(args -> {
                bound.set(McpServer.PROGRESS_REPORTER.isBound());
                return McpToolResult.text("ok");
            }))
            .build();

        try (var client = McpStdioClient.of(probeServer)) {
            client.send("tools/call", Map.of("name", "probe", "arguments", Map.of()));
        }

        assertThat(bound.get()).isFalse();
    }

    @Test
    void shouldBindProgressReporterWhenTokenPresent() {
        final var bound = new java.util.concurrent.atomic.AtomicBoolean(false);
        final var probeServer = McpServer.builder("probe-server", "1.0.0")
            .tool(ToolDef.of("probe", "checks binding").handle(args -> {
                bound.set(McpServer.PROGRESS_REPORTER.isBound());
                return McpToolResult.text("ok");
            }))
            .build();

        try (var client = McpStdioClient.of(probeServer)) {
            client.send("tools/call",
                Map.of("name", "probe", "arguments", Map.of(),
                    "_meta", Map.of("progressToken", "t")));
        }

        assertThat(bound.get()).isTrue();
    }

    private JsonObject sendOne(final String line) {
        final var out = new ByteArrayOutputStream();
        server.stdioLoop(toStream(line + "\n"), out);
        return Json.parse(out.toString(StandardCharsets.UTF_8).strip()).asObject();
    }

    private static ByteArrayInputStream toStream(final String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CapturingTelemetryRecorder extends AbstractTelemetryRecorder {

        private final List<Telemetry> captured = new CopyOnWriteArrayList<>();
        private volatile boolean failOnDiagnostic;

        CapturingTelemetryRecorder() {
            super(URI.create("test://capture"));
        }

        /**
         * Makes every later diagnostic throw, which fails request dispatch before it reaches a handler.
         */
        void failOnDiagnostic() {
            failOnDiagnostic = true;
        }

        @Override
        protected <T extends Telemetry> T record(final T telemetry) {
            if (failOnDiagnostic && telemetry instanceof Diagnostic) {
                throw new IllegalStateException("diagnostic sink failed");
            }
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

        List<Throwable> warnThrowables() {
            return captured.stream()
                .filter(t -> t instanceof Warning)
                .map(t -> ((Warning) t).throwable())
                .flatMap(Optional::stream)
                .toList();
        }
    }
}
