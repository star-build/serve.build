/*-
 * #%L
 * Serve Logging
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
package build.serve.logging;

import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.serve.foundation.Exchange;
import build.serve.foundation.Request;
import build.serve.foundation.Response;
import build.serve.foundation.SimpleExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLoggingMiddlewareTests {

    @Test
    void defaultsPassesThroughResponse() throws Exception {
        var middleware = RequestLoggingMiddleware.defaults();

        var response = new StubResponse();
        var exchange = createExchange("GET", "/api/users", response);
        var nextCalled = new AtomicBoolean(false);

        middleware.apply(ex -> {
            ex.response().status(200);
            nextCalled.set(true);
        }).handle(exchange);

        assertThat(nextCalled).isTrue();
        assertThat(response.statusCode).isEqualTo(200);
    }

    @Test
    void excludedPathStillCallsHandlerButSkipsLogging() throws Exception {
        var middleware = RequestLoggingMiddleware.builder()
            .excludePath("/health")
            .build();

        var response = new StubResponse();
        var exchange = createExchange("GET", "/health", response);
        var nextCalled = new AtomicBoolean(false);

        middleware.apply(ex -> {
            ex.response().status(200);
            nextCalled.set(true);
        }).handle(exchange);

        assertThat(nextCalled).isTrue();
    }

    @Test
    void multipleExcludedPaths() throws Exception {
        var middleware = RequestLoggingMiddleware.builder()
            .excludePath("/health")
            .excludePath("/favicon.ico")
            .build();

        var response1 = new StubResponse();
        var next1 = new AtomicBoolean(false);
        middleware.apply(ex -> next1.set(true)).handle(createExchange("GET", "/health", response1));
        assertThat(next1).isTrue();

        var response2 = new StubResponse();
        var next2 = new AtomicBoolean(false);
        middleware.apply(ex -> next2.set(true)).handle(createExchange("GET", "/favicon.ico", response2));
        assertThat(next2).isTrue();
    }

    @Test
    void handlerExceptionPropagates() {
        var middleware = RequestLoggingMiddleware.defaults();

        var response = new StubResponse();
        var exchange = createExchange("POST", "/api/fail", response);

        assertThatThrownBy(() -> middleware.apply(ex -> {
            throw new IllegalStateException("boom");
        }).handle(exchange))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");
    }

    @Test
    void responseStatusIsAccessibleAfterHandler() throws Exception {
        var middleware = RequestLoggingMiddleware.defaults();

        var response = new StubResponse();
        var exchange = createExchange("GET", "/api/items", response);

        middleware.apply(ex -> ex.response().status(404).send("")).handle(exchange);

        assertThat(response.statusCode).isEqualTo(404);
    }

    @Test
    void jsonFormatPassesThroughResponse() throws Exception {
        var middleware = RequestLoggingMiddleware.builder()
            .format(LogFormat.JSON)
            .build();

        var response = new StubResponse();
        var exchange = createExchange("GET", "/api/items", response);
        var nextCalled = new AtomicBoolean(false);

        middleware.apply(ex -> {
            ex.response().status(200);
            nextCalled.set(true);
        }).handle(exchange);

        assertThat(nextCalled).isTrue();
        assertThat(response.statusCode).isEqualTo(200);
    }

    @Test
    void builderConfiguration() throws Exception {
        var middleware = RequestLoggingMiddleware.builder()
            .includeHeaders(true)
            .includeQueryString(true)
            .excludePath("/health")
            .slowRequestThreshold(Duration.ofSeconds(1))
            .build();

        var response = new StubResponse();
        var exchange = createExchange("GET", "/api/data", response);

        middleware.apply(ex -> ex.response().status(200)).handle(exchange);

        assertThat(response.statusCode).isEqualTo(200);
    }

    @Test
    void shouldLogRequestWhosePathContainsPercent() throws Exception {
        var output = new ByteArrayOutputStream();
        var middleware = RequestLoggingMiddleware.builder().recorder(recorderTo(output)).build();

        var response = new StubResponse();
        var exchange = new SimpleExchange(new StubRequest("GET", URI.create("/a%25s"), Map.of()), response);

        middleware.apply(ex -> ex.response().status(200)).handle(exchange);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("GET /a%s -> 200");
    }

    @Test
    void shouldLogQueryStringContainingPercentEncoding() throws Exception {
        var output = new ByteArrayOutputStream();
        var middleware = RequestLoggingMiddleware.builder()
            .includeQueryString(true)
            .recorder(recorderTo(output))
            .build();

        var response = new StubResponse();
        var exchange = new SimpleExchange(new StubRequest("GET", URI.create("/search?q=a%20b"), Map.of()), response);

        middleware.apply(ex -> ex.response().status(200)).handle(exchange);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("GET /search?q=a%20b -> 200");
    }

    @Test
    void shouldLogHeadersContainingPercent() throws Exception {
        var output = new ByteArrayOutputStream();
        var middleware = RequestLoggingMiddleware.builder()
            .includeHeaders(true)
            .recorder(recorderTo(output))
            .build();

        var response = new StubResponse();
        var headers = Map.of("X-Trace", List.of("100%d"));
        var exchange = new SimpleExchange(new StubRequest("GET", URI.create("/ok"), headers), response);

        middleware.apply(ex -> ex.response().status(200)).handle(exchange);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("100%d");
    }

    @Test
    void shouldPropagateOriginalExceptionWhenPathContainsPercent() {
        var output = new ByteArrayOutputStream();
        var middleware = RequestLoggingMiddleware.builder().recorder(recorderTo(output)).build();

        var response = new StubResponse();
        var exchange = new SimpleExchange(new StubRequest("GET", URI.create("/a%25s"), Map.of()), response);

        assertThatThrownBy(() -> middleware.apply(ex -> {
            throw new IllegalStateException("boom");
        }).handle(exchange))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("GET /a%s -> ERROR");
    }

    @Test
    void shouldNotLogCredentialHeaders() throws Exception {
        var output = new ByteArrayOutputStream();
        var middleware = RequestLoggingMiddleware.builder()
            .includeHeaders(true)
            .recorder(recorderTo(output))
            .build();

        var response = new StubResponse();
        var headers = Map.of(
            "Authorization", List.of("Bearer s3cret-token"),
            "Cookie", List.of("session=s3cret-cookie"),
            "Accept", List.of("text/plain"));
        var exchange = new SimpleExchange(new StubRequest("GET", URI.create("/ok"), headers), response);

        middleware.apply(ex -> ex.response().status(200)).handle(exchange);

        var logged = output.toString(StandardCharsets.UTF_8);

        assertThat(logged)
            .doesNotContain("s3cret-token")
            .doesNotContain("s3cret-cookie")
            .contains("text/plain");
    }

    @Test
    void shouldRedactAdditionalHeadersCaseInsensitively() throws Exception {
        var output = new ByteArrayOutputStream();
        var middleware = RequestLoggingMiddleware.builder()
            .includeHeaders(true)
            .redactHeader("X-Api-Key")
            .recorder(recorderTo(output))
            .build();

        var response = new StubResponse();
        var headers = Map.of(
            "x-API-key", List.of("s3cret-key"),
            "Accept", List.of("text/plain"));
        var exchange = new SimpleExchange(new StubRequest("GET", URI.create("/ok"), headers), response);

        middleware.apply(ex -> ex.response().status(200)).handle(exchange);

        assertThat(output.toString(StandardCharsets.UTF_8))
            .doesNotContain("s3cret-key")
            .contains("text/plain");
    }

    private static TelemetryRecorder recorderTo(final ByteArrayOutputStream output) {
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);

        return PrintStreamTelemetryRecorder.of(URI.create("serve://logging-test"), stream, stream);
    }

    private static Exchange createExchange(final String method, final String path, final StubResponse response) {
        return new SimpleExchange(new StubRequest(method, URI.create(path), Map.of()), response);
    }

    private static final class StubRequest implements Request {

        private final String method;
        private final URI uri;
        private final Map<String, List<String>> headers;

        StubRequest(final String method, final URI uri, final Map<String, List<String>> headers) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public String path() {
            return uri.getPath();
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
            return headers;
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

        final Map<String, String> headers = new LinkedHashMap<>();
        int statusCode = 200;

        @Override
        public Response status(final int statusCode) {
            this.statusCode = statusCode;

            return this;
        }

        @Override
        public int status() {
            return statusCode;
        }

        @Override
        public Response header(final String name, final String value) {
            headers.put(name, value);

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
