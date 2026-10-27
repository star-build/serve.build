/*-
 * #%L
 * Serve MCP
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
package build.serve.mcp;

import build.base.json.JsonValue;
import build.serve.foundation.Request;
import build.serve.foundation.Response;
import build.serve.foundation.SimpleExchange;
import build.serve.foundation.context.RequestContext;
import build.serve.foundation.http.Cookie;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link McpServer} refuses unauthenticated requests from non-loopback peers unless that is
 * explicitly allowed.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpRemoteAccessTests {

    private static final String INITIALIZE =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";

    @Test
    void shouldRefuseUnauthenticatedRemotePeer() throws Exception {
        final var server = McpServer.builder("remote-server", "1.0.0").build();

        final var response = post(server, "203.0.113.7");

        assertThat(response.status).isEqualTo(401);
    }

    @Test
    void shouldAcceptUnauthenticatedLoopbackPeer() throws Exception {
        final var server = McpServer.builder("remote-server", "1.0.0").build();

        final var response = post(server, "127.0.0.1");

        assertThat(response.status).isEqualTo(200);
    }

    @Test
    void shouldRefuseUnauthenticatedPeerWithUnresolvedAddress() throws Exception {
        final var server = McpServer.builder("remote-server", "1.0.0").build();
        final var response = new StubResponse();

        server.handler().handle(new SimpleExchange(
            new StubRequest(InetSocketAddress.createUnresolved("remote.example", 40_000)), response));

        assertThat(response.status).isEqualTo(401);
    }

    @Test
    void shouldRefuseUnauthenticatedPeerWithNoRemoteAddress() throws Exception {
        final var server = McpServer.builder("remote-server", "1.0.0").build();
        final var response = new StubResponse();

        server.handler().handle(new SimpleExchange(new StubRequest((InetSocketAddress) null), response));

        assertThat(response.status).isEqualTo(401);
    }

    @Test
    void shouldAcceptAuthenticatedRemotePeer() throws Exception {
        final var server = McpServer.builder("remote-server", "1.0.0").build();

        final var response = ScopedValue.where(RequestContext.PRINCIPAL, "alice")
            .call(() -> post(server, "203.0.113.7"));

        assertThat(response.status).isEqualTo(200);
    }

    @Test
    void shouldAcceptUnauthenticatedRemotePeerWhenExplicitlyAllowed() throws Exception {
        final var server = McpServer.builder("remote-server", "1.0.0")
            .allowUnauthenticatedRemote()
            .build();

        final var response = post(server, "203.0.113.7");

        assertThat(response.status).isEqualTo(200);
    }

    private static StubResponse post(final McpServer server, final String peer) throws Exception {
        final var response = new StubResponse();
        server.handler().handle(new SimpleExchange(new StubRequest(peer), response));
        return response;
    }

    private static final class StubRequest implements Request {

        private final InetSocketAddress peer;

        StubRequest(final String peer) {
            this(new InetSocketAddress(peer, 40_000));
        }

        StubRequest(final InetSocketAddress peer) {
            this.peer = peer;
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public URI uri() {
            return URI.create("/mcp");
        }

        @Override
        public String path() {
            return "/mcp";
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
            return Map.of("Host", List.of("localhost"));
        }

        @Override
        public Optional<String> header(final String name) {
            return "Host".equalsIgnoreCase(name) ? Optional.of("localhost") : Optional.empty();
        }

        @Override
        public InputStream bodyAsStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public String bodyAsString() {
            return INITIALIZE;
        }

        @Override
        public JsonValue bodyAsJson() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<InetSocketAddress> remoteAddress() {
            return Optional.ofNullable(peer);
        }
    }

    private static final class StubResponse implements Response {

        int status = 200;

        @Override
        public Response status(final int status) {
            this.status = status;
            return this;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public Response header(final String name, final String value) {
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
        public void cookie(final Cookie cookie) {
        }

        @Override
        public void deleteCookie(final String name) {
        }

        @Override
        public OutputStream bodyAsStream() {
            throw new UnsupportedOperationException();
        }
    }
}
