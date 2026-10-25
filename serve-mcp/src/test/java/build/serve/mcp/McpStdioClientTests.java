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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link McpStdioClient}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpStdioClientTests {

    @Test
    void shouldPreserveNonAsciiCharactersInResponses() {
        final var server = McpServer.builder("utf8-server", "1.0.0")
            .tool(ToolDef.of("greet", "Returns non-ASCII text")
                .handle(args -> McpToolResult.text("café 日本語 😀")))
            .build();

        try (var client = McpStdioClient.of(server)) {
            final var result = client.call("greet", Map.of());
            final var text = ((McpContent.Text) result.content().get(0)).text();
            assertThat(text).isEqualTo("café 日本語 😀");
        }
    }

    @Test
    void shouldRouteEachResponseToItsCallerUnderConcurrentUse() throws Exception {
        final var value = ToolParam.string("value", "Value to echo");
        final var server = McpServer.builder("echo-server", "1.0.0")
            .tool(ToolDef.of("echo", "Echoes its argument after a short delay")
                .param(value)
                .handle(args -> {
                    try {
                        Thread.sleep(10);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return McpToolResult.text(value.extract(args));
                }))
            .build();

        final int callers = 32;
        try (var client = McpStdioClient.of(server, Duration.ofSeconds(5));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var gate = new CountDownLatch(1);
            final var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < callers; i++) {
                final var expected = "caller-" + i;
                futures.add(executor.submit(() -> {
                    gate.await();
                    final var result = client.call("echo", Map.of("value", expected));
                    return ((McpContent.Text) result.content().get(0)).text();
                }));
            }
            gate.countDown();

            for (int i = 0; i < callers; i++) {
                assertThat(futures.get(i).get(10, TimeUnit.SECONDS)).isEqualTo("caller-" + i);
            }
        }
    }

    @Test
    void shouldFailCloseWhenServerLoopDoesNotExit() throws Exception {
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var server = McpServer.builder("stuck-server", "1.0.0")
            .tool(ToolDef.of("block", "Blocks until released")
                .handle(args -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return McpToolResult.text("done");
                }))
            .build();

        final var client = McpStdioClient.of(server, Duration.ofSeconds(30), n -> {
        }, Duration.ofMillis(200));
        final var caller = Thread.ofVirtual().start(() -> {
            try {
                client.call("block", Map.of());
            } catch (final RuntimeException ignored) {
                // the caller is abandoned when the test finishes
            }
        });

        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(client::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not exit");
        } finally {
            release.countDown();
            caller.join(5_000);
        }
    }
}
