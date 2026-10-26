/*-
 * #%L
 * Serve Rate Limit
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
package build.serve.ratelimit;

import build.serve.foundation.Handler;
import build.serve.foundation.Request;
import build.serve.foundation.middleware.Middleware;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A {@link Middleware} that throttles credential guessing by counting {@code 401} responses per key.
 * <p>
 * Register it <em>before</em> (outside) the authentication middleware so it sees the outcome of every
 * authentication attempt. Once a key accumulates {@link Builder#maxFailures(int)} failures within the
 * window, further requests from it receive a 429 with {@code Retry-After} until the window ends,
 * without reaching the authentication layer. Successful requests are not counted and do not reset the
 * count, so an attacker cannot clear their failures by interleaving a login they own.
 * <p>
 * The recommended order is: this limiter, then authentication, then the handler. A
 * {@link RateLimitMiddleware} placed inside an authentication group never sees requests with invalid
 * credentials, so it does not slow guessing.
 * <p>
 * The default key is the remote TCP peer address, which a client cannot spoof. Use
 * {@link Builder#keyExtractor(Function)} to supply a forwarded address in a trusted reverse-proxy
 * deployment.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
public final class FailedAuthLimiter implements Middleware {

    private final int maxFailures;
    private final Duration window;
    private final Function<Request, String> keyExtractor;
    private final Clock clock;
    private final int maxKeys;
    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();

    private FailedAuthLimiter(final Builder builder) {
        this.maxFailures = builder.maxFailures;
        this.window = builder.window;
        this.keyExtractor = builder.keyExtractor != null
            ? builder.keyExtractor
            : req -> req.remoteAddress()
            .map(a -> a.getAddress().getHostAddress())
            .orElse("unknown");
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
        this.maxKeys = builder.maxKeys;
    }

    /**
     * Creates a new {@link Builder} for configuring a {@link FailedAuthLimiter}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Handler apply(final Handler next) {
        return exchange -> {
            final var key = keyExtractor.apply(exchange.request());
            final var tracked = failures.get(key);
            final var retryAfter = tracked == null
                ? 0L
                : tracked.retryAfterSeconds(clock.instant(), maxFailures, window);
            if (retryAfter > 0) {
                exchange.response()
                    .header("Retry-After", String.valueOf(retryAfter))
                    .status(429)
                    .send("");
                return;
            }
            next.handle(exchange);
            if (exchange.response().status() == 401) {
                record(key);
            }
        };
    }

    private void record(final String key) {
        if (!failures.containsKey(key) && failures.size() >= maxKeys) {
            evictOldest();
        }
        final var now = clock.instant();
        failures.compute(key, (k, existing) -> {
            final var tracked = existing != null ? existing : new Failures(now);
            tracked.add(now, window);
            return tracked;
        });
    }

    private void evictOldest() {
        String oldestKey = null;
        Instant oldest = null;
        for (final var entry : failures.entrySet()) {
            final var windowStart = entry.getValue().windowStart();
            if (oldest == null || windowStart.isBefore(oldest)) {
                oldest = windowStart;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            failures.remove(oldestKey);
        }
    }

    private static final class Failures {

        private int count;
        private Instant windowStart;

        Failures(final Instant now) {
            this.windowStart = now;
        }

        synchronized void add(final Instant now, final Duration window) {
            if (!now.isBefore(windowStart.plus(window))) {
                count = 0;
                windowStart = now;
            }
            count++;
        }

        synchronized long retryAfterSeconds(final Instant now, final int maxFailures, final Duration window) {
            final var windowEnd = windowStart.plus(window);
            if (count < maxFailures || !now.isBefore(windowEnd)) {
                return 0;
            }
            final var remaining = Duration.between(now, windowEnd);
            return Math.max(1L, (remaining.toNanos() + 999_999_999L) / 1_000_000_000L);
        }

        synchronized Instant windowStart() {
            return windowStart;
        }
    }

    /**
     * A builder for configuring a {@link FailedAuthLimiter}.
     *
     * @author reed.vonredwitz
     * @since Oct-2026
     */
    public static final class Builder {

        private int maxFailures;
        private Duration window;
        private Function<Request, String> keyExtractor;
        private Clock clock;
        private int maxKeys = 100_000;

        private Builder() {
        }

        /**
         * Sets the number of {@code 401} responses a key may receive within the window before it is blocked.
         *
         * @param maxFailures the failure limit
         * @return this {@link Builder}
         */
        public Builder maxFailures(final int maxFailures) {
            this.maxFailures = maxFailures;
            return this;
        }

        /**
         * Sets the duration over which failures are counted, and for which a blocked key stays blocked.
         *
         * @param window the window {@link Duration}
         * @return this {@link Builder}
         */
        public Builder window(final Duration window) {
            this.window = Objects.requireNonNull(window, "window");
            return this;
        }

        /**
         * Sets a custom function that extracts the key from a request. Defaults to the remote TCP peer
         * address, or {@code "unknown"} if the transport does not expose one. A forwarded-address header is
         * client-controlled unless your proxy overwrites it first.
         *
         * @param keyExtractor the key extraction function
         * @return this {@link Builder}
         */
        public Builder keyExtractor(final Function<Request, String> keyExtractor) {
            this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor");
            return this;
        }

        /**
         * Sets the maximum number of distinct keys tracked at once. When the limit is reached, the key with the
         * oldest window is evicted. Defaults to {@code 100_000}.
         * <p>
         * Eviction scans every tracked key, so a limit far above the number of distinct clients you expect
         * makes each new failing key costlier once the map is full. A source with many addresses can also
         * push out other keys' entries, so size the limit to bound memory, not to resist that.
         *
         * @param maxKeys the maximum number of tracked keys
         * @return this {@link Builder}
         */
        public Builder maxKeys(final int maxKeys) {
            if (maxKeys <= 0) {
                throw new IllegalArgumentException("maxKeys must be positive");
            }
            this.maxKeys = maxKeys;
            return this;
        }

        Builder clock(final Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the {@link FailedAuthLimiter}.
         *
         * @return a new {@link FailedAuthLimiter}
         */
        public FailedAuthLimiter build() {
            if (maxFailures <= 0) {
                throw new IllegalArgumentException("maxFailures must be positive");
            }
            Objects.requireNonNull(window, "window");
            return new FailedAuthLimiter(this);
        }
    }
}
