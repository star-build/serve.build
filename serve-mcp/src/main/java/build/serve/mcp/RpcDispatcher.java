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

import build.base.flow.SubscriberRegistry;
import build.base.json.JsonArray;
import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonString;
import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes JSON-RPC requests to the handler for each MCP method, and runs batches. Transports
 * hand it parsed requests; it knows nothing of HTTP or stdio.
 *
 * <p>A handler returns the {@code result} of its response, or throws {@link McpError} to answer
 * with a JSON-RPC error.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class RpcDispatcher {

    static final String SUPPORTED_PROTOCOL_VERSION = "2025-03-26";

    private static final int MAX_COMPLETION_VALUES = 100;

    private final McpServerInfo info;
    private final String instructions;
    private final McpCatalog catalog;
    private final SessionRegistry sessions;
    private final TelemetryRecorder recorder;
    private final int maxBatchSize;
    private final int maxConcurrentRequests;
    private final int maxSubscriptionsPerSession;
    private final SubscriberRegistry<ToolCallEvent> toolCallEvents = new SubscriberRegistry<>();
    private final AtomicLong invocationCounter = new AtomicLong();

    RpcDispatcher(final McpServerInfo info,
                  final String instructions,
                  final McpCatalog catalog,
                  final SessionRegistry sessions,
                  final TelemetryRecorder recorder,
                  final int maxBatchSize,
                  final int maxConcurrentRequests,
                  final int maxSubscriptionsPerSession) {
        this.info = info;
        this.instructions = instructions;
        this.catalog = catalog;
        this.sessions = sessions;
        this.recorder = recorder;
        this.maxBatchSize = maxBatchSize;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.maxSubscriptionsPerSession = maxSubscriptionsPerSession;
        // A subscriber that throws is terminated; without this the failure (and every later event the
        // subscriber would have seen) vanishes without a trace.
        this.toolCallEvents.onSubscriberError((subscriber, error) ->
            recorder.warn(error, "tool-call event subscriber threw and was terminated"));
    }

    SubscriberRegistry<ToolCallEvent> toolCallEvents() {
        return toolCallEvents;
    }

    // --- Dispatch ---

    /**
     * The in-flight permits a batch needs: one per item, as each runs concurrently. A batch larger
     * than {@code maxConcurrentRequests} takes all of them so that it can still run, alone. A batch
     * that is empty or over {@code maxBatchSize} is rejected without running any item, so takes one.
     */
    int permitsFor(final JsonArray batch) {
        final var size = batch.values().size();
        if (size == 0 || size > maxBatchSize) {
            return 1;
        }
        return Math.min(size, maxConcurrentRequests);
    }

    /**
     * Handles one request.
     *
     * @return the response, or empty if the message is a notification, which gets none
     */
    Optional<JsonObject> dispatch(final JsonObject request, final String sessionId) {
        final var state = sessions.find(sessionId);
        if (state != null) {
            state.lastAccessed = Instant.now();
        }

        final var method = McpJson.string(request, "method");
        final var id = request.members().get("id");

        recorder.diagnostic("received: %s", method);

        if (id == null || id instanceof JsonNull) {
            return Optional.empty();
        }

        final var params = McpJson.paramsOf(request.members().getOrDefault("params", JsonNull.INSTANCE));
        JsonObject response;
        try {
            response = McpJson.envelope(id, handle(method, params, sessionId));
        } catch (final McpError e) {
            response = McpJson.errorEnvelope(id, e.code(), e.getMessage());
        }

        recorder.diagnostic("sent response to: %s", method);
        return Optional.of(response);
    }

    private JsonObject handle(final String method, final JsonObject params, final String sessionId) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> JsonObject.builder().build();
            case "tools/list" -> listTools();
            case "tools/call" -> callTool(params, sessionId);
            case "resources/list" -> listResources();
            case "resources/templates/list" -> listResourceTemplates();
            case "resources/read" -> readResource(params);
            case "resources/subscribe" -> subscribe(params, sessionId);
            case "resources/unsubscribe" -> unsubscribe(params, sessionId);
            case "logging/setLevel" -> setLogLevel(params, sessionId);
            case "prompts/list" -> listPrompts();
            case "prompts/get" -> getPrompt(params);
            case "completion/complete" -> complete(params);
            default -> throw new McpError(McpError.METHOD_NOT_FOUND, "Method not found");
        };
    }

    /**
     * Handles a batch, running its items concurrently.
     *
     * @return the array of responses, or empty if no item produced one
     */
    Optional<JsonValue> dispatchBatch(final JsonArray batch, final String sessionId) {
        if (batch.values().isEmpty()) {
            return Optional.of(McpJson.errorEnvelope(JsonNull.INSTANCE, McpError.INVALID_REQUEST,
                "Invalid Request: empty batch"));
        }
        if (batch.values().size() > maxBatchSize) {
            return Optional.of(McpJson.errorEnvelope(JsonNull.INSTANCE, McpError.INVALID_REQUEST,
                "Invalid Request: batch exceeds " + maxBatchSize + " requests"));
        }
        final var responses = new ConcurrentLinkedQueue<JsonObject>();
        final var remaining = new CountDownLatch(batch.values().size());
        for (final var item : batch.values()) {
            if (!(item instanceof JsonObject request)) {
                responses.add(McpJson.invalidRequest());
                remaining.countDown();
                continue;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    dispatch(request, sessionId).ifPresent(responses::add);
                } catch (final Error e) {
                    // answer the request so the batch reply stays complete, then let the Error propagate
                    internalError(request).ifPresent(responses::add);
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
            return Optional.of(McpJson.errorEnvelope(JsonNull.INSTANCE, McpError.INTERNAL_ERROR,
                "Internal error: batch interrupted"));
        }
        if (responses.isEmpty()) {
            return Optional.empty();
        }
        final var array = JsonArray.builder();
        responses.forEach(array::add);
        return Optional.of(array.build());
    }

    /**
     * The response for a request whose handling failed unexpectedly, or empty if it was a
     * notification.
     */
    static Optional<JsonObject> internalError(final JsonObject request) {
        final var id = request.members().get("id");
        if (id == null || id instanceof JsonNull) {
            return Optional.empty();
        }
        return Optional.of(McpJson.errorEnvelope(id, McpError.INTERNAL_ERROR, "Internal error"));
    }

    // --- Lifecycle ---

    private JsonObject initialize(final JsonObject params) {
        final var requestedVersion = McpJson.string(params, "protocolVersion");
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

    // --- Tools ---

    private JsonObject listTools() {
        final var tools = JsonArray.builder();
        catalog.tools().values().forEach(tool -> tools.add(McpJson.tool(tool)));
        return JsonObject.builder().put("tools", tools.build()).build();
    }

    private JsonObject callTool(final JsonObject params, final String sessionId) {
        final var toolName = McpJson.string(params, "name");
        final var arguments = params.members().getOrDefault("arguments", JsonNull.INSTANCE);

        final var tool = catalog.tools().get(toolName);
        if (tool == null) {
            throw new McpError(McpError.INVALID_PARAMS, "Unknown tool: " + McpJson.sanitize(toolName));
        }

        final var meta = params.members().get("_meta");
        final var progressToken = meta instanceof JsonObject m ? m.members().get("progressToken") : null;
        final var hasProgressToken = progressToken != null && !(progressToken instanceof JsonNull);

        final var invocationId = invocationCounter.incrementAndGet();
        toolCallEvents.publish(ToolCallEvent.started(invocationId, sessionId, toolName, arguments));
        final var start = Instant.now();
        try {
            final McpToolResult toolResult;
            if (hasProgressToken) {
                final ProgressReporter reporter = (progress, total, message) ->
                    sendProgress(sessionId, progressToken, progress, total, message);
                toolResult = ScopedValue.where(McpServer.SESSION_ID, sessionId)
                    .where(McpServer.PROGRESS_REPORTER, reporter)
                    .call(() -> tool.call(arguments));
            } else {
                toolResult = ScopedValue.where(McpServer.SESSION_ID, sessionId).call(() -> tool.call(arguments));
            }
            final var duration = Duration.between(start, Instant.now());
            toolCallEvents.publish(
                ToolCallEvent.succeeded(invocationId, sessionId, toolName, arguments, toolResult, duration));
            return McpJson.toolResult(toolResult);
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
            return McpJson.toolResult(McpToolResult.error("Tool execution failed"));
        }
    }

    private void sendProgress(final String sessionId,
                              final JsonValue progressToken,
                              final double progress,
                              final OptionalDouble total,
                              final Optional<String> message) {
        final var params = JsonObject.builder()
            .put("progressToken", progressToken)
            .put("progress", progress);
        total.ifPresent(t -> params.put("total", t));
        message.ifPresent(m -> params.put("message", m));
        sessions.send(sessionId, McpJson.notification("notifications/progress", params.build()));
    }

    // --- Resources ---

    private JsonObject listResources() {
        final var resources = JsonArray.builder();
        catalog.resources().values().forEach(resource -> resources.add(McpJson.resource(resource)));
        return JsonObject.builder().put("resources", resources.build()).build();
    }

    private JsonObject listResourceTemplates() {
        final var templates = JsonArray.builder();
        catalog.templates().forEach(entry -> templates.add(McpJson.resourceTemplate(entry.template())));
        return JsonObject.builder().put("resourceTemplates", templates.build()).build();
    }

    private JsonObject readResource(final JsonObject params) {
        final var uri = McpJson.string(params, "uri");

        final var exact = catalog.resources().get(uri);
        final var template = exact != null ? null : catalog.templateMatching(uri)
            .orElseThrow(() -> new McpError(McpError.RESOURCE_NOT_FOUND,
                "Resource not found: " + McpJson.sanitize(uri)));

        final McpResourceContent content;
        try {
            content = exact != null ? exact.read() : template.read(uri);
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recorder.warn(e, "Failed to read resource %s", McpJson.sanitize(uri));
            throw new McpError(McpError.RESOURCE_NOT_FOUND, "Failed to read resource");
        }

        return JsonObject.builder()
            .put("contents", JsonArray.builder().add(McpJson.resourceContent(content)).build())
            .build();
    }

    private JsonObject subscribe(final JsonObject params, final String sessionId) {
        final var uri = McpJson.string(params, "uri");
        final var state = sessions.find(sessionId);
        if (state != null) {
            synchronized (state.subscriptions) {
                if (state.subscriptions.size() < maxSubscriptionsPerSession) {
                    state.subscriptions.add(uri);
                }
            }
        }
        return JsonObject.builder().build();
    }

    private JsonObject unsubscribe(final JsonObject params, final String sessionId) {
        final var uri = McpJson.string(params, "uri");
        final var state = sessions.find(sessionId);
        if (state != null) {
            state.subscriptions.remove(uri);
        }
        return JsonObject.builder().build();
    }

    // --- Logging ---

    private JsonObject setLogLevel(final JsonObject params, final String sessionId) {
        final var levelName = McpJson.string(params, "level");
        final var level = McpLogLevel.fromString(levelName)
            .orElseThrow(() -> new McpError(McpError.INVALID_PARAMS,
                "Invalid log level: " + McpJson.sanitize(levelName)));
        final var state = sessions.find(sessionId);
        if (state != null) {
            state.logLevel = level;
        }
        return JsonObject.builder().build();
    }

    // --- Prompts ---

    private JsonObject listPrompts() {
        final var prompts = JsonArray.builder();
        catalog.prompts().values().forEach(prompt -> prompts.add(McpJson.prompt(prompt)));
        return JsonObject.builder().put("prompts", prompts.build()).build();
    }

    private JsonObject getPrompt(final JsonObject params) {
        final var name = McpJson.string(params, "name");
        final var prompt = catalog.prompts().get(name);
        if (prompt == null) {
            throw new McpError(McpError.INVALID_PARAMS, "Unknown prompt: " + McpJson.sanitize(name));
        }

        final var arguments = new HashMap<String, String>();
        if (params.members().get("arguments") instanceof JsonObject argumentsObject) {
            for (final var entry : argumentsObject.members().entrySet()) {
                if (entry.getValue() instanceof JsonString s) {
                    arguments.put(entry.getKey(), s.value());
                }
            }
        }

        final McpPromptResult result;
        try {
            result = prompt.get(Map.copyOf(arguments));
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recorder.warn(e, "Prompt %s failed", McpJson.sanitize(name));
            throw new McpError(McpError.INTERNAL_ERROR, "Prompt rendering failed");
        }
        return McpJson.promptResult(result);
    }

    // --- Completion ---

    private JsonObject complete(final JsonObject params) {
        if (!(params.members().get("ref") instanceof JsonObject ref)
            || !(params.members().get("argument") instanceof JsonObject argument)) {
            throw new McpError(McpError.INVALID_PARAMS, "Invalid params: ref and argument are required");
        }

        final var refType = McpJson.string(ref, "type");
        final var argumentName = McpJson.string(argument, "name");
        final var argumentValue = McpJson.string(argument, "value");

        final List<String> values;
        try {
            values = switch (refType) {
                case "ref/prompt" -> {
                    final var prompt = catalog.prompts().get(McpJson.string(ref, "name"));
                    if (prompt == null) {
                        yield List.of();
                    }
                    yield prompt.arguments().stream()
                        .filter(a -> a.name().equals(argumentName))
                        .findFirst()
                        .flatMap(McpPromptArgument::completer)
                        .map(completer -> completer.apply(argumentValue))
                        .orElse(List.of());
                }
                case "ref/resource" -> catalog.templateDeclaring(McpJson.string(ref, "uri"))
                    .map(template -> template.complete(argumentName, argumentValue))
                    .orElse(List.of());
                default -> List.of();
            };
        } catch (final Exception e) {
            recorder.warn(e, "Completion for %s failed", McpJson.sanitize(refType));
            throw new McpError(McpError.INTERNAL_ERROR, "Completion failed");
        }

        final var capped = values.size() > MAX_COMPLETION_VALUES ? values.subList(0, MAX_COMPLETION_VALUES) : values;
        final var array = JsonArray.builder();
        capped.forEach(value -> array.add(JsonString.of(value)));
        return JsonObject.builder()
            .put("completion", JsonObject.builder().put("values", array.build()).build())
            .build();
    }
}
