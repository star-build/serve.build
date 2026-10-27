/*-
 * #%L
 * Serve Compression
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
package build.serve.compression;

import build.base.json.JsonValue;
import build.serve.foundation.Exchange;
import build.serve.foundation.Request;
import build.serve.foundation.Response;
import build.serve.foundation.SimpleExchange;
import build.serve.foundation.http.Cookie;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionMiddlewareTests {

    private static final String LARGE_BODY = "a".repeat(2048);

    @Test
    void compressesWithGzipWhenClientAcceptsIt() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).containsEntry("Content-Encoding", "gzip");
        assertThat(response.headers).containsEntry("Vary", "Accept-Encoding");
        assertThat(decompress(response.body, "gzip")).isEqualTo(LARGE_BODY);
    }

    @Test
    void noCompressionForSmallBodies() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        var smallBody = "hello";

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(smallBody);
        }).handle(exchange);

        assertThat(response.headers).doesNotContainKey("Content-Encoding");
        assertThat(response.headers).containsEntry("Vary", "Accept-Encoding");
        assertThat(new String(response.body)).isEqualTo(smallBody);
    }

    @Test
    void noCompressionWhenAcceptEncodingMissing() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange(null, response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).doesNotContainKey("Content-Encoding");
        assertThat(response.headers).containsEntry("Vary", "Accept-Encoding");
        assertThat(new String(response.body)).isEqualTo(LARGE_BODY);
    }

    @Test
    void noCompressionForNonCompressibleTypes() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "image/png");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).doesNotContainKey("Content-Encoding");
        assertThat(new String(response.body)).isEqualTo(LARGE_BODY);
    }

    @Test
    void varyHeaderAlwaysSet() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange(null, response);

        middleware.apply(ex -> {
            ex.response().send("short");
        }).handle(exchange);

        assertThat(response.headers).containsEntry("Vary", "Accept-Encoding");
    }

    @Test
    void deflateSupport() throws Exception {
        var middleware = CompressionMiddleware.builder()
            .deflate()
            .build();

        var response = new StubResponse();
        var exchange = createExchange("deflate", response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "application/json");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).containsEntry("Content-Encoding", "deflate");
        assertThat(decompress(response.body, "deflate")).isEqualTo(LARGE_BODY);
    }

    @Test
    void doesNotCompressIfResponseAlreadyHasContentEncoding() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "text/plain");
            ex.response().header("Content-Encoding", "br");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).containsEntry("Content-Encoding", "br");
        assertThat(new String(response.body)).isEqualTo(LARGE_BODY);
    }

    @Test
    void compressesApplicationJsonType() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        var json = "{" + "\"data\":\"" + "x".repeat(2000) + "\"}";

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "application/json; charset=utf-8");
            ex.response().send(json);
        }).handle(exchange);

        assertThat(response.headers).containsEntry("Content-Encoding", "gzip");
        assertThat(decompress(response.body, "gzip")).isEqualTo(json);
    }

    @Test
    void shouldPassThroughWebSocketUpgradeWithoutCompression() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = new SimpleExchange(new StubRequest("gzip", "websocket"), response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).doesNotContainKey("Content-Encoding");
        assertThat(response.headers).doesNotContainKey("Vary");
        assertThat(new String(response.body)).isEqualTo(LARGE_BODY);
    }

    @Test
    void shouldForwardCookieSetInsideCompressedResponse() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);
        var session = Cookie.of("session", "abc123");

        middleware.apply(ex -> {
            ex.response().cookie(session);
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.cookies).containsExactly(session);
    }

    @Test
    void shouldForwardDeletedCookieSetInsideCompressedResponse() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().deleteCookie("session");
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.cookies).singleElement().satisfies(cookie -> {
            assertThat(cookie.name()).isEqualTo("session");
            assertThat(cookie.maxAgeSeconds()).isZero();
        });
    }

    @Test
    void shouldKeepStatusAndHeadersForJsonResponse() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);
        var payload = Map.of("id", 7);

        middleware.apply(ex -> ex.response()
            .status(201)
            .header("Location", "/things/7")
            .json(payload)).handle(exchange);

        assertThat(response.statusCode).isEqualTo(201);
        assertThat(response.headers).containsEntry("Location", "/things/7");
        assertThat(response.jsonBody).isEqualTo(payload);
    }

    @Test
    void shouldHonourRemoveHeaderInsideCompressedResponse() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().header("X-Debug", "1");
            ex.response().removeHeader("X-Debug");
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).doesNotContainKey("X-Debug");
    }

    @Test
    void shouldCompressWhenContentEncodingIsRemovedInsideCompressedResponse() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().header("Content-Encoding", "identity");
            ex.response().removeHeader("content-encoding");
            ex.response().header("Content-Type", "text/plain");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).containsEntry("Content-Encoding", "gzip");
    }

    @Test
    void shouldNotCompressWhenContentTypeIsRemovedInsideCompressedResponse() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);

        middleware.apply(ex -> {
            ex.response().header("Content-Type", "text/plain");
            ex.response().removeHeader("content-type");
            ex.response().send(LARGE_BODY);
        }).handle(exchange);

        assertThat(response.headers).doesNotContainKey("Content-Encoding");
        assertThat(new String(response.body)).isEqualTo(LARGE_BODY);
    }

    @Test
    void shouldPreserveExchangeStateInsideCompression() throws Exception {
        var middleware = CompressionMiddleware.defaults();
        var response = new StubResponse();
        var exchange = createExchange("gzip", response);
        exchange.attribute("user", "alice");
        var seen = new AtomicReference<Optional<String>>();

        middleware.apply(ex -> {
            seen.set(ex.attribute("user", String.class));
            ex.response().send("ok");
        }).handle(exchange);

        assertThat(seen.get()).contains("alice");
    }

    private static Exchange createExchange(final String acceptEncoding, final StubResponse response) {
        return new SimpleExchange(new StubRequest(acceptEncoding), response);
    }

    private static String decompress(final byte[] data, final String encoding) throws Exception {
        var input = new ByteArrayInputStream(data);

        try (var stream = "gzip".equals(encoding)
            ? new GZIPInputStream(input)
            : new InflaterInputStream(input)) {
            return new String(stream.readAllBytes());
        }
    }

    private static final class StubRequest implements Request {

        private final String acceptEncoding;
        private final String upgradeHeader;

        StubRequest(final String acceptEncoding) {
            this(acceptEncoding, null);
        }

        StubRequest(final String acceptEncoding, final String upgradeHeader) {
            this.acceptEncoding = acceptEncoding;
            this.upgradeHeader = upgradeHeader;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public URI uri() {
            return URI.create("/");
        }

        @Override
        public String path() {
            return "/";
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
            if ("Accept-Encoding".equalsIgnoreCase(name) && acceptEncoding != null) {
                return Optional.of(acceptEncoding);
            }
            if ("Upgrade".equalsIgnoreCase(name) && upgradeHeader != null) {
                return Optional.of(upgradeHeader);
            }

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
        final List<Cookie> cookies = new ArrayList<>();
        int statusCode = 200;
        byte[] body;
        Object jsonBody;

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
            this.body = body.getBytes();
        }

        @Override
        public void send(final byte[] body) {
            this.body = body;
        }

        @Override
        public void removeHeader(final String name) {
            headers.remove(name);
        }

        @Override
        public void json(final Object body) {
            this.jsonBody = body;
        }

        @Override
        public void cookie(final Cookie cookie) {
            cookies.add(cookie);
        }

        @Override
        public OutputStream bodyAsStream() {
            throw new UnsupportedOperationException();
        }
    }
}
