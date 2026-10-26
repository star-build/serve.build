/*-
 * #%L
 * Serve WebSocket
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
package build.serve.websocket;

import build.serve.foundation.Exchange;
import build.serve.foundation.Handler;
import com.sun.net.httpserver.HttpExchange;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

/**
 * Bridges HTTP to WebSocket by performing the RFC 6455 opening handshake.
 * <p>
 * Detects WebSocket upgrade requests and hands off the underlying streams to a
 * {@link WebSocketConnection} running on a virtual thread.
 * <p>
 * Since the JDK {@link com.sun.net.httpserver.HttpServer} does not natively support
 * protocol upgrades, this implementation accesses the raw connection streams via
 * reflection to perform the WebSocket handshake and frame I/O.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class WebSocketUpgrade {

    /**
     * The WebSocket GUID used in the handshake per RFC 6455 Section 4.2.2.
     */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * Cached reflection accessors for the JDK HttpServer internals, initialised on first use.
     * <p>
     * The JDK's {@code com.sun.net.httpserver.HttpServer} does not expose raw connection streams
     * through its public API. These cached fields allow WebSocket upgrades to access the
     * underlying socket I/O without paying the reflection-lookup cost on every request.
     */
    private static volatile ConnectionAccessors connectionAccessors;

    /**
     * Holds the reflected {@link Field} and {@link Method} objects needed to reach the raw
     * connection streams from a JDK {@link HttpExchange} instance.
     */
    private record ConnectionAccessors(
        Field implField,
        Field connectionField,
        Method rawOutputStreamMethod,
        Field rawInputField) {
    }

    /**
     * Prevent instantiation.
     */
    private WebSocketUpgrade() {
    }

    /**
     * Creates a {@link Handler} that upgrades HTTP connections to WebSocket, with fail-closed
     * {@code Origin} enforcement.
     * <p>
     * The returned handler checks for a valid WebSocket upgrade request, performs the
     * opening handshake, and then delegates to the specified {@link WebSocketHandler}.
     * <p>
     * <strong>Origin policy:</strong> since no origins are registered, any upgrade request
     * carrying an {@code Origin} header (i.e. from a browser) is rejected with
     * {@code 403 Forbidden} — this is the primary DNS-rebinding defense, as a malicious
     * webpage's upgrade attempt always carries an {@code Origin} header. Requests without an
     * {@code Origin} header (non-browser clients) are allowed through this check. To accept
     * specific browser origins, use {@link #upgrade(WebSocketHandler, Set)}.
     *
     * @param wsHandler the handler for the established WebSocket connection
     * @return a {@link Handler} that performs the WebSocket upgrade
     */
    public static Handler upgrade(final WebSocketHandler wsHandler) {
        return upgrade(wsHandler, Set.of(), true);
    }

    /**
     * Creates a {@link Handler} that upgrades HTTP connections to WebSocket, enforcing an
     * {@code Origin} allowlist.
     * <p>
     * If {@code allowedOrigins} is non-empty, the {@code Origin} request header must exactly
     * match one of the allowed values (e.g. {@code "https://example.com"}); otherwise the
     * upgrade is rejected with {@code 403 Forbidden}. If {@code allowedOrigins} is empty, no
     * {@code Origin} check is performed by this overload — pass an explicit allowlist, or use
     * {@link #upgrade(WebSocketHandler)} for the fail-closed default.
     *
     * @param wsHandler      the handler for the established WebSocket connection
     * @param allowedOrigins the set of permitted {@code Origin} values; empty means no check
     * @return a {@link Handler} that performs the WebSocket upgrade
     */
    public static Handler upgrade(final WebSocketHandler wsHandler, final Set<String> allowedOrigins) {
        return upgrade(wsHandler, allowedOrigins, false);
    }

    private static Handler upgrade(final WebSocketHandler wsHandler, final Set<String> allowedOrigins,
                                   final boolean failClosedByDefault) {
        return exchange -> {
            final var request = exchange.request();

            // Validate WebSocket upgrade request
            final var upgrade = request.header("Upgrade").orElse("");
            final var connection = request.header("Connection").orElse("");
            final var wsKey = request.header("Sec-WebSocket-Key").orElse(null);

            if (!upgrade.equalsIgnoreCase("websocket")
                || !connection.toLowerCase().contains("upgrade")
                || wsKey == null) {
                exchange.response().status(400).send("Not a WebSocket upgrade request");

                return;
            }

            if (!allowedOrigins.isEmpty()) {
                final var origin = request.header("Origin").orElse("");

                if (!allowedOrigins.contains(origin)) {
                    exchange.response().status(403).send("Forbidden: Origin not allowed");

                    return;
                }
            } else if (failClosedByDefault && request.header("Origin").isPresent()) {
                exchange.response().status(403).send("Forbidden: Origin not allowed");

                return;
            }

            // Compute accept key
            final var acceptKey = computeAcceptKey(wsKey);

            // Get the underlying HttpExchange to access raw streams
            final var httpExchange = exchange.attribute(Exchange.HTTP_EXCHANGE_ATTRIBUTE, HttpExchange.class)
                .orElseThrow(() -> new IllegalStateException(
                    "WebSocket upgrade requires the HttpExchange attribute. "
                        + "Use WebSocketUpgrade with HttpTransport."));

            // Access the raw connection streams via cached reflection accessors
            final var acc = accessors(httpExchange);
            final OutputStream rawOut;
            final InputStream rawIn;
            try {
                final Object impl = acc.implField().get(httpExchange);
                final Object rawConnection = acc.connectionField().get(impl);
                rawOut = (OutputStream) acc.rawOutputStreamMethod().invoke(rawConnection);
                rawIn = (InputStream) acc.rawInputField().get(rawConnection);
            } catch (final ReflectiveOperationException e) {
                throw new RuntimeException("Failed to access raw WebSocket streams", e);
            }
            final InetSocketAddress remoteAddress = httpExchange.getRemoteAddress();

            // Create the WebSocket connection and hand off to the handler before writing the
            // 101 response, so the connection is registered with the handler before the client
            // can consider the handshake complete and send anything.
            final var wsConnection = new WebSocketConnection(rawIn, rawOut, remoteAddress);

            try {
                wsHandler.handle(wsConnection);

                // Write 101 Switching Protocols response directly to raw stream
                final var response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                    + "\r\n";
                rawOut.write(response.getBytes(StandardCharsets.US_ASCII));
                rawOut.flush();

                // The connection is long-lived by design, so the request timeout must not interrupt it
                exchange.attribute(Exchange.CANCEL_TIMEOUT_ATTRIBUTE, Runnable.class).ifPresent(Runnable::run);

                // Run the read loop on this virtual thread (blocking)
                wsConnection.readLoop();
            } finally {
                try {
                    wsConnection.close();
                } catch (final Exception ignored) {
                    // Best effort cleanup
                }
            }
        };
    }

    /**
     * Computes the {@code Sec-WebSocket-Accept} header value per RFC 6455 Section 4.2.2.
     *
     * @param key the {@code Sec-WebSocket-Key} from the client
     * @return the accept key
     */
    static String computeAcceptKey(final String key) {
        try {
            final var digest = MessageDigest.getInstance("SHA-1");
            final var hash = digest.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));

            return Base64.getEncoder().encodeToString(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    /**
     * Returns the {@link ConnectionAccessors}, initialising them on first use via double-checked locking.
     * <p>
     * The reflection fields are looked up once against the live {@link HttpExchange} instance and cached.
     * If the JDK's internal structure has changed, this method throws on the first WebSocket upgrade
     * with a clear error message rather than silently failing.
     */
    private static ConnectionAccessors accessors(final HttpExchange httpExchange) {
        if (connectionAccessors != null) {
            return connectionAccessors;
        }
        synchronized (WebSocketUpgrade.class) {
            if (connectionAccessors != null) {
                return connectionAccessors;
            }
            try {
                final var implField = httpExchange.getClass().getDeclaredField("impl");
                implField.setAccessible(true);
                final Object impl = implField.get(httpExchange);

                final var connField = impl.getClass().getDeclaredField("connection");
                connField.setAccessible(true);
                final Object connection = connField.get(impl);

                final var rawOutputMethod = connection.getClass().getDeclaredMethod("getRawOutputStream");
                rawOutputMethod.setAccessible(true);

                final var rawInputField = connection.getClass().getDeclaredField("raw");
                rawInputField.setAccessible(true);

                connectionAccessors = new ConnectionAccessors(implField, connField, rawOutputMethod, rawInputField);
            } catch (final ReflectiveOperationException e) {
                throw new RuntimeException(
                    "Failed to initialise WebSocket reflection accessors. "
                        + "The JDK's internal HttpExchange structure may have changed.", e);
            }
        }
        return connectionAccessors;
    }
}
