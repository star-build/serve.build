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

import build.serve.foundation.Handler;
import build.serve.foundation.error.DefaultErrorHandler;
import build.serve.foundation.error.ErrorHandler;
import build.serve.foundation.option.MaxRequestSize;
import build.serve.foundation.option.RequestTimeout;
import build.serve.foundation.routing.Router;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.transport.http.HttpTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

/**
 * A fluent test server that manages lifecycle automatically.
 *
 * <p>Wraps an {@link HttpTransport} and provides a clean API for making
 * HTTP requests and asserting responses in integration tests.</p>
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class TestServer implements AutoCloseable {

    private final int port;
    private final Runnable closeAction;

    private TestServer(final int port, final Runnable closeAction) {
        this.port = port;
        this.closeAction = closeAction;
    }

    /**
     * Creates a test server from a {@link Handler}.
     *
     * @param handler the handler
     * @return a started test server
     */
    public static TestServer of(final Handler handler) {
        try {
            return start(new HttpTransport(new InetSocketAddress("127.0.0.1", 0), 0, handler));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a test server from a {@link Router}.
     *
     * @param router the router
     * @return a started test server
     */
    public static TestServer of(final Router router) {
        return of((Handler) router);
    }

    /**
     * Creates a test server from a {@link RouterBuilder}.
     *
     * @param builder the router builder
     * @return a started test server
     */
    public static TestServer of(final RouterBuilder builder) {
        return of(builder.build());
    }

    /**
     * Creates a test server from a {@link Handler} with a custom {@link ErrorHandler}.
     *
     * @param handler      the handler
     * @param errorHandler the error handler
     * @return a started test server
     */
    public static TestServer of(final Handler handler, final ErrorHandler errorHandler) {
        try {
            return start(new HttpTransport(
                new InetSocketAddress("127.0.0.1", 0), 0, handler, errorHandler));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a test server from a {@link Handler} with a {@link RequestTimeout}.
     *
     * @param handler        the handler
     * @param requestTimeout the maximum time a request handler may run
     * @return a started test server
     */
    public static TestServer of(final Handler handler, final RequestTimeout requestTimeout) {
        try {
            return start(new HttpTransport(
                new InetSocketAddress("127.0.0.1", 0), 0, handler, new DefaultErrorHandler(),
                MaxRequestSize.DEFAULT, requestTimeout));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TestServer start(final HttpTransport transport) throws IOException {
        transport.start();
        return new TestServer(transport.address().getPort(), () -> transport.stop(0));
    }

    /**
     * Returns the bound port.
     *
     * @return the port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the base URI.
     *
     * @return the base URI ({@code http://127.0.0.1:{port}})
     */
    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + port());
    }

    /**
     * Creates a GET request.
     *
     * @param path the request path
     * @return a new test request
     */
    public TestRequest get(final String path) {
        return request("GET", path);
    }

    /**
     * Creates a POST request.
     *
     * @param path the request path
     * @return a new test request
     */
    public TestRequest post(final String path) {
        return request("POST", path);
    }

    /**
     * Creates a PUT request.
     *
     * @param path the request path
     * @return a new test request
     */
    public TestRequest put(final String path) {
        return request("PUT", path);
    }

    /**
     * Creates a DELETE request.
     *
     * @param path the request path
     * @return a new test request
     */
    public TestRequest delete(final String path) {
        return request("DELETE", path);
    }

    /**
     * Creates a request with the given method and path.
     *
     * @param method the HTTP method
     * @param path   the request path
     * @return a new test request
     */
    public TestRequest request(final String method, final String path) {
        return new TestRequest(baseUri(), method, path);
    }

    /**
     * Connects a WebSocket test client to the given path.
     *
     * @param path the WebSocket endpoint path
     * @return a connected {@link TestWebSocket}
     */
    public TestWebSocket connectWebSocket(final String path) {
        return connectWebSocket(path, Map.of());
    }

    /**
     * Connects a WebSocket test client to the given path with custom request headers.
     *
     * @param path    the WebSocket endpoint path
     * @param headers extra headers to include in the upgrade request
     * @return a connected {@link TestWebSocket}
     */
    public TestWebSocket connectWebSocket(final String path, final Map<String, String> headers) {
        final var wsUri = URI.create("ws://127.0.0.1:" + port() + path);
        return TestWebSocket.connect(wsUri, headers);
    }

    /**
     * Opens a streaming SSE connection to the given path.
     *
     * @param path the SSE endpoint path
     * @return a {@link TestSseStream} that receives events as they arrive
     */
    public TestSseStream sse(final String path) {
        return TestSseStream.connect(baseUri().resolve(path));
    }

    /**
     * Opens a streaming SSE connection to the given path with the specified request headers.
     *
     * @param path    the SSE endpoint path
     * @param headers additional request headers (e.g. session IDs)
     * @return a {@link TestSseStream} that receives events as they arrive
     */
    public TestSseStream sse(final String path, final Map<String, String> headers) {
        return TestSseStream.connect(baseUri().resolve(path), headers);
    }

    @Override
    public void close() {
        closeAction.run();
    }
}
