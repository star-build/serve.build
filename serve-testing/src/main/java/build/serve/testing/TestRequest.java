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

import build.base.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fluent HTTP request builder for integration tests.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class TestRequest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final URI baseUri;
    private final String method;
    private final String path;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body;

    TestRequest(final URI baseUri, final String method, final String path) {
        this.baseUri = baseUri;
        this.method = method;
        this.path = path;
    }

    /**
     * Adds a header to the request.
     * <p>
     * The underlying {@link HttpClient} restricts {@code Host}, {@code Connection}, {@code Content-Length},
     * {@code Expect}, and {@code Upgrade}; setting any of them causes {@link #send()} to throw an
     * {@link IllegalArgumentException}.
     *
     * @param name  the header name
     * @param value the header value
     * @return this request
     */
    public TestRequest header(final String name, final String value) {
        headers.put(name, value);
        return this;
    }

    /**
     * Sets the request body as a string.
     *
     * @param body the body
     * @return this request
     */
    public TestRequest body(final String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * Sets the request body as bytes.
     *
     * @param body the body
     * @return this request
     */
    public TestRequest body(final byte[] body) {
        this.body = body.clone();
        return this;
    }

    /**
     * Serializes the given {@link JsonValue} as JSON and sets it as the request body.
     *
     * @param body the JSON value to serialize
     * @return this request
     */
    public TestRequest jsonBody(final JsonValue body) {
        this.body = body.toJsonString().getBytes(StandardCharsets.UTF_8);
        return contentType("application/json");
    }

    /**
     * Sets the Content-Type header.
     *
     * @param contentType the content type
     * @return this request
     */
    public TestRequest contentType(final String contentType) {
        return header("Content-Type", contentType);
    }

    /**
     * Sends the request and returns the response.
     *
     * @return the test response
     */
    public TestResponse send() {
        final var builder = HttpRequest.newBuilder(baseUri.resolve(path))
            .method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        for (final var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        try {
            final var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

            final Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> responseHeaders.put(name, values.getLast()));

            return new TestResponse(response.statusCode(), response.body(), responseHeaders,
                response.headers().firstValue("Content-Type").orElse(null));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending " + method + " " + path, e);
        }
    }
}
