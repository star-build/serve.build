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

import build.base.json.JsonValue;
import build.serve.foundation.Exchange;
import build.serve.foundation.Request;
import build.serve.foundation.Response;
import build.serve.foundation.SimpleExchange;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitMiddlewareTests {

    @Test
    void shouldPassRequestsWithinLimit() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(2)
            .per(Duration.ofSeconds(10))
            .build();

        var nextCalled = new AtomicLong(0);
        var handler = middleware.apply(ex -> nextCalled.incrementAndGet());

        handler.handle(createExchange("1.2.3.4", new StubResponse()));
        handler.handle(createExchange("1.2.3.4", new StubResponse()));

        assertThat(nextCalled).hasValue(2);
    }

    @Test
    void shouldReturn429WhenLimitExceeded() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(2)
            .per(Duration.ofSeconds(10))
            .build();

        var response3 = new StubResponse();
        var nextCalled = new AtomicLong(0);
        var handler = middleware.apply(ex -> nextCalled.incrementAndGet());

        handler.handle(createExchange("1.2.3.4", new StubResponse()));
        handler.handle(createExchange("1.2.3.4", new StubResponse()));
        handler.handle(createExchange("1.2.3.4", response3));

        assertThat(nextCalled).hasValue(2);
        assertThat(response3.statusCode).isEqualTo(429);
    }

    @Test
    void shouldSetRateLimitHeaders() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(5)
            .per(Duration.ofMinutes(1))
            .build();

        var response = new StubResponse();
        middleware.apply(ex -> {
        }).handle(createExchange("1.2.3.4", response));

        assertThat(response.headers).containsEntry("X-RateLimit-Limit", "5");
        assertThat(response.headers).containsEntry("X-RateLimit-Remaining", "4");
    }

    @Test
    void shouldRefillTokensAfterWindow() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-04-19T00:00:00Z"));

        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(10))
            .clock(clock)
            .build();

        var handler = middleware.apply(ex -> {
        });

        handler.handle(createExchange("1.2.3.4", new StubResponse()));

        clock.advance(Duration.ofSeconds(10));

        var response = new StubResponse();
        handler.handle(createExchange("1.2.3.4", response));

        assertThat(response.statusCode).isEqualTo(200);
    }

    @Test
    void shouldTrackKeysIndependently() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(10))
            .build();

        var handler = middleware.apply(ex -> {
        });

        handler.handle(createExchange("10.0.0.1", new StubResponse()));

        var responseB = new StubResponse();
        handler.handle(createExchange("10.0.0.2", responseB));

        var responseA2 = new StubResponse();
        handler.handle(createExchange("10.0.0.1", responseA2));

        assertThat(responseB.statusCode).isEqualTo(200);
        assertThat(responseA2.statusCode).isEqualTo(429);
    }

    @Test
    void shouldUseRemoteAddressAsDefaultKey() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(10))
            .build();

        var handler = middleware.apply(ex -> {
        });
        handler.handle(createExchange("5.6.7.8", new StubResponse()));

        var response = new StubResponse();
        handler.handle(createExchange("5.6.7.8", response));

        assertThat(response.statusCode).isEqualTo(429);
    }

    @Test
    void shouldNotBeBypassedBySpoofedXForwardedForHeader() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(10))
            .build();

        var handler = middleware.apply(ex -> {
        });
        // Same remote address, different spoofed X-Forwarded-For on each request — the default
        // key extractor no longer trusts that client-controlled header, so this must not bypass
        // the limit the way the old X-Forwarded-For-based default would have.
        handler.handle(new SimpleExchange(
            new StubRequest(Map.of("X-Forwarded-For", "1.1.1.1"), new InetSocketAddress("5.6.7.8", 0)),
            new StubResponse()));

        var response = new StubResponse();
        handler.handle(new SimpleExchange(
            new StubRequest(Map.of("X-Forwarded-For", "2.2.2.2"), new InetSocketAddress("5.6.7.8", 0)),
            response));

        assertThat(response.statusCode).isEqualTo(429);
    }

    @Test
    void shouldEvictLeastRecentlyUsedBucketInsteadOfPermanentlyLockingOut() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(10))
            .maxBuckets(2)
            .build();

        var handler = middleware.apply(ex -> {
        });

        handler.handle(createExchange("10.0.0.1", new StubResponse()));
        handler.handle(createExchange("10.0.0.2", new StubResponse()));

        // Both tracked keys are now at capacity (2 == maxBuckets). A brand new key must still be
        // served by evicting the least-recently-used bucket, rather than being permanently 429'd.
        var responseC = new StubResponse();
        handler.handle(createExchange("10.0.0.3", responseC));
        assertThat(responseC.statusCode).isEqualTo(200);

        // 10.0.0.1 was the least-recently-used and was evicted to make room for 10.0.0.3 above,
        // so it's treated as a fresh key here too.
        var responseA2 = new StubResponse();
        handler.handle(createExchange("10.0.0.1", responseA2));
        assertThat(responseA2.statusCode).isEqualTo(200);
    }

    @Test
    void shouldUseCustomKeyExtractor() throws Exception {
        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(10))
            .keyExtractor(req -> req.header("X-User-Id").orElse("anon"))
            .build();

        var handler = middleware.apply(ex -> {
        });

        var r1 = new StubResponse();
        var r2 = new StubResponse();

        handler.handle(createExchangeWithHeader("X-User-Id", "user-1", r1));
        handler.handle(createExchangeWithHeader("X-User-Id", "user-2", r2));

        assertThat(r1.statusCode).isEqualTo(200);
        assertThat(r2.statusCode).isEqualTo(200);
    }

    @Test
    void shouldSetRetryAfterHeaderWhenRateLimited() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-04-19T00:00:00Z"));

        var middleware = RateLimitMiddleware.builder()
            .limit(1)
            .per(Duration.ofSeconds(30))
            .clock(clock)
            .build();

        var handler = middleware.apply(ex -> {
        });
        handler.handle(createExchange("1.2.3.4", new StubResponse()));

        var response = new StubResponse();
        handler.handle(createExchange("1.2.3.4", response));

        assertThat(response.statusCode).isEqualTo(429);
        assertThat(response.headers).containsKey("Retry-After");
    }

    private static Exchange createExchange(final String ip, final StubResponse response) {
        return new SimpleExchange(
            new StubRequest(Map.of("X-Forwarded-For", ip), new InetSocketAddress(ip, 0)),
            response);
    }

    private static Exchange createExchangeWithHeader(
        final String headerName,
        final String headerValue,
        final StubResponse response
    ) {
        return new SimpleExchange(new StubRequest(Map.of(headerName, headerValue), null), response);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(final Instant initial) {
            this.now = initial;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class StubRequest implements Request {

        private final Map<String, String> headers;
        private final InetSocketAddress remoteAddress;

        StubRequest(final Map<String, String> headers, final InetSocketAddress remoteAddress) {
            this.headers = headers;
            this.remoteAddress = remoteAddress;
        }

        @Override
        public Optional<InetSocketAddress> remoteAddress() {
            return Optional.ofNullable(remoteAddress);
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
            return Optional.ofNullable(headers.get(name));
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

    static final class StubResponse implements Response {

        final Map<String, String> headers = new LinkedHashMap<>();
        int statusCode = 200;

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
        }

        @Override
        public void send(final byte[] body) {
        }

        @Override
        public void json(final Object body) {
        }

        @Override
        public OutputStream bodyAsStream() {
            throw new UnsupportedOperationException();
        }
    }
}
