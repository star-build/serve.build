/*-
 * #%L
 * Serve Transport (HTTP)
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
package build.serve.transport.http;

import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.serve.foundation.Exchange;
import build.serve.foundation.Handler;
import build.serve.foundation.SimpleExchange;
import build.serve.foundation.context.RequestContext;
import build.serve.foundation.error.DefaultErrorHandler;
import build.serve.foundation.error.ErrorHandler;
import build.serve.foundation.option.MaxRequestSize;
import build.serve.foundation.option.RequestTimeout;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;

/**
 * An HTTP/HTTPS transport built on the JDK {@link HttpServer} with virtual thread execution.
 * <p>
 * Each incoming request is dispatched on its own virtual thread via
 * {@link Executors#newVirtualThreadPerTaskExecutor()}.
 * <p>
 * Use the constructors for plain HTTP, or the {@link #https} factory methods for TLS/HTTPS.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public class HttpTransport {

    /**
     * The default {@link TelemetryRecorder} used when none is supplied.
     */
    private static final TelemetryRecorder DEFAULT_RECORDER =
        PrintStreamTelemetryRecorder.of(URI.create("serve://transport-http"), System.out, System.err);

    /**
     * The {@link TelemetryRecorder} for this transport.
     */
    private final TelemetryRecorder recorder;

    /**
     * The underlying JDK {@link HttpServer}.
     */
    private final HttpServer httpServer;

    /**
     * The address this transport is bound to.
     */
    private final InetSocketAddress address;

    /**
     * The maximum allowed request body size.
     */
    private final MaxRequestSize maxRequestSize;

    /**
     * The timeout executor, non-null only when a request timeout is configured.
     */
    private final TimeoutExecutor timeoutExecutor;

    /**
     * Constructs an {@link HttpTransport} with a default {@link DefaultErrorHandler}.
     *
     * @param address the address to bind to
     * @param backlog the maximum number of queued incoming connections (0 for system default)
     * @param handler the {@link Handler} to dispatch requests to
     * @throws IOException if the server cannot be created
     */
    public HttpTransport(final InetSocketAddress address,
                         final int backlog,
                         final Handler handler) throws IOException {
        this(address, backlog, handler, new DefaultErrorHandler(), MaxRequestSize.DEFAULT);
    }

    /**
     * Constructs an {@link HttpTransport}.
     *
     * @param address      the address to bind to
     * @param backlog      the maximum number of queued incoming connections (0 for system default)
     * @param handler      the {@link Handler} to dispatch requests to
     * @param errorHandler the {@link ErrorHandler} for unhandled exceptions
     * @throws IOException if the server cannot be created
     */
    public HttpTransport(final InetSocketAddress address,
                         final int backlog,
                         final Handler handler,
                         final ErrorHandler errorHandler) throws IOException {
        this(address, backlog, handler, errorHandler, MaxRequestSize.DEFAULT, RequestTimeout.NONE);
    }

    /**
     * Constructs an {@link HttpTransport} with a custom {@link TelemetryRecorder}.
     *
     * @param address      the address to bind to
     * @param backlog      the maximum number of queued incoming connections (0 for system default)
     * @param handler      the {@link Handler} to dispatch requests to
     * @param errorHandler the {@link ErrorHandler} for unhandled exceptions
     * @param recorder     the {@link TelemetryRecorder} to record lifecycle events with
     * @throws IOException if the server cannot be created
     */
    public HttpTransport(final InetSocketAddress address,
                         final int backlog,
                         final Handler handler,
                         final ErrorHandler errorHandler,
                         final TelemetryRecorder recorder) throws IOException {
        this(address, backlog, handler, errorHandler, MaxRequestSize.DEFAULT, RequestTimeout.NONE, recorder);
    }

    /**
     * Constructs an {@link HttpTransport} with a custom {@link MaxRequestSize}.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @throws IOException if the server cannot be created
     */
    public HttpTransport(final InetSocketAddress address,
                         final int backlog,
                         final Handler handler,
                         final ErrorHandler errorHandler,
                         final MaxRequestSize maxRequestSize) throws IOException {
        this(address, backlog, handler, errorHandler, maxRequestSize, RequestTimeout.NONE);
    }

    /**
     * Constructs an {@link HttpTransport} with a custom {@link MaxRequestSize} and {@link RequestTimeout}.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @param requestTimeout the maximum time a request handler may run before being interrupted
     * @throws IOException if the server cannot be created
     */
    public HttpTransport(final InetSocketAddress address,
                         final int backlog,
                         final Handler handler,
                         final ErrorHandler errorHandler,
                         final MaxRequestSize maxRequestSize,
                         final RequestTimeout requestTimeout) throws IOException {
        this(address, backlog, handler, errorHandler, maxRequestSize, requestTimeout, DEFAULT_RECORDER);
    }

    /**
     * Constructs an {@link HttpTransport} with a custom {@link MaxRequestSize}, {@link RequestTimeout}, and
     * {@link TelemetryRecorder}.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @param requestTimeout the maximum time a request handler may run before being interrupted
     * @param recorder       the {@link TelemetryRecorder} to record lifecycle events with
     * @throws IOException if the server cannot be created
     */
    public HttpTransport(final InetSocketAddress address,
                         final int backlog,
                         final Handler handler,
                         final ErrorHandler errorHandler,
                         final MaxRequestSize maxRequestSize,
                         final RequestTimeout requestTimeout,
                         final TelemetryRecorder recorder) throws IOException {
        this(HttpServer.create(address, backlog), address, handler, errorHandler, maxRequestSize, requestTimeout,
            recorder);
    }

    /**
     * Creates an HTTPS {@link HttpTransport} with a default {@link DefaultErrorHandler}.
     *
     * @param address    the address to bind to
     * @param backlog    the maximum number of queued incoming connections (0 for system default)
     * @param handler    the {@link Handler} to dispatch requests to
     * @param sslContext the {@link SSLContext} used for TLS
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final SSLContext sslContext) throws IOException {
        return https(address, backlog, handler, new DefaultErrorHandler(), MaxRequestSize.DEFAULT, sslContext);
    }

    /**
     * Creates an HTTPS {@link HttpTransport}.
     *
     * @param address      the address to bind to
     * @param backlog      the maximum number of queued incoming connections (0 for system default)
     * @param handler      the {@link Handler} to dispatch requests to
     * @param errorHandler the {@link ErrorHandler} for unhandled exceptions
     * @param sslContext   the {@link SSLContext} used for TLS
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final ErrorHandler errorHandler,
                                      final SSLContext sslContext) throws IOException {
        return https(address, backlog, handler, errorHandler, MaxRequestSize.DEFAULT, sslContext);
    }

    /**
     * Creates an HTTPS {@link HttpTransport} with a custom {@link TelemetryRecorder}.
     *
     * @param address      the address to bind to
     * @param backlog      the maximum number of queued incoming connections (0 for system default)
     * @param handler      the {@link Handler} to dispatch requests to
     * @param errorHandler the {@link ErrorHandler} for unhandled exceptions
     * @param sslContext   the {@link SSLContext} used for TLS
     * @param recorder     the {@link TelemetryRecorder} to record lifecycle events with
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final ErrorHandler errorHandler,
                                      final SSLContext sslContext,
                                      final TelemetryRecorder recorder) throws IOException {
        return https(address, backlog, handler, errorHandler, MaxRequestSize.DEFAULT, sslContext, TlsOptions.defaults(),
            RequestTimeout.NONE, recorder);
    }

    /**
     * Creates an HTTPS {@link HttpTransport} with a custom {@link MaxRequestSize}.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @param sslContext     the {@link SSLContext} used for TLS
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final ErrorHandler errorHandler,
                                      final MaxRequestSize maxRequestSize,
                                      final SSLContext sslContext) throws IOException {
        return https(address, backlog, handler, errorHandler, maxRequestSize, sslContext, TlsOptions.defaults());
    }

    /**
     * Creates an HTTPS {@link HttpTransport} with TLS hardening options.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @param sslContext     the {@link SSLContext} used for TLS
     * @param tlsOptions     the {@link TlsOptions} controlling minimum TLS version and cipher filtering
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final ErrorHandler errorHandler,
                                      final MaxRequestSize maxRequestSize,
                                      final SSLContext sslContext,
                                      final TlsOptions tlsOptions) throws IOException {
        return https(address, backlog, handler, errorHandler, maxRequestSize, sslContext, tlsOptions,
            RequestTimeout.NONE);
    }

    /**
     * Creates an HTTPS {@link HttpTransport} with TLS hardening options and a request timeout.
     * <p>
     * Every other {@code https(...)} overload defaults to {@link RequestTimeout#NONE} — a
     * slow-body or Slowloris-style attack over TLS can pin a virtual thread indefinitely unless
     * a timeout is supplied here, mirroring the plain-HTTP constructor that accepts
     * {@link RequestTimeout}.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @param sslContext     the {@link SSLContext} used for TLS
     * @param tlsOptions     the {@link TlsOptions} controlling minimum TLS version and cipher filtering
     * @param requestTimeout the maximum time a request handler may run before being interrupted
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final ErrorHandler errorHandler,
                                      final MaxRequestSize maxRequestSize,
                                      final SSLContext sslContext,
                                      final TlsOptions tlsOptions,
                                      final RequestTimeout requestTimeout) throws IOException {
        return https(address, backlog, handler, errorHandler, maxRequestSize, sslContext, tlsOptions, requestTimeout,
            DEFAULT_RECORDER);
    }

    /**
     * Creates an HTTPS {@link HttpTransport} with TLS hardening options, a request timeout, and a
     * {@link TelemetryRecorder}.
     * <p>
     * Every other {@code https(...)} overload defaults to {@link RequestTimeout#NONE} — a
     * slow-body or Slowloris-style attack over TLS can pin a virtual thread indefinitely unless
     * a timeout is supplied here, mirroring the plain-HTTP constructor that accepts
     * {@link RequestTimeout}.
     *
     * @param address        the address to bind to
     * @param backlog        the maximum number of queued incoming connections (0 for system default)
     * @param handler        the {@link Handler} to dispatch requests to
     * @param errorHandler   the {@link ErrorHandler} for unhandled exceptions
     * @param maxRequestSize the maximum allowed request body size
     * @param sslContext     the {@link SSLContext} used for TLS
     * @param tlsOptions     the {@link TlsOptions} controlling minimum TLS version and cipher filtering
     * @param requestTimeout the maximum time a request handler may run before being interrupted
     * @param recorder       the {@link TelemetryRecorder} to record lifecycle events with
     * @return a new HTTPS {@link HttpTransport}
     * @throws IOException if the server cannot be created
     */
    public static HttpTransport https(final InetSocketAddress address,
                                      final int backlog,
                                      final Handler handler,
                                      final ErrorHandler errorHandler,
                                      final MaxRequestSize maxRequestSize,
                                      final SSLContext sslContext,
                                      final TlsOptions tlsOptions,
                                      final RequestTimeout requestTimeout,
                                      final TelemetryRecorder recorder) throws IOException {
        Objects.requireNonNull(sslContext, "sslContext must not be null");
        Objects.requireNonNull(tlsOptions, "tlsOptions must not be null");

        final var server = HttpsServer.create(address, backlog);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(final HttpsParameters params) {
                final var engine = getSSLContext().createSSLEngine();
                final var enabledProtocols = engine.getEnabledProtocols();
                final var enabledCiphers = engine.getEnabledCipherSuites();

                params.setNeedClientAuth(false);

                if (tlsOptions.minimumVersion() != null) {
                    final var minName = tlsOptions.minimumVersion().protocolName();
                    final var filteredProtocols = filterProtocols(enabledProtocols, minName);
                    final var filteredCiphers = filterCiphers(enabledCiphers, minName);
                    params.setProtocols(filteredProtocols);
                    params.setCipherSuites(filteredCiphers);
                } else {
                    params.setProtocols(enabledProtocols);
                    params.setCipherSuites(enabledCiphers);
                }

            }
        });

        return new HttpTransport(server, address, handler, errorHandler, maxRequestSize, requestTimeout, recorder);
    }

    static String[] filterProtocols(final String[] protocols, final String minProtocol) {
        final var order = List.of("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");
        final int minIndex = order.indexOf(minProtocol);

        return Arrays.stream(protocols)
            .filter(p -> order.indexOf(p) >= minIndex)
            .toArray(String[]::new);
    }

    static String[] filterCiphers(final String[] ciphers, final String minProtocol) {
        return Arrays.stream(ciphers)
            .filter(c -> !c.contains("_NULL_") && !c.contains("_anon_") && !c.contains("EXPORT")
                && !c.contains("_CBC_") && !c.contains("_3DES_") && !c.startsWith("TLS_RSA_"))
            .toArray(String[]::new);
    }

    /**
     * Private constructor that accepts a pre-configured {@link HttpServer} (HTTP or HTTPS).
     */
    private HttpTransport(final HttpServer server,
                          final InetSocketAddress address,
                          final Handler handler,
                          final ErrorHandler errorHandler,
                          final MaxRequestSize maxRequestSize,
                          final RequestTimeout requestTimeout,
                          final TelemetryRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder must not be null");
        this.address = Objects.requireNonNull(address, "address must not be null");
        this.maxRequestSize = Objects.requireNonNull(maxRequestSize, "maxRequestSize must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(errorHandler, "errorHandler must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");

        final var baseExecutor = Executors.newVirtualThreadPerTaskExecutor();
        if (requestTimeout.duration().isZero()) {
            this.timeoutExecutor = null;
            this.httpServer = server;
            this.httpServer.setExecutor(baseExecutor);
        } else {
            this.timeoutExecutor = new TimeoutExecutor(baseExecutor, requestTimeout.duration());
            this.httpServer = server;
            this.httpServer.setExecutor(this.timeoutExecutor);
        }
        this.httpServer.createContext("/", httpExchange -> {
            final var request = new HttpRequest(httpExchange, this.maxRequestSize);
            final var response = new HttpResponse(httpExchange);
            final var exchange = new SimpleExchange(request, response);

            // Link request and response back to the exchange for path param and body reader/writer resolution
            request.setExchange(exchange);
            response.setExchange(exchange);

            // Expose the underlying HttpExchange for WebSocket upgrade support
            exchange.attribute(Exchange.HTTP_EXCHANGE_ATTRIBUTE, httpExchange);
            exchange.attribute(Exchange.CANCEL_TIMEOUT_ATTRIBUTE, TimeoutExecutor.cancelCurrent());

            RequestContext.run(exchange, () -> {
                try {
                    handler.handle(exchange);
                } catch (final Exception e) {
                    try {
                        errorHandler.handle(exchange, e);
                    } catch (final Exception failure) {
                        // response may already be committed
                        if (failure != e) {
                            failure.addSuppressed(e);
                        }
                        recorder.error(failure, "Error handler failed while handling %s", e);
                    }
                } finally {
                    httpExchange.close();
                }
            });
        });
    }

    /**
     * Starts this {@link HttpTransport}.
     */
    public void start() {
        httpServer.start();

        recorder.info("HttpTransport started on %s", address);
    }

    /**
     * Stops this {@link HttpTransport}.
     *
     * @param delay the maximum time in seconds to wait for active exchanges to finish
     */
    public void stop(final int delay) {
        httpServer.stop(delay);

        if (timeoutExecutor != null) {
            timeoutExecutor.close();
        }

        recorder.info("HttpTransport stopped");
    }

    /**
     * Obtains the {@link InetSocketAddress} this transport is bound to.
     *
     * @return the bound address
     */
    public InetSocketAddress address() {
        return httpServer.getAddress();
    }
}
