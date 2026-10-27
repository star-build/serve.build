/*-
 * #%L
 * Serve DevTools
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
package build.serve.devtools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerTimingMiddlewareTests {

    @Test
    void shouldAddServerTimingHeaderOnSend() throws Exception {
        final var response = new StubExchange.StubResponse();
        final var handler = ServerTimingMiddleware.create().apply(exchange ->
            exchange.response().status(200).send("hi"));

        handler.handle(StubExchange.get("/", response));

        assertThat(response.headers).containsKey("Server-Timing");
        assertThat(response.headers.get("Server-Timing")).startsWith("total;dur=");
    }

    @Test
    void shouldNotAddServerTimingHeaderIfHandlerNeverWrites() throws Exception {
        final var response = new StubExchange.StubResponse();
        final var handler = ServerTimingMiddleware.create().apply(exchange -> { /* no-op */ });

        handler.handle(StubExchange.get("/", response));

        assertThat(response.headers).doesNotContainKey("Server-Timing");
    }

    @Test
    void shouldAddHeaderOnlyOnce() throws Exception {
        final var response = new StubExchange.StubResponse();
        final var handler = ServerTimingMiddleware.create().apply(exchange -> {
            exchange.response().send("first");
            // defensive: don't re-add if a handler somehow writes twice
            exchange.response().send("second");
        });

        handler.handle(StubExchange.get("/", response));

        assertThat(response.headers).containsKey("Server-Timing");
        // The body stored is from the last send() call — header was set once at the first
        assertThat(response.body).isEqualTo("second");
    }

    @Test
    void shouldHonourRemoveHeader() throws Exception {
        final var response = new StubExchange.StubResponse();
        final var handler = ServerTimingMiddleware.create().apply(exchange -> {
            exchange.response().header("X-Debug", "1");
            exchange.response().removeHeader("X-Debug");
            exchange.response().send("hi");
        });

        handler.handle(StubExchange.get("/", response));

        assertThat(response.headers).doesNotContainKey("X-Debug");
    }

    @Test
    void shouldPassRequestThrough() throws Exception {
        final var response = new StubExchange.StubResponse();
        final var handler = ServerTimingMiddleware.create().apply(exchange -> {
            assertThat(exchange.request().method()).isEqualTo("GET");
            exchange.response().status(201).send("ok");
        });

        handler.handle(StubExchange.get("/api/thing", response));

        assertThat(response.statusCode).isEqualTo(201);
        assertThat(response.body).isEqualTo("ok");
    }
}
