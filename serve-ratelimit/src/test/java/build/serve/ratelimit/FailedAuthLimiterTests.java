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

import build.serve.foundation.Exchange;
import build.serve.foundation.SimpleExchange;
import build.serve.ratelimit.RateLimitMiddlewareTests.MutableClock;
import build.serve.ratelimit.RateLimitMiddlewareTests.StubRequest;
import build.serve.ratelimit.RateLimitMiddlewareTests.StubResponse;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FailedAuthLimiter}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class FailedAuthLimiterTests {

    @Test
    void shouldBlockKeyAfterMaxFailures() throws Exception {
        var reached = new AtomicInteger();
        var handler = FailedAuthLimiter.builder()
            .maxFailures(2)
            .window(Duration.ofMinutes(1))
            .build()
            .apply(ex -> {
                reached.incrementAndGet();
                ex.response().status(401);
            });

        handler.handle(exchange("1.2.3.4", new StubResponse()));
        handler.handle(exchange("1.2.3.4", new StubResponse()));
        var blocked = new StubResponse();
        handler.handle(exchange("1.2.3.4", blocked));

        assertThat(reached).hasValue(2);
        assertThat(blocked.statusCode).isEqualTo(429);
        assertThat(blocked.headers).containsKey("Retry-After");
    }

    @Test
    void shouldNotCountSuccessfulRequests() throws Exception {
        var reached = new AtomicInteger();
        var handler = FailedAuthLimiter.builder()
            .maxFailures(1)
            .window(Duration.ofMinutes(1))
            .build()
            .apply(ex -> reached.incrementAndGet());

        for (var i = 0; i < 5; i++) {
            handler.handle(exchange("1.2.3.4", new StubResponse()));
        }

        assertThat(reached).hasValue(5);
    }

    @Test
    void shouldNotResetFailuresOnSuccess() throws Exception {
        var statuses = new ArrayDeque<>(List.of(401, 200, 401));
        var handler = FailedAuthLimiter.builder()
            .maxFailures(2)
            .window(Duration.ofMinutes(1))
            .build()
            .apply(ex -> ex.response().status(statuses.poll()));

        handler.handle(exchange("1.2.3.4", new StubResponse()));
        handler.handle(exchange("1.2.3.4", new StubResponse()));
        handler.handle(exchange("1.2.3.4", new StubResponse()));
        var response = new StubResponse();
        handler.handle(exchange("1.2.3.4", response));

        assertThat(response.statusCode).isEqualTo(429);
    }

    @Test
    void shouldTrackKeysIndependently() throws Exception {
        var handler = FailedAuthLimiter.builder()
            .maxFailures(1)
            .window(Duration.ofMinutes(1))
            .build()
            .apply(ex -> ex.response().status(401));

        handler.handle(exchange("10.0.0.1", new StubResponse()));
        var other = new StubResponse();
        handler.handle(exchange("10.0.0.2", other));

        assertThat(other.statusCode).isEqualTo(401);
    }

    @Test
    void shouldAllowAgainAfterWindow() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-26T00:00:00Z"));
        var handler = FailedAuthLimiter.builder()
            .maxFailures(1)
            .window(Duration.ofSeconds(30))
            .clock(clock)
            .build()
            .apply(ex -> ex.response().status(401));

        handler.handle(exchange("1.2.3.4", new StubResponse()));
        var blocked = new StubResponse();
        handler.handle(exchange("1.2.3.4", blocked));
        clock.advance(Duration.ofSeconds(30));
        var allowed = new StubResponse();
        handler.handle(exchange("1.2.3.4", allowed));

        assertThat(blocked.statusCode).isEqualTo(429);
        assertThat(blocked.headers).containsEntry("Retry-After", "30");
        assertThat(allowed.statusCode).isEqualTo(401);
    }

    @Test
    void shouldEvictOldestKeyWhenFull() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-26T00:00:00Z"));
        var handler = FailedAuthLimiter.builder()
            .maxFailures(1)
            .window(Duration.ofMinutes(1))
            .maxKeys(1)
            .clock(clock)
            .build()
            .apply(ex -> ex.response().status(401));

        handler.handle(exchange("10.0.0.1", new StubResponse()));
        clock.advance(Duration.ofSeconds(1));
        handler.handle(exchange("10.0.0.2", new StubResponse()));
        var evicted = new StubResponse();
        handler.handle(exchange("10.0.0.1", evicted));

        assertThat(evicted.statusCode).isEqualTo(401);
    }

    @Test
    void shouldSurviveConcurrentEvictionOfTrackedKeys() throws Exception {
        var handler = FailedAuthLimiter.builder()
            .maxFailures(1)
            .window(Duration.ofMinutes(1))
            .maxKeys(2)
            .build()
            .apply(ex -> ex.response().status(401));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (var i = 0; i < 2_000; i++) {
                var ip = "10.0." + (i % 8) + "." + (i % 250 + 1);
                tasks.add(executor.submit(() -> {
                    handler.handle(exchange(ip, new StubResponse()));
                    return null;
                }));
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void shouldCountFailuresFromConcurrentRequestsForOneKey() throws Exception {
        var handler = FailedAuthLimiter.builder()
            .maxFailures(100)
            .window(Duration.ofMinutes(1))
            .build()
            .apply(ex -> ex.response().status(401));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (var i = 0; i < 100; i++) {
                tasks.add(executor.submit(() -> {
                    handler.handle(exchange("1.2.3.4", new StubResponse()));
                    return null;
                }));
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        var blocked = new StubResponse();
        handler.handle(exchange("1.2.3.4", blocked));

        assertThat(blocked.statusCode).isEqualTo(429);
    }

    @Test
    void shouldRejectNonPositiveMaxFailures() {
        assertThatThrownBy(() -> FailedAuthLimiter.builder().window(Duration.ofSeconds(1)).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Exchange exchange(final String ip, final StubResponse response) {
        return new SimpleExchange(new StubRequest(Map.of(), new InetSocketAddress(ip, 0)), response);
    }
}
