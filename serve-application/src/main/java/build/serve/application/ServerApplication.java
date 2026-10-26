/*-
 * #%L
 * Serve Application
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
package build.serve.application;

import build.base.network.option.Port;
import build.base.telemetry.TelemetryRecorder;
import build.codemodel.dependency.injection.Context;
import build.serve.foundation.Handler;
import build.serve.foundation.error.DefaultErrorHandler;
import build.serve.foundation.error.ErrorHandler;
import build.serve.foundation.option.ListenAddress;
import build.serve.foundation.option.MaxRequestSize;
import build.serve.foundation.option.RequestTimeout;
import build.serve.foundation.option.ShutdownTimeout;
import build.serve.foundation.option.TlsConfig;
import build.serve.foundation.routing.Router;
import build.serve.security.SecurityHeadersMiddleware;
import build.serve.transport.http.HttpTransport;
import build.serve.transport.http.TlsOptions;
import build.spawn.application.Addressable;
import build.spawn.application.Lifecycle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * A {@link ServerApplication} is an HTTP server that uses a {@link Router} to dispatch requests.
 * <p>
 * It implements the spawn.build {@link Lifecycle} pattern for asynchronous start/stop signaling
 * and {@link Addressable} for network address discovery, without extending the full spawn
 * {@code Application} model (which is designed for external processes).
 * <p>
 * Subclasses provide a {@link Router} via {@link Implementation#configure()} and the lifecycle
 * is managed via {@link #onStart()} and {@link #onExit()}.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public interface ServerApplication
    extends AutoCloseable, Lifecycle<ServerApplication>, Addressable {

    /**
     * Obtains the {@link Router} for this {@link ServerApplication}.
     *
     * @return the {@link Router}
     */
    Router router();

    /**
     * Obtains the {@link InetSocketAddress} this server is bound to.
     *
     * @return the bound address, or {@code null} if not yet started
     */
    InetSocketAddress boundAddress();

    /**
     * An implementation of {@link ServerApplication} that manages the HTTP transport lifecycle.
     * <p>
     * Subclasses must override {@link #configure()} to provide the {@link Router}.
     */
    abstract class Implementation
        implements ServerApplication {

        /**
         * The {@link TelemetryRecorder} for this server's lifecycle events. Defaults to
         * {@link ApplicationTelemetry#DEFAULT_RECORDER}; {@link Launcher} replaces it with the
         * recorder bound into the injection {@link Context}.
         */
        private TelemetryRecorder recorder = ApplicationTelemetry.DEFAULT_RECORDER;

        /**
         * The {@link Router} for this server.
         */
        private Router router;

        /**
         * The {@link HttpTransport} for this server.
         */
        private HttpTransport transport;

        /**
         * The {@link Context} for dependency injection.
         */
        private Context context;

        /**
         * The {@link CompletableFuture} that completes when the server starts.
         */
        private final CompletableFuture<ServerApplication> startFuture;

        /**
         * The {@link CompletableFuture} that completes when the server exits.
         */
        private final CompletableFuture<ServerApplication> exitFuture;

        /**
         * The configured {@link ShutdownTimeout}.
         */
        private ShutdownTimeout shutdownTimeout;

        /**
         * The shutdown hook {@link Thread}.
         */
        private Thread shutdownHook;

        /**
         * Guard to ensure {@link #stop()} runs at most once.
         */
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        /**
         * Constructs an {@link Implementation}.
         */
        protected Implementation() {
            this.startFuture = new CompletableFuture<>();
            this.exitFuture = new CompletableFuture<>();
        }

        /**
         * Obtains the injection {@link Context} for this server.
         * <p>
         * Use this to bind additional services before calling {@link #start}.
         *
         * @return the {@link Context}, or {@code null} if not yet initialized
         */
        public Context context() {
            return context;
        }

        /**
         * Sets the injection {@link Context} for this server.
         *
         * @param context the {@link Context}
         */
        void context(final Context context) {
            this.context = context;
        }

        /**
         * Sets the {@link TelemetryRecorder} used for this server's lifecycle events.
         *
         * @param recorder the {@link TelemetryRecorder}
         */
        void recorder(final TelemetryRecorder recorder) {
            this.recorder = Objects.requireNonNull(recorder, "recorder must not be null");
        }

        /**
         * Obtains the {@link TelemetryRecorder} bound to this server, for use by
         * {@link #configure()} when wiring components (e.g. {@code GraphQlSchema}) that need
         * somewhere to report their own errors instead of defaulting to stdout/stderr.
         *
         * @return the {@link TelemetryRecorder}
         */
        protected TelemetryRecorder recorder() {
            return recorder;
        }

        /**
         * Configures and returns the {@link Router} for this server.
         * <p>
         * Subclasses must implement this method to define routes.
         *
         * @return the configured {@link Router}
         */
        protected abstract Router configure();

        /**
         * Obtains the {@link ErrorHandler} for this server.
         * <p>
         * Subclasses may override this to provide a custom error handler.
         *
         * @return the {@link ErrorHandler}
         */
        protected ErrorHandler errorHandler() {
            return new DefaultErrorHandler(recorder);
        }

        /**
         * Obtains the {@link SecurityHeadersMiddleware} applied to every response.
         * <p>
         * Defaults to {@link SecurityHeadersMiddleware#defaults()} so a default app gets standard
         * hardening headers (X-Frame-Options, X-Content-Type-Options, HSTS, etc.) without needing
         * to opt in. Subclasses may override to customize the headers, or return {@code null} to
         * disable this middleware entirely.
         *
         * @return the {@link SecurityHeadersMiddleware}, or {@code null} to disable it
         */
        protected SecurityHeadersMiddleware securityHeaders() {
            return SecurityHeadersMiddleware.defaults();
        }

        private Handler applySecurityHeaders(final Router router) {
            final var securityHeaders = securityHeaders();
            return securityHeaders != null ? securityHeaders.apply(router) : router;
        }

        /**
         * Starts this {@link ServerApplication} with the default {@link ShutdownTimeout}.
         *
         * @param listenAddress the address to bind to
         * @param listenPort    the port to bind to
         * @throws IOException if the server cannot be started
         */
        public void start(final ListenAddress listenAddress,
                          final Port listenPort) throws IOException {
            start(listenAddress, listenPort, ShutdownTimeout.DEFAULT);
        }

        /**
         * Starts this {@link ServerApplication}.
         *
         * @param listenAddress   the address to bind to
         * @param listenPort      the port to bind to
         * @param shutdownTimeout the graceful shutdown drain timeout
         * @throws IOException if the server cannot be started
         */
        public void start(final ListenAddress listenAddress,
                          final Port listenPort,
                          final ShutdownTimeout shutdownTimeout) throws IOException {
            this.router = configure();
            this.shutdownTimeout = shutdownTimeout;

            final var address = new InetSocketAddress(listenAddress.value(), listenPort.get());

            this.transport = new HttpTransport(address, 0, applySecurityHeaders(router), errorHandler(),
                MaxRequestSize.DEFAULT, RequestTimeout.DEFAULT, recorder);
            this.transport.start();

            this.shutdownHook = new Thread(this::stop, "serve-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            recorder.info("ServerApplication started on %s", transport.address());

            startFuture.complete(this);
        }

        /**
         * Starts this {@link ServerApplication} with TLS/HTTPS and the default {@link ShutdownTimeout}.
         *
         * @param listenAddress the address to bind to
         * @param listenPort    the port to bind to
         * @param tls           the {@link TlsConfig} for HTTPS
         * @throws IOException if the server cannot be started
         */
        public void start(final ListenAddress listenAddress,
                          final Port listenPort,
                          final TlsConfig tls) throws IOException {
            start(listenAddress, listenPort, ShutdownTimeout.DEFAULT, tls);
        }

        /**
         * Starts this {@link ServerApplication} with TLS/HTTPS.
         *
         * @param listenAddress   the address to bind to
         * @param listenPort      the port to bind to
         * @param shutdownTimeout the graceful shutdown drain timeout
         * @param tls             the {@link TlsConfig} for HTTPS
         * @throws IOException if the server cannot be started
         */
        public void start(final ListenAddress listenAddress,
                          final Port listenPort,
                          final ShutdownTimeout shutdownTimeout,
                          final TlsConfig tls) throws IOException {
            this.router = configure();
            this.shutdownTimeout = shutdownTimeout;

            final var address = new InetSocketAddress(listenAddress.value(), listenPort.get());

            this.transport = HttpTransport.https(address, 0, applySecurityHeaders(router), errorHandler(),
                MaxRequestSize.DEFAULT, tls.sslContext(), TlsOptions.defaults(), RequestTimeout.DEFAULT, recorder);
            this.transport.start();

            this.shutdownHook = new Thread(this::stop, "serve-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            recorder.info("ServerApplication started on %s (HTTPS)", transport.address());

            startFuture.complete(this);
        }

        /**
         * Stops this {@link ServerApplication}.
         * <p>
         * This method is idempotent — calling it more than once has no effect.
         */
        public void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }

            if (transport != null) {
                transport.stop((int) shutdownTimeout.duration().toSeconds());

                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (final IllegalStateException ignored) {
                    // JVM is already shutting down
                }

                exitFuture.complete(this);

                recorder.info("ServerApplication stopped");
            }
        }

        @Override
        public Router router() {
            return router;
        }

        @Override
        public InetSocketAddress boundAddress() {
            return transport != null ? transport.address() : null;
        }

        @Override
        public Stream<InetAddress> addresses() {
            final var addr = boundAddress();

            if (addr != null) {
                return Stream.of(addr.getAddress());
            }

            return Stream.empty();
        }

        @Override
        public CompletableFuture<? extends ServerApplication> onStart() {
            return startFuture;
        }

        @Override
        public CompletableFuture<? extends ServerApplication> onExit() {
            return exitFuture;
        }

        @Override
        public void close() {
            stop();
        }
    }
}
