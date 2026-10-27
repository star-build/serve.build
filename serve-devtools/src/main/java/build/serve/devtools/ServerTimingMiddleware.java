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

import build.serve.foundation.DelegatingExchange;
import build.serve.foundation.Handler;
import build.serve.foundation.Response;
import build.serve.foundation.http.Cookie;
import build.serve.foundation.middleware.Middleware;

import java.io.OutputStream;
import java.util.Locale;

/**
 * A {@link Middleware} that emits a {@code Server-Timing} header on each response so the
 * per-request duration shows up in the browser DevTools "Network &gt; Timing" tab.
 * <p>
 * The duration measured is wall-clock time from when the middleware first sees the request to
 * when the handler calls {@code send()} or {@code json()} (i.e. time-to-first-byte from the
 * server's perspective). Subsequent writes are not re-measured.
 * <pre>{@code
 * RouterBuilder.create()
 *     .middleware(ServerTimingMiddleware.create())
 *     ...
 * }</pre>
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public final class ServerTimingMiddleware implements Middleware {

    private static final String HEADER = "Server-Timing";
    private static final String METRIC_NAME = "total";

    private ServerTimingMiddleware() {
    }

    /**
     * Creates a new {@link ServerTimingMiddleware}.
     *
     * @return a new {@link ServerTimingMiddleware}
     */
    public static ServerTimingMiddleware create() {
        return new ServerTimingMiddleware();
    }

    @Override
    public Handler apply(final Handler next) {
        return exchange -> {
            final long start = System.nanoTime();
            final var timingResponse = new TimingResponse(exchange.response(), start);
            next.handle(DelegatingExchange.withResponse(exchange, timingResponse));
        };
    }

    private static String formatMs(final long startNanos) {
        final double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
        return String.format(Locale.ROOT, "%s;dur=%.1f", METRIC_NAME, ms);
    }

    /**
     * A {@link Response} that prepends a {@code Server-Timing} header the first time a body is
     * committed (via {@code send} / {@code json}).
     */
    private static final class TimingResponse implements Response {

        private final Response delegate;
        private final long start;
        private boolean committed;

        TimingResponse(final Response delegate, final long start) {
            this.delegate = delegate;
            this.start = start;
        }

        private void commit() {
            if (committed) {
                return;
            }
            committed = true;
            delegate.header(HEADER, formatMs(start));
        }

        @Override
        public Response status(final int statusCode) {
            delegate.status(statusCode);
            return this;
        }

        @Override
        public int status() {
            return delegate.status();
        }

        @Override
        public Response header(final String name, final String value) {
            delegate.header(name, value);
            return this;
        }

        @Override
        public void removeHeader(final String name) {
            delegate.removeHeader(name);
        }

        @Override
        public void send(final String body) {
            commit();
            delegate.send(body);
        }

        @Override
        public void send(final byte[] body) {
            commit();
            delegate.send(body);
        }

        @Override
        public void json(final Object body) {
            commit();
            delegate.json(body);
        }

        @Override
        public void cookie(final Cookie cookie) {
            delegate.cookie(cookie);
        }

        @Override
        public void deleteCookie(final String name) {
            delegate.deleteCookie(name);
        }

        @Override
        public OutputStream bodyAsStream() {
            commit();
            return delegate.bodyAsStream();
        }
    }
}
