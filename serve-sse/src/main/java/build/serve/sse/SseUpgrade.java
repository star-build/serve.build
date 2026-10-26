/*-
 * #%L
 * Serve SSE
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
package build.serve.sse;

import build.serve.foundation.Exchange;
import build.serve.foundation.Handler;
import com.sun.net.httpserver.HttpExchange;

/**
 * Bridges a {@link Handler} to Server-Sent Events by setting the appropriate
 * response headers and providing an {@link SseEmitter} to the handler.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class SseUpgrade {

    /**
     * Prevent instantiation.
     */
    private SseUpgrade() {
    }

    /**
     * Creates a {@link Handler} that establishes an SSE stream and delegates
     * to the specified {@link SseHandler}.
     *
     * @param sseHandler the handler for the SSE connection
     * @return a {@link Handler} that sets up SSE streaming
     */
    public static Handler sse(final SseHandler sseHandler) {
        return exchange -> {
            exchange.response().header("Content-Type", "text/event-stream");
            exchange.response().header("Cache-Control", "no-cache");
            exchange.response().header("Connection", "keep-alive");
            exchange.response().header("X-Accel-Buffering", "no");

            final var httpExchange = exchange.attribute(Exchange.HTTP_EXCHANGE_ATTRIBUTE, HttpExchange.class)
                .orElseThrow(() -> new IllegalStateException(
                    "SSE requires the HttpExchange attribute. Use SseUpgrade with HttpTransport."));

            httpExchange.sendResponseHeaders(200, 0);

            // The stream is long-lived by design, so the request timeout must not interrupt it
            exchange.attribute(Exchange.CANCEL_TIMEOUT_ATTRIBUTE, Runnable.class).ifPresent(Runnable::run);

            final var out = httpExchange.getResponseBody();
            final var emitter = new SseEmitterImpl(out);

            try {
                sseHandler.handle(emitter);
            } finally {
                emitter.close();
            }
        };
    }
}
