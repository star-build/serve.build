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
import build.base.flow.SubscriberRegistry;
import build.base.json.Json;
import build.base.json.JsonArray;
import build.base.json.JsonNull;
import build.base.json.JsonNumber;
import build.base.json.JsonObject;
import build.base.json.JsonString;
import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.base.telemetry.foundation.SystemTelemetryRecorder;
import build.serve.foundation.Exchange;
import build.serve.foundation.Handler;
import build.serve.foundation.context.RequestContext;
import build.serve.sse.SseEmitter;
import build.serve.sse.SseEvent;
import build.serve.sse.SseUpgrade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * An MCP (Model Context Protocol) server that exposes tools via JSON-RPC 2.0 over HTTP.
 *
 * <p><strong>Authentication:</strong> {@code McpServer} has no built-in authentication.
 * Any deployment reachable by untrusted clients must be wrapped with an authentication
 * layer — for example {@code serve-auth}'s {@code AuthMiddleware} — before the handler
 * returned by {@link #handler()} is registered. Standalone use without such a wrapper
 * is an open server.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class McpServer {

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

    private final McpServerInfo info;
    private final String instructions;
    private final Map<String, McpTool> tools;
    private final Map<String, McpResource> resources;
    private final List<TemplateEntry> templates;
    private final Map<String, McpPrompt> prompts;
    private final Set<String> allowedOrigins;
    private final Set<String> allowedHosts;
    private final int maxSessions;
    private final int maxSubscriptionsPerSession;
    private final int maxBatchSize;
    private final boolean allowUnauthenticatedRemote;
    private final TelemetryRecorder recorder;
    private final SubscriberRegistry<ToolCallEvent> toolCallEvents = new SubscriberRegistry<>();
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong invocationCounter = new AtomicLong();
    private volatile SessionState stdioSession;
    private volatile LinkedBlockingQueue<Optional<String>> stdioOutputQueue;

    private McpServer(final Builder builder) {
        this.info = new McpServerInfo(builder.name, builder.version);
        this.instructions = builder.instructions;
        this.allowedOrigins = Set.copyOf(builder.allowedOrigins);
        this.allowedHosts = Set.copyOf(builder.allowedHosts);
        this.maxSessions = builder.maxSessions;
        this.maxSubscriptionsPerSession = builder.maxSubscriptionsPerSession;
        this.maxBatchSize = builder.maxBatchSize;
        this.allowUnauthenticatedRemote = builder.allowUnauthenticatedRemote;
        this.recorder = builder.recorder;

        startSessionReaper(builder.sessionIdleTimeout);

        final var toolMap = new LinkedHashMap<String, McpTool>();
        for (final var tool : builder.tools) {
            toolMap.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(toolMap);

        final var resourceMap = new LinkedHashMap<String, McpResource>();
        for (final var resource : builder.resources) {
            resourceMap.put(resource.uri(), resource);
        }
        this.resources = Map.copyOf(resourceMap);

        final var templateList = new ArrayList<TemplateEntry>();
        for (final var template : builder.templates) {
            templateList.add(new TemplateEntry(template, uriTemplateToPattern(template.uriTemplate())));
        }
        this.templates = List.copyOf(templateList);

        final var promptMap = new LinkedHashMap<String, McpPrompt>();
        for (final var prompt : builder.prompts) {
            promptMap.put(prompt.name(), prompt);
        }
        this.prompts = Map.copyOf(promptMap);
    }

    private void startSessionReaper(final Duration idleTimeout) {
        final long intervalMs = Math.min(idleTimeout.toMillis() / 2, Duration.ofMinutes(5).toMillis());
        Thread.ofVirtual().name("mcp-session-reaper").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (final InterruptedException e) {
                    break;
                }
                final var cutoff = Instant.now().minus(idleTimeout);
                for (final var entry : sessions.entrySet()) {
                    if (entry.getValue().lastAccessed.isBefore(cutoff)
                        && sessions.remove(entry.getKey(), entry.getValue())) {
                        closeEmitter(entry.getValue());
                    }
                }
            }
        });
    }

    /**
     * Closes the session's SSE stream, if one is open, so its parked virtual thread and socket
     * are released when the session is evicted or deleted.
     */
    private static void closeEmitter(final SessionState state) {
        final var emitter = state.emitter.getAndSet(null);
        if (emitter != null) {
            emitter.close();
        }
    }

    private static final class SessionState {
        /**
         * The principal that initialized the session, or {@code null} if it was unauthenticated.
         */
        final Object principal;
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        final AtomicReference<SseEmitter> emitter = new AtomicReference<>();
        volatile Instant lastAccessed = Instant.now();
        volatile McpLogLevel logLevel;

        SessionState(final Object principal) {
            this.principal = principal;
        }
    }

    private SessionState stateFor(final String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return "local".equals(sessionId) ? stdioSession : sessions.get(sessionId);
    }

    private static Object currentPrincipal() {
        return RequestContext.PRINCIPAL.isBound() ? RequestContext.PRINCIPAL.get() : null;
    }

    /**
     * Looks up an HTTP session, treating a session owned by a different principal as absent so
     * that a session id alone is never enough to use someone else's session. Principals are
     * compared with {@link Object#equals}, so an authentication layer must bind principals that
     * are equal across requests from the same caller (e.g. records).
     * <p>
     * When no principal is bound, as on a server using {@link Builder#allowUnauthenticatedRemote()},
     * every session is owned by {@code null} and the unguessable session id is the only protection.
     */
    private SessionState ownedSession(final String sessionId) {
        final var state = sessions.get(sessionId);
        return state != null && Objects.equals(state.principal, currentPrincipal()) ? state : null;
    }

    /**
     * Fail-closed: a peer that is not on loopback must have been authenticated by a layer in front
     * of this handler unless {@link Builder#allowUnauthenticatedRemote()} was called. A transport
     * that cannot report a peer address is not restricted.
     */
    private boolean isRemoteAccessAllowed(final Exchange exchange) {
        if (allowUnauthenticatedRemote || RequestContext.PRINCIPAL.isBound()) {
            return true;
        }
        return exchange.request().remoteAddress()
            .map(address -> address.getAddress() != null && address.getAddress().isLoopbackAddress())
            .orElse(true);
    }

    private record TemplateEntry(McpResourceTemplate template, Pattern pattern) {
    }

    private static Pattern uriTemplateToPattern(final String uriTemplate) {
        final var sb = new StringBuilder();
        final var m = Pattern.compile("\\{\\+?([^}]+)}").matcher(uriTemplate);
        int pos = 0;
        while (m.find()) {
            sb.append(Pattern.quote(uriTemplate.substring(pos, m.start())));
            sb.append(m.group().startsWith("{+") ? ".+" : "[^/]+");
            pos = m.end();
        }
        sb.append(Pattern.quote(uriTemplate.substring(pos)));
        return Pattern.compile(sb.toString());
    }

    /**
     * Returns a serve.build {@link Handler} that handles the MCP endpoint.
     * <p>
     * Implements the MCP streamable HTTP transport (spec 2025-03-26). POST requests receive either
     * a JSON response or an SSE stream depending on the client's {@code Accept} header. Session IDs
     * are issued during {@code initialize} and validated on subsequent requests.
     *
     * @return the handler
     */
    public Handler handler() {
        return exchange -> {
            if (!isOriginAllowed(exchange) || !isHostAllowed(exchange)) {
                exchange.response().status(403).send("Forbidden");
                return;
            }

            if (!isRemoteAccessAllowed(exchange)) {
                exchange.response().status(401).send("Authentication required");
                return;
            }

            final var method = exchange.request().method();

            if ("GET".equalsIgnoreCase(method)) {
                handleSseConnection(exchange);
                return;
            }

            if ("DELETE".equalsIgnoreCase(method)) {
                final var sid = exchange.request().header("Mcp-Session-Id");
                if (sid.isPresent()) {
                    final var owned = ownedSession(sid.get());
                    if (owned != null && sessions.remove(sid.get(), owned)) {
                        closeEmitter(owned);
                    }
                }
                exchange.response().status(200).send("");
                return;
            }

            if (!"POST".equalsIgnoreCase(method)) {
                exchange.response().status(405).send("Method Not Allowed");
                return;
            }

            final var accept = exchange.request().header("Accept");
            if (accept.isPresent() && !canServe(accept.get())) {
                exchange.response().status(406).send("Not Acceptable");
                return;
            }

            final var sessionId = exchange.request().header("Mcp-Session-Id");

            final var body = exchange.request().bodyAsString();
            final var parsed = Json.parse(body);

            if (parsed instanceof JsonArray batch) {
                if (sessionId.isEmpty()) {
                    exchange.response().status(400).send("Mcp-Session-Id header required");
                    return;
                }
                if (ownedSession(sessionId.get()) == null) {
                    exchange.response().status(404).send("Session not found");
                    return;
                }
                final var maybeResponse = dispatchBatch(batch, sessionId.get());
                if (maybeResponse.isEmpty()) {
                    exchange.response().status(202).send("");
                    return;
                }
                exchange.response()
                    .header("Content-Type", "application/json")
                    .send(maybeResponse.get().toJsonString().getBytes(StandardCharsets.UTF_8));
                return;
            }

            final var request = parsed.asObject();
            final var isInitialize = "initialize".equals(getString(request, "method"));

            if (isInitialize && sessionId.isPresent()) {
                exchange.response().status(400).send("Bad Request");
                return;
            }

            if (!isInitialize && sessionId.isEmpty()) {
                exchange.response().status(400).send("Mcp-Session-Id header required");
                return;
            }

            if (sessionId.isPresent() && ownedSession(sessionId.get()) == null) {
                exchange.response().status(404).send("Session not found");
                return;
            }

            if (isInitialize && sessions.size() >= maxSessions) {
                exchange.response().status(503).send("Too many sessions");
                return;
            }

            final var maybeResponse = dispatch(request, sessionId.orElse(null));

            if (maybeResponse.isEmpty()) {
                exchange.response().status(202).send("");
                return;
            }

            if (isInitialize) {
                final var newSessionId = UUID.randomUUID().toString();
                sessions.put(newSessionId, new SessionState(currentPrincipal()));
                exchange.response().header("Mcp-Session-Id", newSessionId);
            }

            final var jsonString = maybeResponse.get().toJsonString();

            if (acceptsSse(exchange)) {
                final var sseBody = SseEvent.of("message", jsonString).serialize()
                    .getBytes(StandardCharsets.UTF_8);
                exchange.response()
                    .status(200)
                    .header("Content-Type", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .send(sseBody);
            } else {
                exchange.response()
                    .header("Content-Type", "application/json")
                    .send(jsonString.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

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
        stdioSession = new SessionState(null);
        final var queue = new LinkedBlockingQueue<Optional<String>>();
        stdioOutputQueue = queue;
        // Single writer thread owns all writes to `out`; dispatch threads enqueue responses.
        // This keeps PipedInputStream's writer-thread tracking pointed at one live thread.
        final var writerThread = Thread.ofVirtual().name("mcp-stdio-writer").start(() -> {
            final var pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
            try {
                Optional<String> item;
                while ((item = queue.take()).isPresent()) {
                    pw.println(item.get());
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            // closing the executor waits for every in-flight dispatch, on EOF or on error
            try (var dispatcher = Executors.newVirtualThreadPerTaskExecutor()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final var captured = line;
                    dispatcher.execute(() -> {
                        JsonValue parsed = null;
                        try {
                            parsed = Json.parse(captured);
                            if (parsed instanceof JsonArray batch) {
                                dispatchBatch(batch, "local")
                                    .ifPresent(r -> queue.offer(Optional.of(r.toJsonString())));
                            } else {
                                dispatch(parsed.asObject(), "local")
                                    .ifPresent(r -> queue.offer(Optional.of(r.toJsonString())));
                            }
                        } catch (final RuntimeException e) {
                            recorder.warn("Discarding malformed stdio line: %s", e.getMessage());
                        } catch (final Error e) {
                            // there is no connection to abort on stdio, so answer the request
                            // rather than leave the client waiting, then let the Error propagate
                            if (parsed instanceof JsonObject request) {
                                internalError(request).ifPresent(r -> queue.offer(Optional.of(r.toJsonString())));
                            }
                            throw e;
                        }
                    });
                }
            }
            recorder.info("stdio session ended (clean EOF)");
        } catch (final IOException e) {
            recorder.warn("stdio session ended unexpectedly: %s", e.getMessage());
        } finally {
            stdioOutputQueue = null;
            stdioSession = null;
            queue.offer(Optional.empty()); // poison pill — stops the writer thread
            try {
                writerThread.join(5_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Optional<JsonObject> dispatch(final JsonObject request,
                                          final String sessionId) {
        final var state = stateFor(sessionId);
        if (state != null) {
            state.lastAccessed = Instant.now();
        }

        final var rpcMethod = getString(request, "method");
        final var id = request.members().get("id");

        recorder.diagnostic("received: %s", rpcMethod);

        if (id == null || id instanceof JsonNull) {
            return Optional.empty();
        }

        final var response = switch (rpcMethod) {
            case "initialize" ->
                envelope(id, handleInitialize(request.members().getOrDefault("params", JsonNull.INSTANCE)));
            case "ping" -> envelope(id, JsonObject.builder().build());
            case "tools/list" -> envelope(id, handleToolsList());
            case "tools/call" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handleToolsCall(params, sessionId, id);
            }
            case "resources/list" -> envelope(id, handleResourcesList());
            case "resources/templates/list" -> envelope(id, handleResourcesTemplatesList());
            case "resources/read" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handleResourcesRead(params, id);
            }
            case "resources/subscribe" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handleResourcesSubscribe(params, sessionId, id);
            }
            case "resources/unsubscribe" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handleResourcesUnsubscribe(params, sessionId, id);
            }
            case "logging/setLevel" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handleLoggingSetLevel(params, sessionId, id);
            }
            case "prompts/list" -> envelope(id, handlePromptsList());
            case "prompts/get" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handlePromptsGet(params, id);
            }
            case "completion/complete" -> {
                final var params = request.members().getOrDefault("params", JsonNull.INSTANCE);
                yield handleCompletionComplete(params, id);
            }
            default -> errorEnvelope(id, -32601, "Method not found");
        };

        recorder.diagnostic("sent response to: %s", rpcMethod);
        return Optional.of(response);
    }

    private static Optional<JsonObject> internalError(final JsonObject request) {
        final var id = request.members().get("id");
        if (id == null || id instanceof JsonNull) {
            return Optional.empty();
        }
        return Optional.of(errorEnvelope(id, -32603, "Internal error"));
    }

    private Optional<JsonValue> dispatchBatch(final JsonArray batch, final String sessionId) {
        if (batch.values().isEmpty()) {
            return Optional.of(errorEnvelope(JsonNull.INSTANCE, -32600, "Invalid Request: empty batch"));
        }
        if (batch.values().size() > maxBatchSize) {
            return Optional.of(errorEnvelope(JsonNull.INSTANCE, -32600,
                "Invalid Request: batch exceeds " + maxBatchSize + " requests"));
        }
        final var responses = new ConcurrentLinkedQueue<JsonObject>();
        final var remaining = new CountDownLatch(batch.values().size());
        for (final var item : batch.values()) {
            if (!(item instanceof JsonObject req)) {
                responses.add(errorEnvelope(JsonNull.INSTANCE, -32600, "Invalid Request"));
                remaining.countDown();
                continue;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    dispatch(req, sessionId).ifPresent(responses::add);
                } catch (final Error e) {
                    // answer the request so the batch reply stays complete, then let the Error propagate
                    internalError(req).ifPresent(responses::add);
                    throw e;
                } finally {
                    remaining.countDown();
                }
            });
        }
        try {
            remaining.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(errorEnvelope(JsonNull.INSTANCE, -32603, "Internal error: batch interrupted"));
        }
        if (responses.isEmpty()) {
            return Optional.empty();
        }
        final var arr = JsonArray.builder();
        responses.forEach(arr::add);
        return Optional.of(arr.build());
    }

    /**
     * Fail-closed by default: any request carrying an {@code Origin} header (i.e. from a
     * browser) must match an entry registered via {@link Builder#allowOrigin(String)}, even
     * if no origins have been registered. This is the primary DNS-rebinding defense — a
     * malicious webpage's {@code fetch()} always carries an {@code Origin} header. Requests
     * without an {@code Origin} header (server-to-server clients, the Anthropic API's remote
     * MCP client, {@code curl}) are not browser-originated and are always allowed through this
     * check.
     */
    private boolean isOriginAllowed(final Exchange exchange) {
        final var origin = exchange.request().header("Origin");
        return origin.isEmpty() || allowedOrigins.contains(origin.get());
    }

    /**
     * Fail-closed by default: unless {@link Builder#allowHost(String)} has registered explicit
     * hosts, only requests whose {@code Host} header names a loopback address are accepted.
     * This stops the DNS-rebinding class of attack where an attacker-controlled domain resolves
     * to {@code 127.0.0.1} — the request's {@code Origin} can be spoofed to look same-origin,
     * but the {@code Host} header still names the rebound domain.
     */
    private boolean isHostAllowed(final Exchange exchange) {
        final var host = exchange.request().header("Host");
        if (host.isEmpty()) {
            return false;
        }
        final var hostname = hostOnly(host.get());
        if (!allowedHosts.isEmpty()) {
            return allowedHosts.contains(hostname);
        }
        return isLoopbackHost(hostname);
    }

    private static String hostOnly(final String hostHeader) {
        if (hostHeader.startsWith("[")) {
            final var end = hostHeader.indexOf(']');
            return end >= 0 ? hostHeader.substring(0, end + 1) : hostHeader;
        }
        final var colon = hostHeader.indexOf(':');
        return colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
    }

    private static boolean isLoopbackHost(final String hostname) {
        return "localhost".equalsIgnoreCase(hostname)
            || "127.0.0.1".equals(hostname)
            || "[::1]".equals(hostname)
            || "::1".equals(hostname);
    }

    private boolean acceptsSse(final Exchange exchange) {
        return exchange.request().header("Accept")
            .map(a -> a.contains("text/event-stream"))
            .orElse(false);
    }

    private static boolean canServe(final String accept) {
        return accept.contains("application/json")
            || accept.contains("text/event-stream")
            || accept.contains("*/*");
    }

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
        return toolCallEvents;
    }

    private static final String SUPPORTED_PROTOCOL_VERSION = "2025-03-26";

    private JsonObject handleInitialize(final JsonValue params) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var requestedVersion = getString(paramsObj, "protocolVersion");
        // Respond with the requested version if we support it; otherwise our highest supported
        // version. The client decides whether to proceed or disconnect on mismatch.
        final var responseVersion = SUPPORTED_PROTOCOL_VERSION.equals(requestedVersion)
            ? requestedVersion
            : SUPPORTED_PROTOCOL_VERSION;

        final var capabilities = JsonObject.builder()
            .put("tools", JsonObject.builder().put("listChanged", false).build())
            .put("resources", JsonObject.builder()
                .put("subscribe", true)
                .put("listChanged", false)
                .build())
            .put("logging", JsonObject.builder().build())
            .put("prompts", JsonObject.builder().put("listChanged", true).build())
            .put("completions", JsonObject.builder().build())
            .build();

        final var serverInfo = JsonObject.builder()
            .put("name", info.name())
            .put("version", info.version())
            .build();

        final var result = JsonObject.builder()
            .put("protocolVersion", responseVersion)
            .put("capabilities", capabilities)
            .put("serverInfo", serverInfo);
        if (instructions != null) {
            result.put("instructions", instructions);
        }
        return result.build();
    }

    private JsonObject handleToolsList() {
        final var toolsArray = JsonArray.builder();
        for (final var tool : tools.values()) {
            final var toolBuilder = JsonObject.builder()
                .put("name", tool.name())
                .put("description", tool.description())
                .put("inputSchema", tool.inputSchema());
            tool.outputSchema().ifPresent(schema -> toolBuilder.put("outputSchema", schema));
            tool.annotations().ifPresent(ann -> toolBuilder.put("annotations", buildAnnotationsJson(ann)));
            toolsArray.add(toolBuilder.build());
        }

        return JsonObject.builder()
            .put("tools", toolsArray.build())
            .build();
    }

    private static JsonObject buildAnnotationsJson(final McpToolAnnotations ann) {
        final var b = JsonObject.builder();
        ann.audience().ifPresent(roles -> {
            final var arr = JsonArray.builder();
            roles.forEach(r -> arr.add(JsonString.of(r)));
            b.put("audience", arr.build());
        });
        ann.readOnlyHint().ifPresent(v -> b.put("readOnlyHint", v));
        ann.destructiveHint().ifPresent(v -> b.put("destructiveHint", v));
        ann.idempotentHint().ifPresent(v -> b.put("idempotentHint", v));
        ann.openWorldHint().ifPresent(v -> b.put("openWorldHint", v));
        return b.build();
    }

    private JsonObject handleToolsCall(final JsonValue params, final String sessionId, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var toolName = getString(paramsObj, "name");
        final var arguments = paramsObj.members().getOrDefault("arguments", JsonNull.INSTANCE);

        final var tool = tools.get(toolName);
        if (tool == null) {
            return errorEnvelope(id, -32602, "Unknown tool: " + sanitizeMessage(toolName));
        }

        final var meta = paramsObj.members().get("_meta");
        final var progressToken = (meta instanceof JsonObject m) ? m.members().get("progressToken") : null;
        final var hasProgressToken = progressToken != null && !(progressToken instanceof JsonNull);

        final var invocationId = invocationCounter.incrementAndGet();
        toolCallEvents.publish(ToolCallEvent.started(invocationId, sessionId, toolName, arguments));
        final var start = Instant.now();
        try {
            final McpToolResult toolResult;
            if (hasProgressToken) {
                final var token = progressToken;
                final ProgressReporter reporter = (progress, total, message) ->
                    pushProgressNotification(sessionId, token, progress, total, message);
                toolResult = ScopedValue.where(SESSION_ID, sessionId)
                    .where(PROGRESS_REPORTER, reporter)
                    .call(() -> tool.call(arguments));
            } else {
                toolResult = ScopedValue.where(SESSION_ID, sessionId).call(() -> tool.call(arguments));
            }
            final var duration = Duration.between(start, Instant.now());
            toolCallEvents.publish(ToolCallEvent.succeeded(invocationId, sessionId, toolName, arguments, toolResult, duration));
            return envelope(id, buildToolResultJson(toolResult));
        } catch (final Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            final var duration = Duration.between(start, Instant.now());
            toolCallEvents.publish(ToolCallEvent.failed(invocationId, sessionId, toolName, arguments, e, duration));
            recorder.warn(e, "Tool %s failed", toolName);
            if (e instanceof Error error) {
                throw error;
            }
            return envelope(id, buildToolResultJson(McpToolResult.error("Tool execution failed")));
        }
    }

    private void pushProgressNotification(final String sessionId,
                                          final JsonValue progressToken,
                                          final double progress,
                                          final OptionalDouble total,
                                          final Optional<String> message) {
        final var paramsBuilder = JsonObject.builder()
            .put("progressToken", progressToken)
            .put("progress", progress);
        total.ifPresent(t -> paramsBuilder.put("total", t));
        message.ifPresent(m -> paramsBuilder.put("message", m));
        final var notification = JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/progress")
            .put("params", paramsBuilder.build())
            .build()
            .toJsonString();
        pushNotification(sessionId, notification);
    }

    private void pushNotification(final String sessionId, final String json) {
        if ("local".equals(sessionId)) {
            final var q = stdioOutputQueue;
            if (q != null) {
                q.offer(Optional.of(json));
            }
        } else {
            final var state = sessions.get(sessionId);
            if (state != null) {
                final var emitter = state.emitter.get();
                if (emitter != null && emitter.isOpen()) {
                    try {
                        emitter.send(SseEvent.of("message", json));
                    } catch (final IOException e) {
                        recorder.warn("Failed to push notification for session %s: %s", sessionId, e.getMessage());
                    }
                }
            }
        }
    }

    private void handleSseConnection(final Exchange exchange) throws Exception {
        final var sessionId = exchange.request().header("Mcp-Session-Id").orElse(null);
        if (sessionId == null) {
            exchange.response().status(400).send("Mcp-Session-Id header required");
            return;
        }
        final var state = ownedSession(sessionId);
        if (state == null) {
            exchange.response().status(404).send("Session not found");
            return;
        }
        SseUpgrade.sse(emitter -> {
            state.lastAccessed = Instant.now();
            final var prev = state.emitter.getAndSet(emitter);
            if (prev != null) {
                prev.close();
            }
            if (sessions.get(sessionId) != state) {
                // the session was evicted or deleted while this stream was connecting
                closeEmitter(state);
            }
            try {
                emitter.awaitClose();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                state.emitter.compareAndSet(emitter, null);
            }
        }).handle(exchange);
    }

    /**
     * Sends a {@code notifications/resources/updated} event to all sessions subscribed to
     * the given URI. Call this whenever the content of a resource changes.
     *
     * @param uri the URI of the changed resource
     */
    public void notifyResourceChanged(final String uri) {
        final var notification = JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/resources/updated")
            .put("params", JsonObject.builder().put("uri", uri).build())
            .build()
            .toJsonString();
        final var event = SseEvent.of("message", notification);

        for (final var state : sessions.values()) {
            if (state.subscriptions.contains(uri)) {
                final var emitter = state.emitter.get();
                if (emitter != null && emitter.isOpen()) {
                    try {
                        emitter.send(event);
                    } catch (final IOException e) {
                        recorder.warn("Failed to push resource notification for URI %s: %s", uri, e.getMessage());
                    }
                }
            }
        }

        final var q = stdioOutputQueue;
        final var ss = stdioSession;
        if (q != null && ss != null && ss.subscriptions.contains(uri)) {
            q.offer(Optional.of(notification));
        }
    }

    private JsonObject handleResourcesList() {
        final var resourcesArray = JsonArray.builder();
        for (final var resource : resources.values()) {
            final var builder = JsonObject.builder()
                .put("uri", resource.uri())
                .put("name", resource.name());
            resource.description().ifPresent(d -> builder.put("description", d));
            resource.mimeType().ifPresent(m -> builder.put("mimeType", m));
            resource.size().ifPresent(s -> builder.put("size", s));
            resourcesArray.add(builder.build());
        }
        return JsonObject.builder()
            .put("resources", resourcesArray.build())
            .build();
    }

    private JsonObject handleResourcesTemplatesList() {
        final var templatesArray = JsonArray.builder();
        for (final var entry : templates) {
            final var t = entry.template();
            final var builder = JsonObject.builder()
                .put("uriTemplate", t.uriTemplate())
                .put("name", t.name());
            t.description().ifPresent(d -> builder.put("description", d));
            t.mimeType().ifPresent(m -> builder.put("mimeType", m));
            templatesArray.add(builder.build());
        }
        return JsonObject.builder()
            .put("resourceTemplates", templatesArray.build())
            .build();
    }

    private JsonObject handleResourcesRead(final JsonValue params, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var uri = getString(paramsObj, "uri");

        final McpResourceContent content;
        try {
            final var exact = resources.get(uri);
            if (exact != null) {
                content = exact.read();
            } else {
                final var matched = findTemplate(uri);
                if (matched == null) {
                    return errorEnvelope(id, -32002, "Resource not found: " + uri);
                }
                content = matched.read(uri);
            }
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recorder.warn(e, "Failed to read resource %s", sanitizeMessage(uri));
            return errorEnvelope(id, -32002, "Failed to read resource");
        }

        final var contentNode = switch (content) {
            case McpResourceContent.Text text -> JsonObject.builder()
                .put("uri", text.uri())
                .put("mimeType", text.mimeType())
                .put("text", text.text())
                .build();
            case McpResourceContent.Blob blob -> JsonObject.builder()
                .put("uri", blob.uri())
                .put("mimeType", blob.mimeType())
                .put("blob", blob.blob())
                .build();
        };
        return envelope(id, JsonObject.builder()
            .put("contents", JsonArray.builder().add(contentNode).build())
            .build());
    }

    private McpResourceTemplate findTemplate(final String uri) {
        for (final var entry : templates) {
            if (entry.pattern().matcher(uri).matches()) {
                return entry.template();
            }
        }
        return null;
    }

    private JsonObject handleResourcesSubscribe(final JsonValue params, final String sessionId, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var uri = getString(paramsObj, "uri");
        final var state = stateFor(sessionId);
        if (state != null) {
            synchronized (state.subscriptions) {
                if (state.subscriptions.size() < maxSubscriptionsPerSession) {
                    state.subscriptions.add(uri);
                }
            }
        }
        return envelope(id, JsonObject.builder().build());
    }

    private JsonObject handleResourcesUnsubscribe(final JsonValue params, final String sessionId, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var uri = getString(paramsObj, "uri");
        final var state = stateFor(sessionId);
        if (state != null) {
            state.subscriptions.remove(uri);
        }
        return envelope(id, JsonObject.builder().build());
    }

    private JsonObject handleLoggingSetLevel(final JsonValue params, final String sessionId, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var levelStr = getString(paramsObj, "level");
        final var level = McpLogLevel.fromString(levelStr);
        if (level.isEmpty()) {
            return errorEnvelope(id, -32602, "Invalid log level: " + sanitizeMessage(levelStr));
        }
        final var state = stateFor(sessionId);
        if (state != null) {
            state.logLevel = level.get();
        }
        return envelope(id, JsonObject.builder().build());
    }

    private JsonObject handlePromptsList() {
        final var promptsArray = JsonArray.builder();
        for (final var prompt : prompts.values()) {
            final var builder = JsonObject.builder().put("name", prompt.name());
            prompt.description().ifPresent(d -> builder.put("description", d));
            final var args = prompt.arguments();
            if (!args.isEmpty()) {
                final var argsArray = JsonArray.builder();
                for (final var arg : args) {
                    final var argBuilder = JsonObject.builder()
                        .put("name", arg.name())
                        .put("required", arg.required());
                    arg.description().ifPresent(d -> argBuilder.put("description", d));
                    argsArray.add(argBuilder.build());
                }
                builder.put("arguments", argsArray.build());
            }
            promptsArray.add(builder.build());
        }
        return JsonObject.builder().put("prompts", promptsArray.build()).build();
    }

    private JsonObject handlePromptsGet(final JsonValue params, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var name = getString(paramsObj, "name");
        final var prompt = prompts.get(name);
        if (prompt == null) {
            return errorEnvelope(id, -32602, "Unknown prompt: " + sanitizeMessage(name));
        }

        final var rawArgs = paramsObj.members().get("arguments");
        final var argMap = new HashMap<String, String>();
        if (rawArgs instanceof JsonObject argsObj) {
            for (final var entry : argsObj.members().entrySet()) {
                if (entry.getValue() instanceof JsonString s) {
                    argMap.put(entry.getKey(), s.value());
                }
            }
        }

        final McpPromptResult result;
        try {
            result = prompt.get(Map.copyOf(argMap));
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recorder.warn(e, "Prompt %s failed", sanitizeMessage(name));
            return errorEnvelope(id, -32603, "Prompt rendering failed");
        }

        final var messagesArray = JsonArray.builder();
        for (final var msg : result.messages()) {
            final var contentNode = switch (msg) {
                case McpPromptMessage.Text t -> JsonObject.builder()
                    .put("type", "text")
                    .put("text", t.text())
                    .build();
                case McpPromptMessage.Image img -> JsonObject.builder()
                    .put("type", "image")
                    .put("data", img.data())
                    .put("mimeType", img.mimeType())
                    .build();
            };
            messagesArray.add(JsonObject.builder()
                .put("role", msg.role())
                .put("content", contentNode)
                .build());
        }

        final var resultBuilder = JsonObject.builder();
        result.description().ifPresent(d -> resultBuilder.put("description", d));
        resultBuilder.put("messages", messagesArray.build());
        return envelope(id, resultBuilder.build());
    }

    private JsonObject handleCompletionComplete(final JsonValue params, final JsonValue id) {
        final var paramsObj = params instanceof JsonObject p ? p : JsonObject.builder().build();
        final var ref = paramsObj.members().get("ref");
        final var argument = paramsObj.members().get("argument");

        if (!(ref instanceof JsonObject refObj) || !(argument instanceof JsonObject argObj)) {
            return errorEnvelope(id, -32602, "Invalid params: ref and argument are required");
        }

        final var refType = getString(refObj, "type");
        final var argName = getString(argObj, "name");
        final var argValue = getString(argObj, "value");

        final List<String> values;
        try {
            values = switch (refType) {
                case "ref/prompt" -> {
                    final var prompt = prompts.get(getString(refObj, "name"));
                    if (prompt == null) {
                        yield List.of();
                    }
                    yield prompt.arguments().stream()
                        .filter(a -> a.name().equals(argName))
                        .findFirst()
                        .flatMap(McpPromptArgument::completer)
                        .map(fn -> fn.apply(argValue))
                        .orElse(List.of());
                }
                case "ref/resource" -> {
                    final var template = findTemplateByUri(getString(refObj, "uri"));
                    yield template == null ? List.of() : template.complete(argName, argValue);
                }
                default -> List.of();
            };
        } catch (final Exception e) {
            recorder.warn(e, "Completion for %s failed", sanitizeMessage(refType));
            return errorEnvelope(id, -32603, "Completion failed");
        }

        final var capped = values.size() > 100 ? values.subList(0, 100) : values;
        final var arr = JsonArray.builder();
        capped.forEach(v -> arr.add(JsonString.of(v)));
        return envelope(id, JsonObject.builder()
            .put("completion", JsonObject.builder()
                .put("values", arr.build())
                .build())
            .build());
    }

    private McpResourceTemplate findTemplateByUri(final String uriTemplate) {
        for (final var entry : templates) {
            if (entry.template().uriTemplate().equals(uriTemplate)) {
                return entry.template();
            }
        }
        return null;
    }

    /**
     * Pushes a {@code notifications/prompts/list_changed} event to all connected clients.
     * Call this whenever the set of available prompts changes at runtime.
     */
    public void notifyPromptsChanged() {
        final var notification = JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/prompts/list_changed")
            .build()
            .toJsonString();
        final var event = SseEvent.of("message", notification);

        for (final var state : sessions.values()) {
            final var emitter = state.emitter.get();
            if (emitter != null && emitter.isOpen()) {
                try {
                    emitter.send(event);
                } catch (final IOException e) {
                    recorder.warn("Failed to push prompts/list_changed notification: %s", e.getMessage());
                }
            }
        }

        final var q = stdioOutputQueue;
        if (q != null) {
            q.offer(Optional.of(notification));
        }
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
        final var paramsBuilder = JsonObject.builder().put("level", level.name());
        if (logger != null && !logger.isEmpty()) {
            paramsBuilder.put("logger", logger);
        }
        if (data != null && !(data instanceof JsonNull)) {
            paramsBuilder.put("data", data);
        }
        final var notification = JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/message")
            .put("params", paramsBuilder.build())
            .build()
            .toJsonString();
        final var event = SseEvent.of("message", notification);

        for (final var state : sessions.values()) {
            final var threshold = state.logLevel;
            if (threshold == null || !level.meets(threshold)) {
                continue;
            }
            final var emitter = state.emitter.get();
            if (emitter != null && emitter.isOpen()) {
                try {
                    emitter.send(event);
                } catch (final IOException e) {
                    recorder.warn("Failed to push log notification: %s", e.getMessage());
                }
            }
        }

        final var q = stdioOutputQueue;
        final var ss = stdioSession;
        if (q != null && ss != null) {
            final var threshold = ss.logLevel;
            if (threshold != null && level.meets(threshold)) {
                q.offer(Optional.of(notification));
            }
        }
    }

    private static JsonObject buildToolResultJson(final McpToolResult toolResult) {
        final var contentArray = JsonArray.builder();
        for (final var content : toolResult.content()) {
            final var contentNode = switch (content) {
                case McpContent.Text text -> JsonObject.builder()
                    .put("type", "text")
                    .put("text", text.text())
                    .build();
                case McpContent.Image image -> JsonObject.builder()
                    .put("type", "image")
                    .put("data", image.data())
                    .put("mimeType", image.mimeType())
                    .build();
                case McpContent.Audio audio -> JsonObject.builder()
                    .put("type", "audio")
                    .put("data", audio.data())
                    .put("mimeType", audio.mimeType())
                    .build();
                case McpContent.Resource r -> JsonObject.builder()
                    .put("type", "resource")
                    .put("resource", switch (r.content()) {
                        case McpResourceContent.Text t -> JsonObject.builder()
                            .put("uri", t.uri())
                            .put("mimeType", t.mimeType())
                            .put("text", t.text())
                            .build();
                        case McpResourceContent.Blob b -> JsonObject.builder()
                            .put("uri", b.uri())
                            .put("mimeType", b.mimeType())
                            .put("blob", b.blob())
                            .build();
                    })
                    .build();
            };
            contentArray.add(contentNode);
        }

        final var resultBuilder = JsonObject.builder()
            .put("content", contentArray.build())
            .put("isError", toolResult.isError());
        toolResult.structuredContent().ifPresent(sc -> resultBuilder.put("structuredContent", sc));
        return resultBuilder.build();
    }

    private static JsonObject envelope(final JsonValue id, final JsonValue result) {
        final var builder = JsonObject.builder().put("jsonrpc", "2.0");
        addId(builder, id);
        builder.put("result", result);
        return builder.build();
    }

    private static JsonObject errorEnvelope(final JsonValue id, final int code, final String message) {
        final var error = JsonObject.builder()
            .put("code", code)
            .put("message", message)
            .build();
        final var builder = JsonObject.builder().put("jsonrpc", "2.0");
        addId(builder, id);
        builder.put("error", error);
        return builder.build();
    }

    private static void addId(final JsonObject.Builder builder, final JsonValue id) {
        if (id instanceof JsonNumber n) {
            builder.put("id", n.toNumber());
        } else if (id instanceof JsonString s) {
            builder.put("id", s.value());
        } else {
            builder.put("id", JsonNull.INSTANCE);
        }
    }

    private static String getString(final JsonObject obj, final String key) {
        final var val = obj.members().get(key);
        return val instanceof JsonString s ? s.value() : "";
    }

    private static String sanitizeMessage(final String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\r\\n\\t\\x00-\\x1F\\x7F]", " ");
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
         * A background daemon thread sweeps expired sessions at half the idle timeout interval,
         * capped at 5 minutes. Activity on a session (any JSON-RPC request or SSE connection)
         * resets the idle clock.
         *
         * @param idleTimeout the idle duration; must be at least 1 minute
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
                server.toolCallEvents().subscribe(McpToolCallLogger.of(server.recorder));
            }
            return server;
        }
    }
}
