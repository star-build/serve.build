/*-
 * #%L
 * Serve Testing
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
package build.serve.testing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link TestServer}, {@link TestRequest}, and {@link TestResponse} API.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
class TestServerTests {

    @Test
    void startsAndStopsCleanly() {
        try (var server = TestServer.of(exchange -> exchange.response().send("ok"))) {
            assertThat(server.port()).isPositive();
            assertThat(server.baseUri().toString()).startsWith("http://127.0.0.1:");
        }
    }

    @Test
    void getReturnsCorrectResponse() {
        try (var server = TestServer.of(exchange -> exchange.response().send("Hello!"))) {
            server.get("/anything")
                .send()
                .assertStatus(200)
                .assertBody("Hello!");
        }
    }

    @Test
    void postWithBodyWorks() {
        try (var server = TestServer.of(exchange -> {
            final var body = exchange.request().bodyAsString();
            exchange.response().send("echo:" + body);
        })) {
            server.post("/echo")
                .body("test-data")
                .send()
                .assertStatus(200)
                .assertBody("echo:test-data");
        }
    }

    @Test
    void customHeadersAreSent() {
        try (var server = TestServer.of(exchange -> {
            final var value = exchange.request().header("X-Test").orElse("none");
            exchange.response().send("header:" + value);
        })) {
            server.get("/")
                .header("X-Test", "hello")
                .send()
                .assertStatus(200)
                .assertBody("header:hello");
        }
    }

    @Test
    void shouldSendOriginHeader() {
        try (var server = TestServer.of(exchange -> {
            final var value = exchange.request().header("Origin").orElse("none");
            exchange.response().send("origin:" + value);
        })) {
            server.get("/")
                .header("Origin", "https://evil.example")
                .send()
                .assertStatus(200)
                .assertBody("origin:https://evil.example");
        }
    }

    @Test
    void shouldSendAllCustomHeaders() {
        try (var server = TestServer.of(exchange -> {
            final var first = exchange.request().header("X-First").orElse("none");
            final var second = exchange.request().header("X-Second").orElse("none");
            exchange.response().send(first + "," + second);
        })) {
            server.get("/")
                .header("X-First", "one")
                .header("X-Second", "two")
                .send()
                .assertBody("one,two");
        }
    }

    @Test
    void shouldSendPutBody() {
        try (var server = TestServer.of(exchange -> exchange.response().send(
            exchange.request().method() + ":" + exchange.request().bodyAsString()))) {
            server.put("/resource")
                .body("payload")
                .send()
                .assertStatus(200)
                .assertBody("PUT:payload");
        }
    }

    @Test
    void shouldSendBinaryBodyUnchanged() {
        final var bytes = new byte[]{0, 1, (byte) 0xFF, (byte) 0x80, 42};
        try (var server = TestServer.of(exchange -> {
            final var received = exchange.request().bodyAsStream().readAllBytes();
            exchange.response().send(received.length + ":" + (received[2] & 0xFF));
        })) {
            server.post("/")
                .body(bytes)
                .send()
                .assertBody("5:255");
        }
    }

    @Test
    void shouldReadBodyOfErrorResponse() {
        try (var server = TestServer.of(exchange -> exchange.response().status(500).send("boom"))) {
            final var response = server.get("/").send();

            assertThat(response.status()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("boom");
        }
    }

    @Test
    void shouldReturnEmptyBodyWhenNoneSent() {
        try (var server = TestServer.of(exchange -> exchange.response().status(204).send(""))) {
            final var response = server.get("/").send();

            assertThat(response.status()).isEqualTo(204);
            assertThat(response.bodyBytes()).isEmpty();
        }
    }

    @Test
    void shouldFollowRedirects() {
        try (var server = TestServer.of(exchange -> {
            if (exchange.request().path().equals("/old")) {
                exchange.response().redirect("/new");
            } else {
                exchange.response().send("arrived:" + exchange.request().path());
            }
        })) {
            server.get("/old")
                .send()
                .assertStatus(200)
                .assertBody("arrived:/new");
        }
    }

    @Test
    void shouldExposeResponseHeadersCaseInsensitively() {
        try (var server = TestServer.of(exchange ->
            exchange.response().header("X-Custom", "value").send("ok"))) {
            final var response = server.get("/").send();

            assertThat(response.header("x-custom")).isEqualTo("value");
            response.assertHeader("X-Custom", "value");
        }
    }

    @Test
    void assertStatusThrowsOnMismatch() {
        try (var server = TestServer.of(exchange -> exchange.response().status(404).send("nope"))) {
            final var response = server.get("/").send();

            assertThatThrownBy(() -> response.assertStatus(200))
                .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void assertBodyThrowsOnMismatch() {
        try (var server = TestServer.of(exchange -> exchange.response().send("actual"))) {
            final var response = server.get("/").send();

            assertThatThrownBy(() -> response.assertBody("expected"))
                .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void assertBodyContainsWorks() {
        try (var server = TestServer.of(exchange -> exchange.response().send("hello world"))) {
            server.get("/").send().assertBodyContains("world");
        }
    }

    @Test
    void responseContentTypeAccessible() {
        try (var server = TestServer.of(exchange ->
            exchange.response().header("Content-Type", "text/html").send("<h1>Hi</h1>"))) {
            server.get("/").send().assertContentType("text/html");
        }
    }
}
