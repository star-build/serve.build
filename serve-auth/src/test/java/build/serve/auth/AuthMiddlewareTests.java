/*-
 * #%L
 * Serve Auth
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
package build.serve.auth;

import build.base.json.JsonValue;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.serve.foundation.Exchange;
import build.serve.foundation.Request;
import build.serve.foundation.Response;
import build.serve.foundation.SimpleExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthMiddlewareTests {

    private static final Principal REED = SimplePrincipal.of("reed");

    @Test
    void shouldWarnAboutQueryParamKeyWhenParamNameContainsPercent() {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var recorder = PrintStreamTelemetryRecorder.of(URI.create("serve://auth-test"), stream, stream);

        ApiKeyStrategy.fromQueryParam("key%s", key -> Optional.empty(), recorder);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("fromQueryParam(\"key%s\")");
    }

    // --- AuthMiddleware ---

    @Test
    void shouldCallNextWhenStrategyReturnsAPrincipal() throws Exception {
        var middleware = AuthMiddleware.builder()
            .strategy(req -> Optional.of(REED))
            .build();

        var nextCalled = new AtomicBoolean(false);
        middleware.apply(ex -> nextCalled.set(true)).handle(exchange("", new StubResponse()));

        assertThat(nextCalled).isTrue();
    }

    @Test
    void shouldReturn401WhenStrategyReturnsEmpty() throws Exception {
        var middleware = AuthMiddleware.builder()
            .strategy(req -> Optional.empty())
            .build();

        var response = new StubResponse();
        middleware.apply(ex -> {
        }).handle(exchange("", response));

        assertThat(response.statusCode).isEqualTo(401);
    }

    @Test
    void shouldSetWwwAuthenticateChallengeOn401() throws Exception {
        var strategy = new AuthStrategy() {
            @Override
            public Optional<Principal> authenticate(final Request request) {
                return Optional.empty();
            }

            @Override
            public String challenge() {
                return "Basic realm=\"myapp\"";
            }
        };

        var response = new StubResponse();
        AuthMiddleware.builder().strategy(strategy).build()
            .apply(ex -> {
            }).handle(exchange("", response));

        assertThat(response.headers).containsEntry("WWW-Authenticate", "Basic realm=\"myapp\"");
    }

    @Test
    void shouldNotCallNextOn401() throws Exception {
        var nextCalled = new AtomicBoolean(false);
        AuthMiddleware.builder()
            .strategy(req -> Optional.empty())
            .build()
            .apply(ex -> nextCalled.set(true))
            .handle(exchange("", new StubResponse()));

        assertThat(nextCalled).isFalse();
    }

    @Test
    void shouldBindPrincipalToAuthContext() throws Exception {
        var captured = new AtomicReference<Optional<Principal>>();
        AuthMiddleware.builder()
            .strategy(req -> Optional.of(REED))
            .build()
            .apply(ex -> captured.set(AuthContext.current()))
            .handle(exchange("", new StubResponse()));

        assertThat(captured.get()).contains(REED);
    }

    // --- BearerTokenStrategy ---

    @Test
    void shouldAuthenticateValidBearerToken() {
        var strategy = BearerTokenStrategy.of(t -> t.equals("secret") ? Optional.of(REED) : Optional.empty());

        assertThat(strategy.authenticate(request("Authorization", "Bearer secret"))).contains(REED);
    }

    @Test
    void shouldRejectInvalidBearerToken() {
        var strategy = BearerTokenStrategy.of(t -> Optional.empty());

        assertThat(strategy.authenticate(request("Authorization", "Bearer bad"))).isEmpty();
    }

    @Test
    void shouldRejectMissingAuthorizationHeader() {
        var strategy = BearerTokenStrategy.of(t -> Optional.of(REED));

        assertThat(strategy.authenticate(request("X-Other", "value"))).isEmpty();
    }

    @Test
    void shouldRejectNonBearerScheme() {
        var strategy = BearerTokenStrategy.of(t -> Optional.of(REED));

        assertThat(strategy.authenticate(request("Authorization", "Basic dXNlcjpwYXNz"))).isEmpty();
    }

    // --- BasicAuthStrategy ---

    @Test
    void shouldAuthenticateValidBasicCredentials() {
        var strategy = BasicAuthStrategy.of((u, p) ->
            "reed".equals(u) && "pass".equals(p) ? Optional.of(REED) : Optional.empty());

        var encoded = Base64.getEncoder().encodeToString("reed:pass".getBytes(StandardCharsets.UTF_8));
        assertThat(strategy.authenticate(request("Authorization", "Basic " + encoded))).contains(REED);
    }

    @Test
    void shouldRejectInvalidBasicCredentials() {
        var strategy = BasicAuthStrategy.of((u, p) -> Optional.empty());

        var encoded = Base64.getEncoder().encodeToString("bad:pass".getBytes(StandardCharsets.UTF_8));
        assertThat(strategy.authenticate(request("Authorization", "Basic " + encoded))).isEmpty();
    }

    @Test
    void shouldRejectMalformedBase64InBasicAuth() {
        var strategy = BasicAuthStrategy.of((u, p) -> Optional.of(REED));

        assertThat(strategy.authenticate(request("Authorization", "Basic !!notbase64!!"))).isEmpty();
    }

    @Test
    void shouldRejectBasicCredentialsWithNoColon() {
        var strategy = BasicAuthStrategy.of((u, p) -> Optional.of(REED));

        var encoded = Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8));
        assertThat(strategy.authenticate(request("Authorization", "Basic " + encoded))).isEmpty();
    }

    @Test
    void shouldIncludeRealmInChallenge() {
        assertThat(BasicAuthStrategy.of("myapp", (u, p) -> Optional.empty()).challenge())
            .isEqualTo("Basic realm=\"myapp\"");
    }

    @Test
    void shouldRejectRealmWithQuote() {
        assertThatThrownBy(() -> BasicAuthStrategy.of("my\"app", (u, p) -> Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectRealmWithCrlf() {
        assertThatThrownBy(() -> BasicAuthStrategy.of("app\r\nX-Injected: val", (u, p) -> Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- ApiKeyStrategy ---

    @Test
    void shouldAuthenticateValidApiKeyFromHeader() {
        var strategy = ApiKeyStrategy.fromHeader("X-Api-Key", k -> k.equals("key123") ? Optional.of(REED) : Optional.empty());

        assertThat(strategy.authenticate(request("X-Api-Key", "key123"))).contains(REED);
    }

    @Test
    void shouldRejectMissingApiKeyHeader() {
        var strategy = ApiKeyStrategy.fromHeader("X-Api-Key", k -> Optional.of(REED));

        assertThat(strategy.authenticate(request("X-Other", "value"))).isEmpty();
    }

    @Test
    void shouldAuthenticateValidApiKeyFromQueryParam() {
        var strategy = ApiKeyStrategy.fromQueryParam("api_key", k -> k.equals("key123") ? Optional.of(REED) : Optional.empty());

        assertThat(strategy.authenticate(queryParamRequest("api_key", "key123"))).contains(REED);
    }

    // --- AuthStrategy.anyOf ---

    @Test
    void shouldReturnFirstSuccessfulStrategy() {
        var other = SimplePrincipal.of("other");
        var strategy = AuthStrategy.anyOf(
            req -> Optional.empty(),
            req -> Optional.of(other)
        );

        assertThat(strategy.authenticate(request("X-None", ""))).contains(other);
    }

    @Test
    void shouldReturnEmptyWhenAllStrategiesFail() {
        var strategy = AuthStrategy.anyOf(
            req -> Optional.empty(),
            req -> Optional.empty()
        );

        assertThat(strategy.authenticate(request("X-None", ""))).isEmpty();
    }

    @Test
    void shouldJoinDelegateChallengesInAnyOf() {
        var basic = BasicAuthStrategy.of("myapp", (u, p) -> Optional.empty());
        var bearer = BearerTokenStrategy.of(t -> Optional.empty());
        var strategy = AuthStrategy.anyOf(basic, bearer);

        assertThat(strategy.challenge())
            .isEqualTo("Basic realm=\"myapp\", Bearer");
    }

    // --- helpers ---

    private static Exchange exchange(final String headerName, final StubResponse response) {
        return new SimpleExchange(request(headerName, ""), response);
    }

    private static Request request(final String headerName, final String headerValue) {
        return new StubRequest(Map.of(headerName, headerValue), Map.of());
    }

    private static Request queryParamRequest(final String paramName, final String paramValue) {
        return new StubRequest(Map.of(), Map.of(paramName, paramValue));
    }

    private static final class StubRequest implements Request {

        private final Map<String, String> headers;
        private final Map<String, String> queryParams;

        StubRequest(final Map<String, String> headers, final Map<String, String> queryParams) {
            this.headers = headers;
            this.queryParams = queryParams;
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
            return queryParams.containsKey(name) ? List.of(queryParams.get(name)) : List.of();
        }

        @Override
        public Optional<String> queryParam(final String name) {
            return Optional.ofNullable(queryParams.get(name));
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

    private static final class StubResponse implements Response {

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
