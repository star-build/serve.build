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

import build.base.flow.Publisher;
import build.base.json.Json;
import build.base.json.JsonArray;
import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.base.telemetry.foundation.SystemTelemetryRecorder;
import build.serve.foundation.Exchange;
import build.serve.foundation.Handler;
import build.serve.foundation.context.RequestContext;
import build.serve.sse.SseEvent;
import build.serve.sse.SseUpgrade;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * An MCP (Model Context Protocol) server that exposes tools via JSON-RPC 2.0 over HTTP.
 *
 * <p><strong>Authentication:</strong> {@code McpServer} has no built-in authentication.
 * Any deployment reachable by untrusted clients must be wrapped with an authentication
 * layer — for example {@code serve-auth}'s {@code AuthMiddleware} — before the handler
 * returned by {@link #handler()} is registered. Standalone use without such a wrapper
 * is an open server.
 *
 * <p><strong>Lifecycle:</strong> a server runs a background thread that evicts idle sessions.
 * {@link #close()} stops it and ends every open session's SSE stream.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class McpServer implements AutoCloseable {

    /**
     * ScopedValue bound to the current MCP session ID for the duration of each tool call.
     * Bound to {@code "local"} for stdio. For HTTP it is bound to the session id issued at
     * {@code initialize}; requests without a session are rejected, so it is never a value shared
     * between callers.
     */
    public static final ScopedValue<String> SESSION_ID = ScopedValue.newInstance();

    /**
     * ScopedValue bound to a {@link ProgressReporter} when the client's {@code tools/call}
     * request included a {@code _meta.progressToken}. Tools should check
     * {@link ScopedValue#isBound()} before calling the reporter.
     */
    public static final ScopedValue<ProgressReporter> PROGRESS_REPORTER = ScopedValue.newInstance();

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final SessionRegistry sessions;
    private final RequestGuard guard;
    private final RpcDispatcher dispatcher;
    private final StdioTransport stdio;
    private final Semaphore inFlightRequests;

    private McpServer(final Builder builder) {
        this.sessions = new SessionRegistry(builder.recorder, builder.maxSessions,
            builder.maxSessionsPerPrincipal, builder.sessionIdleTimeout);
        this.guard = new RequestGuard(builder.recorder, builder.allowedOrigins, builder.allowedHosts,
            builder.allowUnauthenticatedRemote);
        this.dispatcher = new RpcDispatcher(
            new McpServerInfo(builder.name, builder.version),
            builder.instructions,
            McpCatalog.of(builder.tools, builder.resources, builder.templates, builder.prompts),
            sessions,
            builder.recorder,
            builder.maxBatchSize,
            builder.maxConcurrentRequests,
            builder.maxSubscriptionsPerSession);
        this.inFlightRequests = new Semaphore(builder.maxConcurrentRequests);
        this.stdio = new StdioTransport(builder.recorder, sessions, dispatcher, inFlightRequests,
            builder.maxStdioLineLength);
    }

    /**
     * Stops the session reaper and ends every open session, closing their SSE streams. Safe to
     * call more than once. The server must not be used afterwards.
     */
    @Override
    public void close() {
        sessions.close();
    }

    // --- HTTP transport ---

    /**
     * Returns a serve.build {@link Handler} that handles the MCP endpoint.
     * <p>
     * Implements the MCP streamable HTTP transport (spec 2025-03-26). POST requests receive either
     * a JSON response or an SSE stream depending on the client's {@code Accept} header. Session IDs
     * are issued during {@code initialize} and validated on subsequent requests.
     * <p>
     * At most {@link Builder#maxConcurrentRequests(int)} JSON-RPC requests are handled at once, each
     * item of a batch counting as one; further POST and DELETE requests get 503. Open SSE streams are
     * not counted, as they are bounded by the session caps.
     *
     * @return the handler
     */
    public Handler handler() {
        return exchange -> {
            if ("GET".equalsIgnoreCase(exchange.request().method())) {
                route(exchange);
                return;
            }
            if (!inFlightRequests.tryAcquire()) {
                exchange.response().status(503).send("Too many concurrent requests");
                return;
            }
            try {
                route(exchange);
            } finally {
                inFlightRequests.release();
            }
        };
    }

    private void route(final Exchange exchange) throws Exception {
        if (!guard.admit(exchange)) {
            return;
        }
        final var method = exchange.request().method();
        if ("GET".equalsIgnoreCase(method)) {
            openStream(exchange);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            exchange.request().header(SESSION_HEADER).ifPresent(sessions::terminate);
            exchange.response().status(200).send("");
        } else if ("POST".equalsIgnoreCase(method)) {
            post(exchange);
        } else {
            exchange.response().status(405).send("Method Not Allowed");
        }
    }

    private void openStream(final Exchange exchange) throws Exception {
        final var sessionId = exchange.request().header(SESSION_HEADER).orElse(null);
        if (sessionId == null) {
            exchange.response().status(400).send("Mcp-Session-Id header required");
            return;
        }
        final var state = sessions.owned(sessionId);
        if (state == null) {
            exchange.response().status(404).send("Session not found");
            return;
        }
        SseUpgrade.sse(emitter -> sessions.serveStream(sessionId, state, emitter)).handle(exchange);
    }

    private void post(final Exchange exchange) {
        final var accept = exchange.request().header("Accept");
        if (accept.isPresent() && !canServe(accept.get())) {
            exchange.response().status(406).send("Not Acceptable");
            return;
        }

        final var sessionId = exchange.request().header(SESSION_HEADER);
        final var body = exchange.request().bodyAsString();
        final JsonValue parsed;
        try {
            parsed = Json.parse(body);
        } catch (final RuntimeException e) {
            refuse(exchange, McpJson.parseError());
            return;
        }

        if (parsed instanceof JsonArray batch) {
            postBatch(exchange, batch, sessionId);
        } else if (parsed instanceof JsonObject request) {
            postRequest(exchange, request, sessionId);
        } else {
            refuse(exchange, McpJson.invalidRequest());
        }
    }

    private static void refuse(final Exchange exchange, final JsonObject error) {
        exchange.response()
            .status(400)
            .header("Content-Type", "application/json")
            .send(error.toJsonString().getBytes(StandardCharsets.UTF_8));
    }

    private void postBatch(final Exchange exchange, final JsonArray batch, final Optional<String> sessionId) {
        if (sessionId.isEmpty()) {
            exchange.response().status(400).send("Mcp-Session-Id header required");
            return;
        }
        if (sessions.owned(sessionId.get()) == null) {
            exchange.response().status(404).send("Session not found");
            return;
        }
        // the handler already holds one permit for the request itself
        final var extraPermits = dispatcher.permitsFor(batch) - 1;
        if (!inFlightRequests.tryAcquire(extraPermits)) {
            exchange.response().status(503).send("Too many concurrent requests");
            return;
        }
        final Optional<JsonValue> response;
        try {
            response = dispatcher.dispatchBatch(batch, sessionId.get());
        } finally {
            inFlightRequests.release(extraPermits);
        }
        if (response.isEmpty()) {
            exchange.response().status(202).send("");
            return;
        }
        exchange.response()
            .header("Content-Type", "application/json")
            .send(response.get().toJsonString().getBytes(StandardCharsets.UTF_8));
    }

    private void postRequest(final Exchange exchange, final JsonObject request, final Optional<String> sessionId) {
        final var isInitialize = "initialize".equals(McpJson.string(request, "method"));

        if (isInitialize && sessionId.isPresent()) {
            exchange.response().status(400).send("Bad Request");
            return;
        }
        if (!isInitialize && sessionId.isEmpty()) {
            exchange.response().status(400).send("Mcp-Session-Id header required");
            return;
        }
        if (sessionId.isPresent() && sessions.owned(sessionId.get()) == null) {
            exchange.response().status(404).send("Session not found");
            return;
        }

        final var newSessionId = isInitialize ? sessions.reserve() : null;
        if (isInitialize && newSessionId == null) {
            exchange.response().status(503).send("Too many sessions");
            return;
        }

        final Optional<JsonObject> response;
        try {
            response = dispatcher.dispatch(request, sessionId.orElse(null));
        } catch (final RuntimeException | Error e) {
            sessions.release(newSessionId);
            throw e;
        }

        if (response.isEmpty()) {
            sessions.release(newSessionId);
            exchange.response().status(202).send("");
            return;
        }

        if (newSessionId != null) {
            exchange.response().header(SESSION_HEADER, newSessionId);
        }

        final var json = response.get().toJsonString();
        if (acceptsSse(exchange)) {
            exchange.response()
                .status(200)
                .header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .send(SseEvent.of("message", json).serialize().getBytes(StandardCharsets.UTF_8));
        } else {
            exchange.response()
                .header("Content-Type", "application/json")
                .send(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean acceptsSse(final Exchange exchange) {
        return exchange.request().header("Accept")
            .map(a -> a.contains("text/event-stream"))
            .orElse(false);
    }

    private static boolean canServe(final String accept) {
        return accept.contains("application/json")
            || accept.contains("text/event-stream")
            || accept.contains("*/*");
    }

    // --- Stdio transport ---

    /**
     * Runs the MCP stdio transport, reading newline-delimited JSON-RPC from {@code in} and
     * writing newline-delimited JSON responses to {@code out}. Blocks until {@code in} reaches EOF.
     * <p>
     * All tool calls use the session ID {@code "local"}.
     *
     * <p><strong>Stdout pollution:</strong> {@code out} is the protocol channel — any bytes written
     * to it outside this method (e.g. a stray {@code System.out.println} in a tool implementation
     * or transitive library) will silently corrupt the NDJSON stream from the client's point of
     * view. At process start, before calling this method, redirect {@code System.out} to
     * {@code System.err} and pass the original {@code System.out} as {@code out} here so that
     * only this method ever writes to the protocol channel.
     *
     * @param in  the input stream (e.g. {@code System.in})
     * @param out the output stream (e.g. {@code System.out} — see stdout pollution note above)
     */
    public void stdioLoop(final InputStream in,
                          final OutputStream out) {
        stdio.run(in, out);
    }

    // --- Events and notifications ---

    /**
     * Returns a {@link Publisher} of {@link ToolCallEvent}s.
     *
     * <p>Subscribers receive one event per tool invocation, published after the
     * tool returns (or throws). Subscribers are observers only — they do not
     * participate in dispatch and cannot alter the result seen by the caller.
     *
     * @return the tool-call event publisher
     */
    public Publisher<ToolCallEvent> toolCallEvents() {
        return dispatcher.toolCallEvents();
    }

    /**
     * Sends a {@code notifications/resources/updated} event to all sessions subscribed to
     * the given URI. Call this whenever the content of a resource changes.
     *
     * @param uri the URI of the changed resource
     */
    public void notifyResourceChanged(final String uri) {
        sessions.broadcast(state -> state.subscriptions.contains(uri),
            McpJson.notification("notifications/resources/updated", JsonObject.builder().put("uri", uri).build()));
    }

    /**
     * Pushes a {@code notifications/prompts/list_changed} event to all connected clients.
     * Call this whenever the set of available prompts changes at runtime.
     */
    public void notifyPromptsChanged() {
        sessions.broadcast(state -> true, McpJson.notification("notifications/prompts/list_changed"));
    }

    /**
     * Pushes a {@code notifications/message} log event to each connected client whose
     * configured log level threshold is met. Has no effect on clients that have not called
     * {@code logging/setLevel}, or where {@code level} does not meet their threshold.
     *
     * @param level  the severity level
     * @param logger an optional logger name (may be null or empty)
     * @param data   the log data payload (may be null)
     */
    public void log(final McpLogLevel level, final String logger, final JsonValue data) {
        final var params = JsonObject.builder().put("level", level.name());
        if (logger != null && !logger.isEmpty()) {
            params.put("logger", logger);
        }
        if (data != null && !(data instanceof JsonNull)) {
            params.put("data", data);
        }
        sessions.broadcast(state -> {
            final var threshold = state.logLevel;
            return threshold != null && level.meets(threshold);
        }, McpJson.notification("notifications/message", params.build()));
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @param name    the server name
     * @param version the server version
     * @return the builder
     */
    public static Builder builder(final String name,
                                  final String version) {
        return new Builder(name, version);
    }

    /**
     * A builder for {@link McpServer}.
     *
     * @author reed.vonredwitz
     * @since Mar-2026
     */
    public static final class Builder {

        private final String name;
        private final String version;
        private String instructions;
        private final List<McpTool> tools = new ArrayList<>();
        private final List<McpResource> resources = new ArrayList<>();
        private final List<McpResourceTemplate> templates = new ArrayList<>();
        private final List<McpPrompt> prompts = new ArrayList<>();
        private final HashSet<String> allowedOrigins = new HashSet<>();
        private final HashSet<String> allowedHosts = new HashSet<>();
        private int maxSessions = 10_000;
        private int maxSubscriptionsPerSession = 1_000;
        private int maxBatchSize = 1_000;
        private int maxSessionsPerPrincipal = 100;
        private int maxConcurrentRequests = 1_000;
        private int maxStdioLineLength = 10 * 1024 * 1024;
        private Duration sessionIdleTimeout = Duration.ofHours(1);
        private TelemetryRecorder recorder;
        private boolean logToolCalls = false;
        private boolean allowUnauthenticatedRemote = false;

        private Builder(final String name, final String version) {
            this.name = name;
            this.version = version;
            this.recorder = PrintStreamTelemetryRecorder.of(URI.create("serve://mcp/" + name), System.err, System.err);
        }

        /**
         * Sets optional instructions for the client describing how to interact with this server.
         *
         * @param instructions the instructions string
         * @return this builder
         */
        public Builder instructions(final String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * Adds a tool to this server.
         *
         * @param tool the tool
         * @return this builder
         */
        public Builder tool(final McpTool tool) {
            tools.add(tool);
            return this;
        }

        /**
         * Adds a resource to this server.
         *
         * @param resource the resource
         * @return this builder
         */
        public Builder resource(final McpResource resource) {
            resources.add(resource);
            return this;
        }

        /**
         * Adds a URI-template-based resource to this server.
         *
         * @param template the resource template
         * @return this builder
         */
        public Builder template(final McpResourceTemplate template) {
            templates.add(template);
            return this;
        }

        /**
         * Adds a prompt template to this server.
         *
         * @param prompt the prompt
         * @return this builder
         */
        public Builder prompt(final McpPrompt prompt) {
            prompts.add(prompt);
            return this;
        }

        /**
         * Adds an allowed {@code Origin} header value for DNS rebinding / cross-site protection.
         *
         * <p>Origin enforcement is fail-closed by default: any request carrying an
         * {@code Origin} header is rejected with HTTP 403 unless it matches an origin
         * registered here — an empty allowlist rejects every browser-originated request.
         * Requests without an {@code Origin} header (e.g. non-browser clients, the Anthropic
         * API's remote MCP client) are always allowed through this check.
         *
         * @param origin the allowed origin (e.g. {@code "https://app.example.com"})
         * @return this builder
         */
        public Builder allowOrigin(final String origin) {
            allowedOrigins.add(origin);
            return this;
        }

        /**
         * Adds an allowed {@code Host} header value, for servers deployed behind a hostname
         * other than loopback (e.g. a remote MCP server on {@code mcp.example.com}).
         *
         * <p>Host enforcement is fail-closed by default: with no hosts registered here, only
         * requests whose {@code Host} header names a loopback address ({@code localhost},
         * {@code 127.0.0.1}, {@code ::1}) are accepted — every other request gets HTTP 403.
         * Once at least one host is registered, only those exact hosts (port excluded) are
         * accepted; loopback is no longer implicitly allowed.
         *
         * @param host the allowed hostname, without port (e.g. {@code "mcp.example.com"})
         * @return this builder
         */
        public Builder allowHost(final String host) {
            allowedHosts.add(host);
            return this;
        }

        /**
         * Sets the maximum number of concurrent sessions (default 10,000).
         * {@code initialize} requests are rejected with 503 when the cap is reached.
         *
         * @param maxSessions the cap; must be positive
         * @return this builder
         */
        public Builder maxSessions(final int maxSessions) {
            if (maxSessions <= 0) {
                throw new IllegalArgumentException("maxSessions must be positive");
            }
            this.maxSessions = maxSessions;
            return this;
        }

        /**
         * Sets the idle timeout after which inactive sessions are reaped (default 1 hour).
         * A background thread sweeps expired sessions at half the idle timeout interval, kept
         * between 10 milliseconds and 5 minutes. Activity on a session (any JSON-RPC request or SSE
         * connection) resets the idle clock.
         *
         * @param idleTimeout the idle duration; must be positive
         * @return this builder
         */
        public Builder sessionIdleTimeout(final Duration idleTimeout) {
            if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException("sessionIdleTimeout must be positive");
            }
            this.sessionIdleTimeout = idleTimeout;
            return this;
        }

        /**
         * Sets the {@link TelemetryRecorder} used for server-level diagnostic output.
         * Defaults to {@link SystemTelemetryRecorder} writing to {@code System.out}/{@code System.err}.
         *
         * @param recorder the recorder; must not be null
         * @return this builder
         */
        public Builder recorder(final TelemetryRecorder recorder) {
            this.recorder = Objects.requireNonNull(recorder, "recorder must not be null");
            return this;
        }

        /**
         * Subscribes a {@link McpToolCallLogger} using this server's recorder.
         * STARTED events are logged at diagnostic level, SUCCEEDED at info (or warn when slow),
         * and FAILED at warn with the thrown exception.
         *
         * @return this builder
         */
        public Builder logToolCalls() {
            this.logToolCalls = true;
            return this;
        }

        /**
         * Sets the maximum number of resource subscriptions per session (default 1,000).
         * Subscribe requests beyond the cap are silently ignored.
         *
         * @param max the cap; must be positive
         * @return this builder
         */
        public Builder maxSubscriptionsPerSession(final int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxSubscriptionsPerSession must be positive");
            }
            this.maxSubscriptionsPerSession = max;
            return this;
        }

        /**
         * Sets the maximum number of requests accepted in one JSON-RPC batch (default 1,000).
         * Larger batches are rejected with a single {@code -32600} Invalid Request error.
         *
         * @param max the cap; must be positive
         * @return this builder
         */
        public Builder maxBatchSize(final int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxBatchSize must be positive");
            }
            this.maxBatchSize = max;
            return this;
        }

        /**
         * Sets the maximum number of concurrent sessions one authenticated principal may hold
         * (default 100), so that a single caller cannot take every slot under {@link #maxSessions(int)}.
         * {@code initialize} requests beyond the cap are rejected with 503. Sessions without a
         * principal are bounded only by {@link #maxSessions(int)}.
         *
         * @param max the cap; must be positive
         * @return this builder
         */
        public Builder maxSessionsPerPrincipal(final int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxSessionsPerPrincipal must be positive");
            }
            this.maxSessionsPerPrincipal = max;
            return this;
        }

        /**
         * Sets the maximum number of JSON-RPC requests being handled at once (default 1,000), each
         * item of a batch counting as one. HTTP requests beyond the cap are rejected with 503. On
         * stdio the reader waits for free slots instead. A batch larger than the cap takes all of
         * it and runs alone, so keep {@link #maxBatchSize(int)} at or below this.
         *
         * @param max the cap; must be positive
         * @return this builder
         */
        public Builder maxConcurrentRequests(final int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxConcurrentRequests must be positive");
            }
            this.maxConcurrentRequests = max;
            return this;
        }

        /**
         * Sets the maximum length in characters of one stdio line (default 10 MiB). A longer line
         * is discarded with a warning and the loop carries on with the next one.
         *
         * @param max the cap; must be positive
         * @return this builder
         */
        public Builder maxStdioLineLength(final int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxStdioLineLength must be positive");
            }
            this.maxStdioLineLength = max;
            return this;
        }

        /**
         * Allows requests from non-loopback peers that carry no authenticated principal.
         *
         * <p>By default a request from a non-loopback peer is rejected with HTTP 401 unless an
         * authentication layer (e.g. {@code serve-auth}'s {@code AuthMiddleware}) has bound
         * {@link RequestContext#PRINCIPAL}. Call this only for a server that is deliberately open.
         *
         * <p>The check uses the transport's peer address. Behind a reverse proxy running on the same
         * host every request appears to come from loopback, so the proxy itself must authenticate
         * callers.
         *
         * @return this builder
         */
        public Builder allowUnauthenticatedRemote() {
            this.allowUnauthenticatedRemote = true;
            return this;
        }

        /**
         * Builds the {@link McpServer}.
         *
         * @return the server
         */
        public McpServer build() {
            final var server = new McpServer(this);
            if (logToolCalls) {
                server.toolCallEvents().subscribe(McpToolCallLogger.of(recorder));
            }
            return server;
        }
    }
}
