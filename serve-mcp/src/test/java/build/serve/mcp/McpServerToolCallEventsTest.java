package build.serve.mcp;

import build.base.json.Json;
import build.base.json.JsonArray;
import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonString;
import build.base.json.JsonValue;
import build.base.telemetry.Telemetry;
import build.base.telemetry.TelemetryRecorderFactory;
import build.base.telemetry.Warning;
import build.base.telemetry.foundation.AbstractTelemetryRecorder;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestResponse;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link McpServer} tool-call event publishing.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
class McpServerToolCallEventsTest {

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Initializes a fresh session, then posts {@code body} on it.
     */
    private TestResponse postInSession(final String body) {
        final var initialize = server.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}")
            .send();
        return server.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", initialize.header("Mcp-Session-Id"))
            .body(body)
            .send();
    }

    @Test
    void shouldPublishStartedThenSucceededEventsOnSuccessfulInvocation() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        postInSession("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}}")
            .assertStatus(200);

        assertThat(received).hasSize(2);

        final var started = received.get(0);
        assertThat(started.phase()).isEqualTo(ToolCallEvent.Phase.STARTED);
        assertThat(started.toolName()).isEqualTo("echo");
        assertThat(started.arguments().asObject().getString("text")).isEqualTo("hello");
        assertThat(started.result()).isEmpty();
        assertThat(started.duration()).isEmpty();
        assertThat(started.error()).isEmpty();
        assertThat(started.timestamp()).isNotNull();

        final var succeeded = received.get(1);
        assertThat(succeeded.phase()).isEqualTo(ToolCallEvent.Phase.SUCCEEDED);
        assertThat(succeeded.toolName()).isEqualTo("echo");
        assertThat(succeeded.invocationId()).isEqualTo(started.invocationId());
        assertThat(succeeded.result()).isPresent();
        assertThat(succeeded.result().get().isError()).isFalse();
        assertThat(succeeded.duration()).isPresent();
        assertThat(succeeded.duration().get().toMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(succeeded.error()).isEmpty();
    }

    @Test
    void shouldPublishStartedThenFailedEventsOnToolFailure() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new ThrowingTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        postInSession("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"thrower\",\"arguments\":{}}}")
            .assertStatus(200);

        assertThat(received).hasSize(2);

        final var started = received.get(0);
        assertThat(started.phase()).isEqualTo(ToolCallEvent.Phase.STARTED);
        assertThat(started.toolName()).isEqualTo("thrower");

        final var failed = received.get(1);
        assertThat(failed.phase()).isEqualTo(ToolCallEvent.Phase.FAILED);
        assertThat(failed.invocationId()).isEqualTo(started.invocationId());
        assertThat(failed.result()).isEmpty();
        assertThat(failed.error()).isPresent();
        assertThat(failed.error().get()).isInstanceOf(RuntimeException.class);
        assertThat(failed.duration()).isPresent();
        assertThat(failed.duration().get().toMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void shouldNotAffectDispatchBehaviorWhenNoSubscribersAttached() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        final var response = postInSession("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"world\"}}}")
            .assertStatus(200);

        final var json = Json.parse(response.body()).asObject();
        assertThat(json.get("result").asObject().get("isError").asBoolean().value()).isFalse();
        assertThat(((JsonArray) json.get("result").asObject().get("content"))
            .values().getFirst().asObject().getString("text")).isEqualTo("echo: world");
    }

    @Test
    void shouldNotPublishEventForUnknownTool() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        postInSession("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"no-such-tool\",\"arguments\":{}}}")
            .assertStatus(200);

        assertThat(received).isEmpty();
    }

    @Test
    void shouldCarrySessionIdOnEvents() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);

        try (var client = McpTestClient.start(mcpServer)) {
            client.initialize();
            client.call("echo", java.util.Map.of("text", "hi"));
        }

        assertThat(received).hasSize(2);
        assertThat(received.get(0).sessionId()).isNotNull().isNotBlank().isNotEqualTo("local");
        assertThat(received.get(1).sessionId()).isEqualTo(received.get(0).sessionId());
    }

    @Test
    void shouldNotDispatchToolCallWhenNoSessionHeader() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        server.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"no-session\"}}}")
            .send()
            .assertStatus(400);

        assertThat(received).isEmpty();
    }

    @Test
    void shouldFanOutToMultipleSubscribers() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        final var first = new CopyOnWriteArrayList<ToolCallEvent>();
        final var second = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(first::add);
        mcpServer.toolCallEvents().subscribe(second::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        postInSession("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"fan-out\"}}}")
            .assertStatus(200);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(first.get(0).toolName()).isEqualTo("echo");
        assertThat(second.get(0).toolName()).isEqualTo("echo");
        assertThat(first.get(0).invocationId()).isEqualTo(second.get(0).invocationId());
    }

    @Test
    void shouldAssignDistinctInvocationIdsToConsecutiveCalls() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new EchoTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        for (int i = 0; i < 2; i++) {
            postInSession("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}}}")
                .assertStatus(200);
        }

        assertThat(received).hasSize(4);
        final var firstId = received.get(0).invocationId();
        final var secondId = received.get(2).invocationId();
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(received.get(1).invocationId()).isEqualTo(firstId);
        assertThat(received.get(3).invocationId()).isEqualTo(secondId);
    }

    @Test
    void shouldPublishFailedEventWhenToolThrowsError() {
        final var mcpServer = McpServer.builder("test", "1.0")
            .tool(new ErrorTool())
            .build();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        // an Error is not converted to a tool result, so the exchange is aborted
        assertThatThrownBy(() -> postInSession("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"error-thrower\",\"arguments\":{}}}"))
            .isInstanceOf(UncheckedIOException.class);

        // the HTTP client may retry the aborted POST, so more than one invocation can be recorded
        assertThat(received).hasSizeGreaterThanOrEqualTo(2);
        assertThat(received.get(0).phase()).isEqualTo(ToolCallEvent.Phase.STARTED);
        final var failed = received.get(1);
        assertThat(failed.phase()).isEqualTo(ToolCallEvent.Phase.FAILED);
        assertThat(failed.invocationId()).isEqualTo(received.get(0).invocationId());
        assertThat(failed.error()).isPresent();
        assertThat(failed.error().get()).isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldWarnWhenSubscriberThrowsAndStillServeTheCall() {
        final var capturing = new CapturingTelemetryRecorder();
        final var mcpServer = McpServer.builder("test", "1.0")
            .recorder(capturing)
            .tool(new EchoTool())
            .build();
        mcpServer.toolCallEvents().subscribe(event -> {
            throw new IllegalStateException("subscriber boom");
        });
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        postInSession("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}}")
            .assertStatus(200);

        assertThat(capturing.warns()).hasSize(1);
        assertThat(capturing.warns().getFirst())
            .contains("tool-call event subscriber")
            .contains("terminated");
        assertThat(capturing.warnThrowables()).singleElement().satisfies(cause ->
            assertThat(cause).isInstanceOf(IllegalStateException.class).hasMessage("subscriber boom"));
    }

    @Test
    void shouldTerminateThrowingSubscriberWithoutAffectingOthers() {
        final var capturing = new CapturingTelemetryRecorder();
        final var mcpServer = McpServer.builder("test", "1.0")
            .recorder(capturing)
            .tool(new EchoTool())
            .build();
        final var throwingCalls = new AtomicInteger();
        final var received = new CopyOnWriteArrayList<ToolCallEvent>();
        mcpServer.toolCallEvents().subscribe(event -> {
            throwingCalls.incrementAndGet();
            throw new IllegalStateException("subscriber boom");
        });
        mcpServer.toolCallEvents().subscribe(received::add);
        server = TestServer.of(RouterBuilder.create().route("/mcp", mcpServer.handler()).build());

        postInSession("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}}")
            .assertStatus(200);

        // the STARTED event terminated the throwing subscriber, so it never saw SUCCEEDED
        assertThat(throwingCalls.get()).isEqualTo(1);
        assertThat(capturing.warns()).hasSize(1);
        assertThat(received).hasSize(2);
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

    private static final class EchoTool implements McpTool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes the input text";
        }

        @Override
        public JsonObject inputSchema() {
            return JsonObject.builder().build();
        }

        @Override
        public McpToolResult call(final JsonValue arguments) {
            final var text = arguments.asObject().members().getOrDefault("text",
                JsonNull.INSTANCE);
            final var textStr = text instanceof JsonString s ? s.value() : "";
            return McpToolResult.text("echo: " + textStr);
        }
    }

    private static final class ErrorTool implements McpTool {

        @Override
        public String name() {
            return "error-thrower";
        }

        @Override
        public String description() {
            return "Throws an Error (not Exception)";
        }

        @Override
        public JsonObject inputSchema() {
            return JsonObject.builder().build();
        }

        @Override
        public McpToolResult call(final JsonValue arguments) {
            throw new AssertionError("fatal error");
        }
    }

    private static final class ThrowingTool implements McpTool {

        @Override
        public String name() {
            return "thrower";
        }

        @Override
        public String description() {
            return "Always throws an exception";
        }

        @Override
        public JsonObject inputSchema() {
            return JsonObject.builder().build();
        }

        @Override
        public McpToolResult call(final JsonValue arguments) {
            throw new RuntimeException("tool failure");
        }
    }
}
