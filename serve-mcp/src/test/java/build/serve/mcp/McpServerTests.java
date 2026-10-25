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
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpServer}.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
class McpServerTests {

    private McpTestClient client;

    @BeforeEach
    void setUp() {
        final var location = ToolParam.string("location", "City name or zip code");
        final var mcpServer = McpServer.builder("test-server", "1.0.0")
            .tool(ToolDef.of("get_weather", "Get weather for a location")
                .param(location)
                .handle(args -> McpToolResult.text("Weather in " + location.extract(args) + ": sunny, 72°F")))
            .build();

        client = McpTestClient.start(mcpServer);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void initializeTest() {
        final var json = client.initialize().asObject();
        assertThat(json.getString("jsonrpc")).isEqualTo("2.0");
        final var result = json.get("result").asObject();
        assertThat(result.getString("protocolVersion")).isEqualTo("2025-03-26");
        assertThat(result.get("capabilities").asObject().get("tools").asObject().get("listChanged").asBoolean().value()).isFalse();
        assertThat(result.get("serverInfo").asObject().getString("name")).isEqualTo("test-server");
        assertThat(result.get("serverInfo").asObject().getString("version")).isEqualTo("1.0.0");
    }

    @Test
    void toolsListTest() {
        final var tools = client.listTools();
        assertThat(tools).isInstanceOf(JsonArray.class);
        assertThat(((JsonArray) tools).values()).hasSize(1);
        final var tool = ((JsonArray) tools).values().get(0).asObject();
        assertThat(tool.getString("name")).isEqualTo("get_weather");
        assertThat(tool.getString("description")).isEqualTo("Get weather for a location");
        assertThat(tool.get("inputSchema").asObject().getString("type")).isEqualTo("object");
    }

    @Test
    void toolsCallTest() {
        final var result = client.call("get_weather", Map.of("location", "Berlin")).asObject();
        assertThat(result.get("isError").asBoolean().value()).isFalse();
        final var content = ((JsonArray) result.get("content")).values().get(0).asObject();
        assertThat(content.getString("type")).isEqualTo("text");
        assertThat(content.getString("text")).isEqualTo("Weather in Berlin: sunny, 72°F");
    }

    @Test
    void shouldSerializeResourceContent() {
        try (final var resourceClient = McpTestClient.start(McpServer.builder("res-server", "1.0.0")
            .tool(ToolDef.of("get_file", "Returns a binary file")
                .handle(args -> McpToolResult.withResources("Here is the file.",
                    List.of(McpContent.Resource.blob("output.mid", "audio/midi", "TVRoZA==")))))
            .build())) {

            resourceClient.initialize();
            final var result = resourceClient.call("get_file", Map.of()).asObject();
            assertThat(result.get("isError").asBoolean().value()).isFalse();

            final var content = ((JsonArray) result.get("content")).values();
            final var text = content.get(0).asObject();
            assertThat(text.getString("type")).isEqualTo("text");
            assertThat(text.getString("text")).isEqualTo("Here is the file.");

            final var resource = content.get(1).asObject();
            assertThat(resource.getString("type")).isEqualTo("resource");
            final var resourceObj = resource.get("resource").asObject();
            assertThat(resourceObj.getString("uri")).isEqualTo("output.mid");
            assertThat(resourceObj.getString("mimeType")).isEqualTo("audio/midi");
            assertThat(resourceObj.getString("blob")).isEqualTo("TVRoZA==");
        }
    }

    @Test
    void shouldIncludeOutputSchemaInToolsList() {
        final var summary = ToolParam.string("summary", "Result summary");
        try (final var structuredClient = McpTestClient.start(McpServer.builder("structured-server", "1.0.0")
            .tool(ToolDef.of("summarize", "Summarizes something")
                .outputParam(summary)
                .handle(args -> McpToolResult.structured(JsonObject.builder().put("summary", "ok").build())))
            .build())) {

            structuredClient.initialize();
            final var tools = ((JsonArray) structuredClient.listTools()).values();
            final var tool = tools.get(0).asObject();
            final var outputSchema = tool.get("outputSchema").asObject();
            assertThat(outputSchema.getString("type")).isEqualTo("object");
            assertThat(outputSchema.get("properties").asObject().has("summary")).isTrue();
        }
    }

    @Test
    void shouldIncludeStructuredContentInToolsCallResult() {
        try (final var structuredClient = McpTestClient.start(McpServer.builder("structured-server", "1.0.0")
            .tool(ToolDef.of("summarize", "Summarizes something")
                .handle(args -> McpToolResult.structured(JsonObject.builder().put("summary", "ok").build())))
            .build())) {

            structuredClient.initialize();
            final var result = structuredClient.call("summarize", Map.of()).asObject();
            assertThat(result.get("isError").asBoolean().value()).isFalse();
            assertThat(result.get("structuredContent").asObject().getString("summary")).isEqualTo("ok");

            final var content = ((JsonArray) result.get("content")).values().get(0).asObject();
            assertThat(content.getString("type")).isEqualTo("text");
            assertThat(content.getString("text")).isEqualTo("{\"summary\":\"ok\"}");
        }
    }

    @Test
    void toolsCallUnknownTest() {
        final var json = client.send("tools/call",
            Map.of("name", "nonexistent", "arguments", Map.of())).asObject();
        assertThat(json.get("error").asObject().get("code").asNumber().toNumber().intValue()).isEqualTo(-32602);
        assertThat(json.get("error").asObject().getString("message")).contains("Unknown tool");
    }

    @Test
    void notificationIgnoredTest() {
        client.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
            .send()
            .assertStatus(202);
    }

    @Test
    void unknownMethodTest() {
        final var json = client.send("unknown/method", Map.of()).asObject();
        assertThat(json.get("error").asObject().get("code").asNumber().toNumber().intValue()).isEqualTo(-32601);
        assertThat(json.get("error").asObject().getString("message")).isEqualTo("Method not found");
    }

    @Test
    void shouldReturn400ForGetWithoutSession() {
        client.get("/mcp").send().assertStatus(400);
    }

    @Test
    void shouldRespondToPing() {
        final var json = client.send("ping", Map.of()).asObject();
        assertThat(json.get("result")).isInstanceOf(JsonObject.class);
    }

    @Test
    void shouldReturnSessionIdOnInitialize() {
        final var response = client.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
            .send()
            .assertStatus(200);
        assertThat(response.header("Mcp-Session-Id")).isNotBlank();
    }

    @Test
    void shouldAcceptValidSessionId() {
        client.initialize();
        final var tools = client.listTools();
        assertThat(tools).isInstanceOf(JsonArray.class);
    }

    @Test
    void shouldReturn404ForUnknownSessionId() {
        client.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", "unknown-session-id")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
            .send()
            .assertStatus(404);
    }

    @Test
    void shouldRespondWithSseWhenAcceptHeaderIncludesEventStream() {
        final var response = client.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
            .send()
            .assertStatus(200);
        assertThat(response.contentType()).contains("text/event-stream");
        assertThat(response.body()).startsWith("event: message\ndata: ");
        assertThat(response.body()).endsWith("\n\n");
    }

    @Test
    void shouldIncludeValidJsonInSseDataField() {
        final var response = client.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
            .send()
            .assertStatus(200);
        final var dataLine = response.body().lines()
            .filter(l -> l.startsWith("data: "))
            .findFirst()
            .orElseThrow();
        final var json = Json.parse(dataLine.substring("data: ".length())).asObject();
        assertThat(json.get("result").asObject().getString("protocolVersion")).isEqualTo("2025-03-26");
    }

    @Test
    void shouldReturnJsonWhenAcceptHeaderIsAbsent() {
        final var response = client.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
            .send()
            .assertStatus(200);
        assertThat(response.contentType()).contains("application/json");
        Json.parse(response.body()); // parses without error
    }

    @Test
    void shouldAdvertiseResourcesCapabilityOnInitialize() {
        final var result = client.initialize().asObject().get("result").asObject();
        final var resources = result.get("capabilities").asObject().get("resources").asObject();
        assertThat(resources.get("subscribe").asBoolean().value()).isTrue();
        assertThat(resources.get("listChanged").asBoolean().value()).isFalse();
    }

    @Test
    void shouldListResources() {
        try (final var resourceClient = McpTestClient.start(McpServer.builder("res-server", "1.0.0")
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "file:///data/config.json";
                }

                @Override
                public String name() {
                    return "Config";
                }

                @Override
                public Optional<String> description() {
                    return Optional.of("App config");
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.of("application/json");
                }

                @Override
                public McpResourceContent read() {
                    return new McpResourceContent.Text(uri(), "application/json", "{}");
                }
            })
            .build())) {

            resourceClient.initialize();
            final var resources = (JsonArray) resourceClient.listResources();
            assertThat(resources.values()).hasSize(1);
            final var r = resources.values().get(0).asObject();
            assertThat(r.getString("uri")).isEqualTo("file:///data/config.json");
            assertThat(r.getString("name")).isEqualTo("Config");
            assertThat(r.getString("description")).isEqualTo("App config");
            assertThat(r.getString("mimeType")).isEqualTo("application/json");
        }
    }

    @Test
    void shouldReadTextResource() {
        try (final var resourceClient = McpTestClient.start(McpServer.builder("res-server", "1.0.0")
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "file:///readme.txt";
                }

                @Override
                public String name() {
                    return "Readme";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.of("text/plain");
                }

                @Override
                public McpResourceContent read() {
                    return new McpResourceContent.Text(uri(), "text/plain", "Hello, world!");
                }
            })
            .build())) {

            resourceClient.initialize();
            final var response = resourceClient.readResource("file:///readme.txt").asObject();
            assertThat(response.members()).containsKey("result");
            final var contents = (JsonArray) response.get("result").asObject().get("contents");
            assertThat(contents.values()).hasSize(1);
            final var content = contents.values().get(0).asObject();
            assertThat(content.getString("uri")).isEqualTo("file:///readme.txt");
            assertThat(content.getString("mimeType")).isEqualTo("text/plain");
            assertThat(content.getString("text")).isEqualTo("Hello, world!");
        }
    }

    @Test
    void shouldReadBlobResource() {
        try (final var resourceClient = McpTestClient.start(McpServer.builder("res-server", "1.0.0")
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "file:///image.png";
                }

                @Override
                public String name() {
                    return "Image";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.of("image/png");
                }

                @Override
                public McpResourceContent read() {
                    return new McpResourceContent.Blob(uri(), "image/png", "iVBORw0KGgo=");
                }
            })
            .build())) {

            resourceClient.initialize();
            final var response = resourceClient.readResource("file:///image.png").asObject();
            final var content = ((JsonArray) response.get("result").asObject().get("contents")).values().get(0).asObject();
            assertThat(content.getString("mimeType")).isEqualTo("image/png");
            assertThat(content.getString("blob")).isEqualTo("iVBORw0KGgo=");
            assertThat(content.members()).doesNotContainKey("text");
        }
    }

    @Test
    void shouldReturnErrorForUnknownResource() {
        try (final var resourceClient = McpTestClient.start(McpServer.builder("res-server", "1.0.0").build())) {
            resourceClient.initialize();
            final var response = resourceClient.readResource("file:///missing.txt").asObject();
            assertThat(response.members()).containsKey("error");
            assertThat(response.get("error").asObject().get("code").asNumber().toNumber().intValue()).isEqualTo(-32002);
            assertThat(response.get("error").asObject().getString("message")).contains("missing.txt");
        }
    }

    @Test
    void shouldListEmptyResourcesWhenNoneRegistered() {
        final var resources = (JsonArray) client.listResources();
        assertThat(resources.values()).isEmpty();
    }

    @Test
    void shouldListResourceTemplates() {
        try (final var templateClient = McpTestClient.start(McpServer.builder("tmpl-server", "1.0.0")
            .template(new McpResourceTemplate() {
                @Override
                public String uriTemplate() {
                    return "file:///{path}";
                }

                @Override
                public String name() {
                    return "File";
                }

                @Override
                public Optional<String> description() {
                    return Optional.of("Any file");
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.of("text/plain");
                }

                @Override
                public McpResourceContent read(final String uri) {
                    return new McpResourceContent.Text(uri, "text/plain", "contents of " + uri);
                }
            })
            .build())) {

            templateClient.initialize();
            final var result = templateClient.send("resources/templates/list", Map.of()).asObject()
                .get("result").asObject();
            final var templates = (JsonArray) result.get("resourceTemplates");
            assertThat(templates.values()).hasSize(1);
            final var t = templates.values().get(0).asObject();
            assertThat(t.getString("uriTemplate")).isEqualTo("file:///{path}");
            assertThat(t.getString("name")).isEqualTo("File");
            assertThat(t.getString("description")).isEqualTo("Any file");
            assertThat(t.getString("mimeType")).isEqualTo("text/plain");
        }
    }

    @Test
    void shouldReadResourceMatchedByTemplate() {
        try (final var templateClient = McpTestClient.start(McpServer.builder("tmpl-server", "1.0.0")
            .template(new McpResourceTemplate() {
                @Override
                public String uriTemplate() {
                    return "db://{table}/{id}";
                }

                @Override
                public String name() {
                    return "DB Row";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.of("application/json");
                }

                @Override
                public McpResourceContent read(final String uri) {
                    return new McpResourceContent.Text(uri, "application/json", "{\"uri\":\"" + uri + "\"}");
                }
            })
            .build())) {

            templateClient.initialize();
            final var response = templateClient.readResource("db://users/42").asObject();
            final var content = ((JsonArray) response.get("result").asObject().get("contents"))
                .values().get(0).asObject();
            assertThat(content.getString("uri")).isEqualTo("db://users/42");
            assertThat(content.getString("text")).contains("db://users/42");
        }
    }

    @Test
    void shouldPreferExactResourceOverTemplate() {
        try (final var mixedClient = McpTestClient.start(McpServer.builder("mixed-server", "1.0.0")
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "file:///special.txt";
                }

                @Override
                public String name() {
                    return "Special";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.empty();
                }

                @Override
                public McpResourceContent read() {
                    return new McpResourceContent.Text(uri(), "text/plain", "exact match");
                }
            })
            .template(new McpResourceTemplate() {
                @Override
                public String uriTemplate() {
                    return "file:///{path}";
                }

                @Override
                public String name() {
                    return "File";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.empty();
                }

                @Override
                public McpResourceContent read(final String uri) {
                    return new McpResourceContent.Text(uri, "text/plain", "template match");
                }
            })
            .build())) {

            mixedClient.initialize();
            final var content = ((JsonArray) mixedClient.readResource("file:///special.txt")
                .asObject().get("result").asObject().get("contents"))
                .values().get(0).asObject();
            assertThat(content.getString("text")).isEqualTo("exact match");
        }
    }

    @Test
    void shouldReturnErrorForUriMatchingNoTemplateOrResource() {
        final var response = client.readResource("file:///missing.txt").asObject();
        assertThat(response.members()).containsKey("error");
        assertThat(response.get("error").asObject().get("code").asNumber().toNumber().intValue()).isEqualTo(-32002);
    }

    @Test
    void shouldSubscribeAndReceiveNotification() throws InterruptedException {
        final var mcpServer = McpServer.builder("sub-server", "1.0.0").build();
        try (final var subClient = McpTestClient.start(mcpServer)) {
            subClient.initialize();

            try (final var stream = subClient.sseStream()) {
                Thread.sleep(100); // let SSE connection establish

                final var subResponse = subClient.send("resources/subscribe",
                    Map.of("uri", "file:///data.txt")).asObject();
                assertThat(subResponse.members()).containsKey("result");

                mcpServer.notifyResourceChanged("file:///data.txt");

                final var events = stream.collect(1, java.time.Duration.ofSeconds(5));
                assertThat(events).hasSize(1);
                final var notification = Json.parse(events.get(0).data()).asObject();
                assertThat(notification.getString("method")).isEqualTo("notifications/resources/updated");
                assertThat(notification.get("params").asObject().getString("uri")).isEqualTo("file:///data.txt");
            }
        }
    }

    @Test
    void shouldNotReceiveNotificationAfterUnsubscribe() throws InterruptedException {
        final var mcpServer = McpServer.builder("sub-server", "1.0.0").build();
        try (final var subClient = McpTestClient.start(mcpServer)) {
            subClient.initialize();

            try (final var stream = subClient.sseStream()) {
                Thread.sleep(100);

                subClient.send("resources/subscribe", Map.of("uri", "file:///data.txt"));
                subClient.send("resources/unsubscribe", Map.of("uri", "file:///data.txt"));

                mcpServer.notifyResourceChanged("file:///data.txt");

                assertThat(stream.poll(java.time.Duration.ofMillis(300))).isEmpty();
            }
        }
    }

    @Test
    void shouldNotNotifyUnsubscribedUri() throws InterruptedException {
        final var mcpServer = McpServer.builder("sub-server", "1.0.0").build();
        try (final var subClient = McpTestClient.start(mcpServer)) {
            subClient.initialize();

            try (final var stream = subClient.sseStream()) {
                Thread.sleep(100);

                subClient.send("resources/subscribe", Map.of("uri", "file:///a.txt"));

                mcpServer.notifyResourceChanged("file:///b.txt");

                assertThat(stream.poll(java.time.Duration.ofMillis(300))).isEmpty();
            }
        }
    }

    @Test
    void shouldSerializeAudioContent() {
        try (final var audioClient = McpTestClient.start(McpServer.builder("audio-server", "1.0.0")
            .tool(ToolDef.of("get_audio", "Returns audio")
                .handle(args -> new McpToolResult(List.of(new McpContent.Audio("UklGRg==", "audio/wav")), false)))
            .build())) {

            audioClient.initialize();
            final var result = audioClient.call("get_audio", Map.of()).asObject();
            final var content = ((JsonArray) result.get("content")).values().get(0).asObject();
            assertThat(content.getString("type")).isEqualTo("audio");
            assertThat(content.getString("data")).isEqualTo("UklGRg==");
            assertThat(content.getString("mimeType")).isEqualTo("audio/wav");
        }
    }

    @Test
    void shouldSerializeTextEmbeddedResource() {
        try (final var textClient = McpTestClient.start(McpServer.builder("text-server", "1.0.0")
            .tool(ToolDef.of("get_doc", "Returns a doc")
                .handle(args -> McpToolResult.withResources("Here is the doc.",
                    List.of(McpContent.Resource.text("readme.txt", "text/plain", "Hello!")))))
            .build())) {

            textClient.initialize();
            final var result = textClient.call("get_doc", Map.of()).asObject();
            final var content = ((JsonArray) result.get("content")).values();
            final var resource = content.get(1).asObject();
            assertThat(resource.getString("type")).isEqualTo("resource");
            final var resourceObj = resource.get("resource").asObject();
            assertThat(resourceObj.getString("uri")).isEqualTo("readme.txt");
            assertThat(resourceObj.getString("text")).isEqualTo("Hello!");
            assertThat(resourceObj.members()).doesNotContainKey("blob");
        }
    }

    @Test
    void shouldAllowRequestWithPermittedOrigin() throws IOException {
        try (final var originClient = McpTestClient.start(McpServer.builder("origin-server", "1.0.0")
            .allowOrigin("https://app.example.com")
            .build())) {

            assertThat(rawPost(originClient.port(), Map.of("Origin", "https://app.example.com"))).isEqualTo(200);
        }
    }

    @Test
    void shouldRejectRequestWithDisallowedOrigin() throws IOException {
        try (final var originClient = McpTestClient.start(McpServer.builder("origin-server", "1.0.0")
            .allowOrigin("https://app.example.com")
            .build())) {

            assertThat(rawPost(originClient.port(), Map.of("Origin", "https://evil.example.com"))).isEqualTo(403);
        }
    }

    @Test
    void shouldAllowRequestWithNoOriginWhenOriginsConfigured() throws IOException {
        try (final var originClient = McpTestClient.start(McpServer.builder("origin-server", "1.0.0")
            .allowOrigin("https://app.example.com")
            .build())) {

            assertThat(rawPost(originClient.port(), Map.of())).isEqualTo(200);
        }
    }

    @Test
    void shouldRejectRequestWithOriginByDefault() throws IOException {
        try (final var defaultClient = McpTestClient.start(McpServer.builder("default-origin-server", "1.0.0")
            .build())) {

            assertThat(rawPost(defaultClient.port(), Map.of("Origin", "https://evil.example.com"))).isEqualTo(403);
        }
    }

    @Test
    void shouldAllowRequestWithNoOriginByDefault() throws IOException {
        try (final var defaultClient = McpTestClient.start(McpServer.builder("default-origin-server", "1.0.0")
            .build())) {

            assertThat(rawPost(defaultClient.port(), Map.of())).isEqualTo(200);
        }
    }

    @Test
    void shouldRejectRequestWithNonLoopbackHostByDefault() throws IOException {
        try (final var defaultClient = McpTestClient.start(McpServer.builder("default-host-server", "1.0.0")
            .build())) {

            assertThat(rawPostWithHost(defaultClient.port(), "attacker.example.com")).isEqualTo(403);
        }
    }

    @Test
    void shouldAllowRequestWithAllowlistedHost() throws IOException {
        try (final var hostClient = McpTestClient.start(McpServer.builder("allow-host-server", "1.0.0")
            .allowHost("mcp.example.com")
            .build())) {

            assertThat(rawPostWithHost(hostClient.port(), "mcp.example.com")).isEqualTo(200);
        }
    }

    @Test
    void shouldRejectLoopbackHostOnceAllowlistIsConfigured() throws IOException {
        try (final var hostClient = McpTestClient.start(McpServer.builder("allow-host-server", "1.0.0")
            .allowHost("mcp.example.com")
            .build())) {

            assertThat(rawPostWithHost(hostClient.port(), "127.0.0.1")).isEqualTo(403);
        }
    }

    @Test
    void shouldIncludeInstructionsInInitializeResult() {
        try (final var instrClient = McpTestClient.start(McpServer.builder("instr-server", "1.0.0")
            .instructions("Use the get_weather tool to answer weather questions.")
            .build())) {

            final var result = instrClient.initialize().asObject().get("result").asObject();
            assertThat(result.getString("instructions")).isEqualTo("Use the get_weather tool to answer weather questions.");
        }
    }

    @Test
    void shouldOmitInstructionsWhenNotSet() {
        final var result = client.initialize().asObject().get("result").asObject();
        assertThat(result.members()).doesNotContainKey("instructions");
    }

    @Test
    void shouldIncludeAnnotationsInToolsList() {
        try (final var annClient = McpTestClient.start(McpServer.builder("ann-server", "1.0.0")
            .tool(ToolDef.of("delete_record", "Deletes a record")
                .annotations(McpToolAnnotations.builder()
                    .destructiveHint(true)
                    .idempotentHint(false)
                    .readOnlyHint(false)
                    .audience(List.of("assistant"))
                    .build())
                .handle(args -> McpToolResult.text("deleted")))
            .build())) {

            annClient.initialize();
            final var tool = ((JsonArray) annClient.listTools()).values().get(0).asObject();
            final var ann = tool.get("annotations").asObject();
            assertThat(ann.get("destructiveHint").asBoolean().value()).isTrue();
            assertThat(ann.get("idempotentHint").asBoolean().value()).isFalse();
            assertThat(ann.get("readOnlyHint").asBoolean().value()).isFalse();
            final var audience = (JsonArray) ann.get("audience");
            assertThat(audience.values()).hasSize(1);
            assertThat(audience.values().get(0).asString().value()).isEqualTo("assistant");
        }
    }

    @Test
    void shouldOmitAnnotationsWhenNotSet() {
        client.initialize();
        final var tool = ((JsonArray) client.listTools()).values().get(0).asObject();
        assertThat(tool.members()).doesNotContainKey("annotations");
    }

    @Test
    void shouldIncludeSizeInResourcesList() {
        try (final var sizeClient = McpTestClient.start(McpServer.builder("size-server", "1.0.0")
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "file:///data.bin";
                }

                @Override
                public String name() {
                    return "Data";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.of("application/octet-stream");
                }

                @Override
                public Optional<Long> size() {
                    return Optional.of(1024L);
                }

                @Override
                public McpResourceContent read() {
                    return new McpResourceContent.Blob(uri(), "application/octet-stream", "");
                }
            })
            .build())) {

            sizeClient.initialize();
            final var resource = ((JsonArray) sizeClient.listResources()).values().get(0).asObject();
            assertThat(resource.get("size").asNumber().toNumber().longValue()).isEqualTo(1024L);
        }
    }

    @Test
    void shouldOmitSizeWhenNotProvided() {
        try (final var nosizeClient = McpTestClient.start(McpServer.builder("nosize-server", "1.0.0")
            .resource(new McpResource() {
                @Override
                public String uri() {
                    return "file:///data.bin";
                }

                @Override
                public String name() {
                    return "Data";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.empty();
                }

                @Override
                public McpResourceContent read() {
                    return new McpResourceContent.Text(uri(), "text/plain", "");
                }
            })
            .build())) {

            nosizeClient.initialize();
            final var resource = ((JsonArray) nosizeClient.listResources()).values().get(0).asObject();
            assertThat(resource.members()).doesNotContainKey("size");
        }
    }

    @Test
    void shouldEchoStringIdInResponse() {
        final var response = client.post("/mcp")
            .header("Content-Type", "application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":\"req-abc\",\"method\":\"ping\",\"params\":{}}")
            .send()
            .assertStatus(200);
        final var json = Json.parse(response.body()).asObject();
        assertThat(json.getString("id")).isEqualTo("req-abc");
    }

    @Test
    void shouldReturn200ForDeleteRegardlessOfSessionId() {
        final var mcp = McpServer.builder("s", "1").build();
        final var srv = TestServer.of(
            RouterBuilder.create()
                .route("/mcp", mcp.handler()).build());
        try (srv) {
            assertThat(srv.delete("/mcp").send().status()).isEqualTo(200);
            assertThat(srv.delete("/mcp").header("Mcp-Session-Id", "unknown").send().status()).isEqualTo(200);
        }
    }

    @Test
    void shouldRejectDeletedSessionOnNextRequest() {
        final var mcp = McpServer.builder("s", "1").build();
        final var srv = TestServer.of(
            RouterBuilder.create()
                .route("/mcp", mcp.handler()).build());
        try (srv) {
            final var initResp = srv.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .send();
            final var sessionId = initResp.header("Mcp-Session-Id");

            srv.delete("/mcp").header("Mcp-Session-Id", sessionId).send().assertStatus(200);

            final var followUp = srv.post("/mcp")
                .header("Content-Type", "application/json")
                .header("Mcp-Session-Id", sessionId)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")
                .send();
            assertThat(followUp.status()).isEqualTo(404);
        }
    }

    @Test
    void shouldRejectInitializeWhenSessionCapReached() {
        final var mcp = McpServer.builder("s", "1").maxSessions(1).build();
        final var srv = TestServer.of(
            RouterBuilder.create()
                .route("/mcp", mcp.handler()).build());
        try (srv) {
            srv.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .send().assertStatus(200);
            final var second = srv.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}")
                .send();
            assertThat(second.status()).isEqualTo(503);
        }
    }

    @Test
    void shouldRejectInitializeWhenSessionIdPresent() {
        final var response = client.post("/mcp")
            .header("Content-Type", "application/json")
            .header("Mcp-Session-Id", "any-value")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
            .send();
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void shouldSanitizeExceptionMessageInToolError() {
        // Raw McpTool intentionally — tests server-level CRLF sanitization of thrown exceptions,
        // which ToolDef.call() would swallow before the server sees them.
        final var badTool = new McpTool() {
            @Override
            public String name() {
                return "bad_tool";
            }

            @Override
            public String description() {
                return "throws";
            }

            @Override
            public JsonObject inputSchema() {
                return ToolDef.of("bad_tool", "throws").handle(a -> null).inputSchema();
            }

            @Override
            public McpToolResult call(final JsonValue arguments) throws Exception {
                throw new RuntimeException("error\r\nX-Injected: evil");
            }
        };
        final var errorServer = McpServer.builder("s", "1").tool(badTool).build();
        try (var errorClient = McpTestClient.start(errorServer)) {
            errorClient.initialize();
            final var result = errorClient.call("bad_tool", Map.of());
            final var content = ((JsonArray) result.asObject().members().get("content")).values();
            final var text = content.get(0).asObject().getString("text");
            assertThat(text).doesNotContain("\r").doesNotContain("\n");
            assertThat(text).contains("error");
        }
    }

    @Test
    void shouldEvictIdleSessionAfterTimeout() throws InterruptedException {
        final var mcp = McpServer.builder("s", "1")
            .maxSessions(1)
            .sessionIdleTimeout(Duration.ofMillis(200))
            .build();
        final var srv = TestServer.of(RouterBuilder.create().route("/mcp", mcp.handler()).build());
        try (srv) {
            srv.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .send().assertStatus(200);

            srv.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}")
                .send().assertStatus(503);

            Thread.sleep(500); // wait for reaper (sweeps at 100ms, idle timeout 200ms)

            assertThat(srv.post("/mcp")
                .header("Content-Type", "application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{}}")
                .send().status()).isEqualTo(200);
        }
    }

    @Test
    void shouldCloseOldSseEmitterOnReconnect() throws InterruptedException {
        final var mcpServer = McpServer.builder("sse-server", "1.0.0").build();
        try (final var sseClient = McpTestClient.start(mcpServer)) {
            sseClient.initialize();

            final var stream1 = sseClient.sseStream();
            Thread.sleep(100); // let stream1 establish on the server

            try (final var ignored = sseClient.sseStream()) {
                // stream2 connecting causes the server to close stream1's emitter;
                // collectAll waits for the done future, which completes when the reader exits
                final var events = stream1.collectAll(Duration.ofSeconds(3));
                assertThat(events).isEmpty();
            }
        }
    }

    @Test
    void shouldCloseSseEmitterWhenIdleSessionIsEvicted() throws InterruptedException {
        final var mcpServer = McpServer.builder("sse-server", "1.0.0")
            .sessionIdleTimeout(Duration.ofMillis(200))
            .build();
        try (final var sseClient = McpTestClient.start(mcpServer)) {
            sseClient.initialize();

            try (final var stream = sseClient.sseStream()) {
                Thread.sleep(100); // let the stream establish on the server

                // collectAll fails if the server never closes the stream
                assertThat(stream.collectAll(Duration.ofSeconds(3))).isEmpty();
            }
        }
    }

    @Test
    void shouldCloseSseEmitterWhenSessionIsDeleted() throws InterruptedException {
        final var mcpServer = McpServer.builder("sse-server", "1.0.0").build();
        try (final var sseClient = McpTestClient.start(mcpServer)) {
            sseClient.initialize();

            try (final var stream = sseClient.sseStream()) {
                Thread.sleep(100); // let the stream establish on the server

                sseClient.deleteSession().assertStatus(200);

                assertThat(stream.collectAll(Duration.ofSeconds(3))).isEmpty();
            }
        }
    }

    @Test
    void shouldReturnInvalidRequestForHttpBatchOverDefaultLimit() {
        final var mcpServer = McpServer.builder("batch-server", "1.0.0").build();
        try (final var testClient = McpTestClient.start(mcpServer)) {
            final var response = testClient.post("/mcp")
                .header("Content-Type", "application/json")
                .body(emptyBatch(70_000))
                .send()
                .assertStatus(200);

            final var error = Json.parse(response.body()).asObject().get("error").asObject();
            assertThat(error.get("code").asNumber().toNumber().intValue()).isEqualTo(-32600);
        }
    }

    @Test
    void shouldReturnInvalidRequestForHttpBatchOverConfiguredLimit() {
        final var mcpServer = McpServer.builder("batch-server", "1.0.0").maxBatchSize(2).build();
        try (final var testClient = McpTestClient.start(mcpServer)) {
            testClient.post("/mcp")
                .header("Content-Type", "application/json")
                .body("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]")
                .send()
                .assertStatus(200);

            final var response = testClient.post("/mcp")
                .header("Content-Type", "application/json")
                .body("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}]")
                .send();

            final var error = Json.parse(response.body()).asObject().get("error").asObject();
            assertThat(error.get("code").asNumber().toNumber().intValue()).isEqualTo(-32600);
        }
    }

    @Test
    @Timeout(60)
    void shouldHandleHttpBatchWithMoreConcurrentItemsThan65535WhenConfigured() {
        final int size = 66_000;
        final var started = new AtomicInteger();
        final var gate = new CountDownLatch(1);
        final var mcpServer = McpServer.builder("batch-server", "1.0.0")
            .maxBatchSize(100_000)
            .tool(ToolDef.of("hold", "Holds until every batch item is in flight")
                .handle(args -> {
                    if (started.incrementAndGet() >= size) {
                        gate.countDown();
                    }
                    try {
                        gate.await(15, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return McpToolResult.text("ok");
                }))
            .build();
        final var item = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"hold\"}}";
        try (final var testClient = McpTestClient.start(mcpServer)) {
            final var response = testClient.post("/mcp")
                .header("Content-Type", "application/json")
                .body("[" + String.join(",", Collections.nCopies(size, item)) + "]")
                .send()
                .assertStatus(200);

            assertThat(Json.parse(response.body()).asArray().values()).hasSize(size);
        }
    }

    private static String emptyBatch(final int size) {
        return "[" + String.join(",", Collections.nCopies(size, "{}")) + "]";
    }

    @Test
    void shouldHandleHttpBatchRequest() {
        final var mcpServer = McpServer.builder("batch-server", "1.0.0").build();
        try (final var testClient = McpTestClient.start(mcpServer)) {
            testClient.initialize();

            final var batch = "[{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\",\"params\":{}},"
                + "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\",\"params\":{}}]";
            final var response = testClient.post("/mcp")
                .header("Content-Type", "application/json")
                .header("Mcp-Session-Id", testClient.sessionId)
                .body(batch)
                .send();

            assertThat(response.status()).isEqualTo(200);
            final var arr = Json.parse(response.body()).asArray();
            assertThat(arr.values()).hasSize(2);
            final var ids = arr.values().stream()
                .map(v -> ((build.base.json.JsonNumber) v.asObject().members().get("id")).toNumber().intValue())
                .toList();
            assertThat(ids).containsExactlyInAnyOrder(10, 11);
        }
    }

    @Test
    void shouldReturn202ForHttpBatchOfNotificationsOnly() {
        final var mcpServer = McpServer.builder("batch-server", "1.0.0").build();
        try (final var testClient = McpTestClient.start(mcpServer)) {
            testClient.initialize();

            final var batch = "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]";
            final var response = testClient.post("/mcp")
                .header("Content-Type", "application/json")
                .header("Mcp-Session-Id", testClient.sessionId)
                .body(batch)
                .send();

            assertThat(response.status()).isEqualTo(202);
        }
    }

    @Test
    void shouldAdvertisePromptsCapabilityOnInitialize() {
        final var result = client.initialize().asObject().get("result").asObject();
        final var prompts = result.get("capabilities").asObject().get("prompts").asObject();
        assertThat(prompts.get("listChanged").asBoolean().value()).isTrue();
    }

    @Test
    void shouldListEmptyPromptsWhenNoneRegistered() {
        client.initialize();
        final var prompts = (JsonArray) client.listPrompts();
        assertThat(prompts.values()).isEmpty();
    }

    @Test
    void shouldListRegisteredPrompt() {
        try (final var promptClient = McpTestClient.start(McpServer.builder("p-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "greet";
                }

                @Override
                public Optional<String> description() {
                    return Optional.of("Greets someone");
                }

                @Override
                public List<McpPromptArgument> arguments() {
                    return List.of(McpPromptArgument.required("name", "Person to greet"));
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userText("Hello, " + arguments.get("name") + "!"));
                }
            })
            .build())) {

            promptClient.initialize();
            final var prompts = (JsonArray) promptClient.listPrompts();
            assertThat(prompts.values()).hasSize(1);
            final var p = prompts.values().get(0).asObject();
            assertThat(p.getString("name")).isEqualTo("greet");
            assertThat(p.getString("description")).isEqualTo("Greets someone");
            final var args = (JsonArray) p.get("arguments");
            assertThat(args.values()).hasSize(1);
            final var arg = args.values().get(0).asObject();
            assertThat(arg.getString("name")).isEqualTo("name");
            assertThat(arg.getString("description")).isEqualTo("Person to greet");
            assertThat(arg.get("required").asBoolean().value()).isTrue();
        }
    }

    @Test
    void shouldOmitArgumentsWhenPromptHasNone() {
        try (final var promptClient = McpTestClient.start(McpServer.builder("p-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "static_prompt";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userText("Fixed content"));
                }
            })
            .build())) {

            promptClient.initialize();
            final var p = ((JsonArray) promptClient.listPrompts()).values().get(0).asObject();
            assertThat(p.members()).doesNotContainKey("arguments");
            assertThat(p.members()).doesNotContainKey("description");
        }
    }

    @Test
    void shouldGetPromptWithTextMessage() {
        try (final var promptClient = McpTestClient.start(McpServer.builder("p-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "greet";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(
                        "Greeting prompt",
                        McpPromptMessage.userText("Hello, " + arguments.getOrDefault("name", "world") + "!")
                    );
                }
            })
            .build())) {

            promptClient.initialize();
            final var response = promptClient.getPrompt("greet", Map.of("name", "Alice")).asObject();
            assertThat(response.members()).containsKey("result");
            final var result = response.get("result").asObject();
            assertThat(result.getString("description")).isEqualTo("Greeting prompt");
            final var messages = (JsonArray) result.get("messages");
            assertThat(messages.values()).hasSize(1);
            final var msg = messages.values().get(0).asObject();
            assertThat(msg.getString("role")).isEqualTo("user");
            final var content = msg.get("content").asObject();
            assertThat(content.getString("type")).isEqualTo("text");
            assertThat(content.getString("text")).isEqualTo("Hello, Alice!");
        }
    }

    @Test
    void shouldGetPromptWithImageMessage() {
        try (final var promptClient = McpTestClient.start(McpServer.builder("p-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "show_image";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userImage("iVBORw0KGgo=", "image/png"));
                }
            })
            .build())) {

            promptClient.initialize();
            final var result = promptClient.getPrompt("show_image", Map.of()).asObject().get("result").asObject();
            final var msg = ((JsonArray) result.get("messages")).values().get(0).asObject();
            assertThat(msg.getString("role")).isEqualTo("user");
            final var content = msg.get("content").asObject();
            assertThat(content.getString("type")).isEqualTo("image");
            assertThat(content.getString("data")).isEqualTo("iVBORw0KGgo=");
            assertThat(content.getString("mimeType")).isEqualTo("image/png");
        }
    }

    @Test
    void shouldGetPromptWithAssistantRole() {
        try (final var promptClient = McpTestClient.start(McpServer.builder("p-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "convo";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(
                        McpPromptMessage.userText("What is Java?"),
                        McpPromptMessage.assistantText("Java is a programming language.")
                    );
                }
            })
            .build())) {

            promptClient.initialize();
            final var messages = (JsonArray) promptClient.getPrompt("convo", Map.of())
                .asObject().get("result").asObject().get("messages");
            assertThat(messages.values()).hasSize(2);
            assertThat(messages.values().get(0).asObject().getString("role")).isEqualTo("user");
            assertThat(messages.values().get(1).asObject().getString("role")).isEqualTo("assistant");
        }
    }

    @Test
    void shouldOmitDescriptionFromGetPromptResultWhenAbsent() {
        try (final var promptClient = McpTestClient.start(McpServer.builder("p-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "nodesc";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userText("content"));
                }
            })
            .build())) {

            promptClient.initialize();
            final var result = promptClient.getPrompt("nodesc", Map.of()).asObject().get("result").asObject();
            assertThat(result.members()).doesNotContainKey("description");
        }
    }

    @Test
    void shouldReturnErrorForUnknownPrompt() {
        client.initialize();
        final var response = client.getPrompt("nonexistent", Map.of()).asObject();
        assertThat(response.members()).containsKey("error");
        assertThat(response.get("error").asObject().get("code").asNumber().toNumber().intValue()).isEqualTo(-32602);
        assertThat(response.get("error").asObject().getString("message")).contains("Unknown prompt");
    }

    @Test
    void shouldPushPromptsListChangedNotificationOverSse() throws InterruptedException {
        final var mcpServer = McpServer.builder("p-server", "1.0.0").build();
        try (final var promptClient = McpTestClient.start(mcpServer)) {
            promptClient.initialize();

            try (final var stream = promptClient.sseStream()) {
                Thread.sleep(100);

                mcpServer.notifyPromptsChanged();

                final var events = stream.collect(1, java.time.Duration.ofSeconds(5));
                assertThat(events).hasSize(1);
                final var notification = Json.parse(events.get(0).data()).asObject();
                assertThat(notification.getString("method")).isEqualTo("notifications/prompts/list_changed");
            }
        }
    }

    @Test
    void shouldAdvertiseCompletionsCapabilityOnInitialize() {
        final var result = client.initialize().asObject().get("result").asObject();
        assertThat(result.get("capabilities").asObject().members()).containsKey("completions");
    }

    @Test
    void shouldCompletePromptArgument() {
        try (final var completionClient = McpTestClient.start(McpServer.builder("c-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "greet";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public List<McpPromptArgument> arguments() {
                    return List.of(McpPromptArgument.required("city", "City name")
                        .withCompleter(v -> List.of("Berlin", "Boston", "Barcelona")
                            .stream().filter(c -> c.toLowerCase().startsWith(v.toLowerCase())).toList()));
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userText("Hello from " + arguments.get("city")));
                }
            })
            .build())) {

            completionClient.initialize();
            final var response = completionClient.completePromptArg("greet", "city", "B").asObject();
            assertThat(response.members()).containsKey("result");
            final var values = (JsonArray) response.get("result").asObject().get("completion").asObject().get("values");
            assertThat(values.values()).hasSize(3);
            assertThat(values.values().stream().map(v -> v.asString().value()).toList())
                .containsExactlyInAnyOrder("Berlin", "Boston", "Barcelona");
        }
    }

    @Test
    void shouldReturnEmptyCompletionForUnknownPrompt() {
        client.initialize();
        final var response = client.completePromptArg("nonexistent", "arg", "v").asObject();
        assertThat(response.members()).containsKey("result");
        final var values = (JsonArray) response.get("result").asObject().get("completion").asObject().get("values");
        assertThat(values.values()).isEmpty();
    }

    @Test
    void shouldReturnEmptyCompletionForArgumentWithNoCompleter() {
        try (final var noCompleterClient = McpTestClient.start(McpServer.builder("c-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "greet";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public List<McpPromptArgument> arguments() {
                    return List.of(McpPromptArgument.required("name", "Name"));
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userText("Hi"));
                }
            })
            .build())) {

            noCompleterClient.initialize();
            final var response = noCompleterClient.completePromptArg("greet", "name", "A").asObject();
            final var values = (JsonArray) response.get("result").asObject().get("completion").asObject().get("values");
            assertThat(values.values()).isEmpty();
        }
    }

    @Test
    void shouldCompleteResourceTemplateArgument() {
        try (final var templateClient = McpTestClient.start(McpServer.builder("c-server", "1.0.0")
            .template(new McpResourceTemplate() {
                @Override
                public String uriTemplate() {
                    return "db://{table}/{id}";
                }

                @Override
                public String name() {
                    return "DB Row";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> mimeType() {
                    return Optional.empty();
                }

                @Override
                public McpResourceContent read(final String uri) {
                    return new McpResourceContent.Text(uri, "application/json", "{}");
                }

                @Override
                public List<String> complete(final String argumentName, final String value) {
                    if ("table".equals(argumentName)) {
                        return List.of("users", "orders").stream()
                            .filter(t -> t.startsWith(value)).toList();
                    }
                    return List.of();
                }
            })
            .build())) {

            templateClient.initialize();
            final var response = templateClient.completeResourceArg("db://{table}/{id}", "table", "u").asObject();
            assertThat(response.members()).containsKey("result");
            final var values = (JsonArray) response.get("result").asObject().get("completion").asObject().get("values");
            assertThat(values.values()).hasSize(1);
            assertThat(values.values().get(0).asString().value()).isEqualTo("users");
        }
    }

    @Test
    void shouldCapCompletionsAtOneHundred() {
        try (final var capClient = McpTestClient.start(McpServer.builder("c-server", "1.0.0")
            .prompt(new McpPrompt() {
                @Override
                public String name() {
                    return "big";
                }

                @Override
                public Optional<String> description() {
                    return Optional.empty();
                }

                @Override
                public List<McpPromptArgument> arguments() {
                    return List.of(McpPromptArgument.required("x")
                        .withCompleter(v -> IntStream.range(0, 200)
                            .mapToObj(Integer::toString).toList()));
                }

                @Override
                public McpPromptResult get(final Map<String, String> arguments) {
                    return McpPromptResult.of(McpPromptMessage.userText(""));
                }
            })
            .build())) {

            capClient.initialize();
            final var response = capClient.completePromptArg("big", "x", "").asObject();
            final var values = (JsonArray) response.get("result").asObject().get("completion").asObject().get("values");
            assertThat(values.values()).hasSize(100);
        }
    }

    @Test
    void shouldReturnErrorForMissingRefOrArgument() {
        client.initialize();
        final var response = client.send("completion/complete", Map.of()).asObject();
        assertThat(response.members()).containsKey("error");
        assertThat(response.get("error").asObject().get("code").asNumber().toNumber().intValue()).isEqualTo(-32602);
    }

    /**
     * Sends a raw HTTP POST to /mcp using a plain socket, bypassing Java's restricted-header
     * filtering so that headers like Origin can be set freely.
     */
    private static int rawPost(final int port, final Map<String, String> extraHeaders) throws IOException {
        final var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        final var bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final var sb = new StringBuilder();
        sb.append("POST /mcp HTTP/1.1\r\n");
        sb.append("Host: 127.0.0.1:").append(port).append("\r\n");
        sb.append("Content-Type: application/json\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        for (final var entry : extraHeaders.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n").append(body);

        try (var socket = new Socket("127.0.0.1", port);
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final var statusLine = in.readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private static int rawPostWithHost(final int port, final String hostHeader) throws IOException {
        final var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        final var bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final var sb = new StringBuilder();
        sb.append("POST /mcp HTTP/1.1\r\n");
        sb.append("Host: ").append(hostHeader).append("\r\n");
        sb.append("Content-Type: application/json\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("\r\n").append(body);

        try (var socket = new Socket("127.0.0.1", port);
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final var statusLine = in.readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
