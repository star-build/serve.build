/*-
 * #%L
 * Serve Foundation
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
package build.serve.foundation;

import build.base.json.JsonValue;
import build.serve.foundation.option.MaxRequestSize;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DelegatingExchange}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class DelegatingExchangeTests {

    @Test
    void shouldSubstituteTheRequestAndShareTheResponse() {
        final var parent = new SimpleExchange(new StubRequest("/original"), new StubResponse());
        final var replacement = new StubRequest("/replaced");

        final var exchange = DelegatingExchange.withRequest(parent, replacement);

        assertThat(exchange.request()).isSameAs(replacement);
        assertThat(exchange.response()).isSameAs(parent.response());
    }

    @Test
    void shouldSubstituteTheResponseAndShareTheRequest() {
        final var parent = new SimpleExchange(new StubRequest("/original"), new StubResponse());
        final var replacement = new StubResponse();

        final var exchange = DelegatingExchange.withResponse(parent, replacement);

        assertThat(exchange.response()).isSameAs(replacement);
        assertThat(exchange.request()).isSameAs(parent.request());
    }

    @Test
    void shouldSeeAttributesAndOptionsSetOnTheParent() {
        final var parent = new SimpleExchange(new StubRequest("/"), new StubResponse());
        parent.attribute("user", "alice");
        parent.option(MaxRequestSize.of(1234));

        final var exchange = DelegatingExchange.withRequest(parent, new StubRequest("/stripped"));

        assertThat(exchange.attribute("user", String.class)).contains("alice");
        assertThat(exchange.option(MaxRequestSize.class)).contains(MaxRequestSize.of(1234));
    }

    @Test
    void shouldWriteAttributesAndOptionsThroughToTheParent() {
        final var parent = new SimpleExchange(new StubRequest("/"), new StubResponse());
        final var exchange = DelegatingExchange.withResponse(parent, new StubResponse());

        exchange.attribute("user", "bob");
        exchange.option(MaxRequestSize.of(99));

        assertThat(parent.attribute("user", String.class)).contains("bob");
        assertThat(parent.option(MaxRequestSize.class)).contains(MaxRequestSize.of(99));
    }

    private static final class StubRequest implements Request {

        private final String path;

        StubRequest(final String path) {
            this.path = path;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public URI uri() {
            return URI.create(path);
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public Optional<String> pathParam(final String name) {
            return Optional.empty();
        }

        @Override
        public List<String> queryParams(final String name) {
            return List.of();
        }

        @Override
        public Optional<String> queryParam(final String name) {
            return Optional.empty();
        }

        @Override
        public Map<String, List<String>> headers() {
            return Map.of();
        }

        @Override
        public Optional<String> header(final String name) {
            return Optional.empty();
        }

        @Override
        public InputStream bodyAsStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public String bodyAsString() {
            return "";
        }

        @Override
        public JsonValue bodyAsJson() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubResponse implements Response {

        @Override
        public Response status(final int statusCode) {
            return this;
        }

        @Override
        public int status() {
            return 200;
        }

        @Override
        public Response header(final String name, final String value) {
            return this;
        }

        @Override
        public void send(final String body) {
        }

        @Override
        public void send(final byte[] body) {
        }

        @Override
        public void json(final Object body) {
        }

        @Override
        public OutputStream bodyAsStream() {
            throw new UnsupportedOperationException();
        }
    }
}
