package build.serve.mcp;

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

import build.base.json.Json;
import build.base.json.JsonArray;
import build.base.json.JsonBoolean;
import build.base.json.JsonNull;
import build.base.json.JsonNumber;
import build.base.json.JsonObject;
import build.base.json.JsonString;
import build.base.json.JsonValue;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestRequest;
import build.serve.testing.TestResponse;
import build.serve.testing.TestServer;

import java.util.List;
import java.util.Map;

/**
 * A fluent MCP test client that wraps a {@link TestServer} and handles session management
 * and JSON-RPC plumbing, so tests can focus on tool behavior rather than protocol details.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (var client = McpTestClient.start(mcpServer)) {
 *     client.initialize();
 *     var tools = client.listTools();
 *     var result = client.call("my_tool", Map.of("arg", "value"));
 * }
 * }</pre>
 */
final class McpTestClient implements AutoCloseable {

    private static final String MCP_PATH = "/mcp";

    private final TestServer server;
    String sessionId;
    private int nextId = 1;

    private McpTestClient(final TestServer server) {
        this.server = server;
    }

    static McpTestClient start(final McpServer mcpServer) {
        final var server = TestServer.of(
            RouterBuilder.create().route(MCP_PATH, mcpServer.handler()).build());
        return new McpTestClient(server);
    }

    /**
     * Sends {@code initialize}, captures the session ID from the response, and returns the
     * full JSON-RPC response for assertion.
     */
    JsonValue initialize() {
        final var response = postRaw(rpc("initialize", nextId++, Map.of()));
        sessionId = response.header("Mcp-Session-Id");
        return Json.parse(response.body());
    }

    /**
     * Returns the {@code tools} array from a {@code tools/list} call.
     */
    JsonValue listTools() {
        return postJson(rpc("tools/list", nextId++, Map.of())).asObject().get("result").asObject().get("tools");
    }

    /**
     * Returns the {@code resources} array from a {@code resources/list} call.
     */
    JsonValue listResources() {
        return postJson(rpc("resources/list", nextId++, Map.of())).asObject().get("result").asObject().get("resources");
    }

    /**
     * Sends a {@code resources/read} request and returns the full JSON-RPC response.
     */
    JsonValue readResource(final String uri) {
        return postJson(rpc("resources/read", nextId++, Map.of("uri", uri)));
    }

    /**
     * Returns the {@code prompts} array from a {@code prompts/list} call.
     */
    JsonValue listPrompts() {
        return postJson(rpc("prompts/list", nextId++, Map.of())).asObject().get("result").asObject().get("prompts");
    }

    /**
     * Sends a {@code prompts/get} request and returns the full JSON-RPC response.
     */
    JsonValue getPrompt(final String name, final Map<String, Object> arguments) {
        return postJson(rpc("prompts/get", nextId++, Map.of("name", name, "arguments", arguments)));
    }

    /**
     * Sends a {@code completion/complete} for a prompt argument and returns the full JSON-RPC response.
     */
    JsonValue completePromptArg(final String promptName, final String argName, final String value) {
        final var params = Map.<String, Object>of(
            "ref", Map.of("type", "ref/prompt", "name", promptName),
            "argument", Map.of("name", argName, "value", value));
        return postJson(rpc("completion/complete", nextId++, params));
    }

    /**
     * Sends a {@code completion/complete} for a resource template argument and returns the full JSON-RPC response.
     */
    JsonValue completeResourceArg(final String uriTemplate, final String argName, final String value) {
        final var params = Map.<String, Object>of(
            "ref", Map.of("type", "ref/resource", "uri", uriTemplate),
            "argument", Map.of("name", argName, "value", value));
        return postJson(rpc("completion/complete", nextId++, params));
    }

    /**
     * Calls the named tool and returns the {@code result} object.
     */
    JsonValue call(final String toolName, final Map<String, Object> arguments) {
        final var params = Map.<String, Object>of("name", toolName, "arguments", arguments);
        return postJson(rpc("tools/call", nextId++, params)).asObject().get("result");
    }

    /**
     * Sends a raw JSON-RPC request and returns the full response node.
     */
    JsonValue send(final String method, final Map<String, Object> params) {
        return postJson(rpc(method, nextId++, params));
    }

    /**
     * Opens a persistent SSE connection on GET /mcp using the current session ID.
     * The caller is responsible for closing the returned stream.
     */
    build.serve.testing.TestSseStream sseStream() {
        return server.sse("/mcp", sessionId != null ? java.util.Map.of("Mcp-Session-Id", sessionId) : java.util.Map.of());
    }

    /**
     * Returns the port the underlying test server is listening on.
     * Useful for raw socket connections that need to set restricted HTTP headers.
     */
    int port() {
        return server.port();
    }

    /**
     * Delegates to the underlying {@link TestServer} for direct HTTP access.
     */
    TestRequest get(final String path) {
        return server.get(path);
    }

    /**
     * Delegates to the underlying {@link TestServer} for direct HTTP access.
     */
    TestRequest post(final String path) {
        return server.post(path);
    }

    /**
     * Sends {@code DELETE /mcp} for the current session, terminating it.
     */
    TestResponse deleteSession() {
        var req = server.delete(MCP_PATH);
        if (sessionId != null) {
            req = req.header("Mcp-Session-Id", sessionId);
        }
        return req.send();
    }

    @Override
    public void close() {
        server.close();
    }

    private String rpc(final String method, final int id, final Map<String, Object> params) {
        return JsonObject.builder()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", toJsonValue(params))
            .build()
            .toJsonString();
    }

    private build.serve.testing.TestResponse postRaw(final String body) {
        var req = server.post(MCP_PATH).header("Content-Type", "application/json");
        if (sessionId != null) {
            req = req.header("Mcp-Session-Id", sessionId);
        }
        return req.body(body).send();
    }

    private JsonValue postJson(final String body) {
        return Json.parse(postRaw(body).body());
    }

    @SuppressWarnings("unchecked")
    private static JsonValue toJsonValue(final Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String s) {
            return JsonString.of(s);
        }
        if (value instanceof Number n) {
            return JsonNumber.of(n);
        }
        if (value instanceof Boolean b) {
            return JsonBoolean.of(b);
        }
        if (value instanceof Map<?, ?> m) {
            final var builder = JsonObject.builder();
            for (final var entry : m.entrySet()) {
                builder.put((String) entry.getKey(), toJsonValue(entry.getValue()));
            }
            return builder.build();
        }
        if (value instanceof List<?> l) {
            final var builder = JsonArray.builder();
            for (final var item : l) {
                builder.add(toJsonValue(item));
            }
            return builder.build();
        }
        throw new IllegalArgumentException("Unsupported type for JSON conversion: " + value.getClass());
    }
}
