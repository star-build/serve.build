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

import build.base.json.JsonValue;
import build.serve.foundation.Request;
import build.serve.foundation.Response;
import build.serve.foundation.SimpleExchange;
import build.serve.foundation.http.Cookie;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only stubs for {@link Request} and {@link Response} used across devtools tests.
 */
final class StubExchange {

    private StubExchange() {
    }

    static SimpleExchange get(final String path, final StubResponse response) {
        return new SimpleExchange(new StubRequest("GET", path, Map.of()), response);
    }

    static SimpleExchange get(final String path,
                              final Map<String, String> headers,
                              final StubResponse response) {
        return new SimpleExchange(new StubRequest("GET", path, headers), response);
    }

    static final class StubRequest implements Request {

        private final String method;
        private final String path;
        private final Map<String, List<String>> headers;

        StubRequest(final String method, final String path, final Map<String, String> headers) {
            this.method = method;
            this.path = path;
            final Map<String, List<String>> h = new LinkedHashMap<>();
            headers.forEach((k, v) -> h.put(k, List.of(v)));
            this.headers = Map.copyOf(h);
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public URI uri() {
            return URI.create(path);
        }

        @Override
        public String path() {
            return path;
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
            return headers;
        }

        @Override
        public Optional<String> header(final String name) {
            final var values = headers.get(name);
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
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

        int statusCode = 200;
        final Map<String, String> headers = new LinkedHashMap<>();
        String body = "";
        boolean sent;

        @Override
        public Response status(final int status) {
            this.statusCode = status;
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
        public void removeHeader(final String name) {
            headers.remove(name);
        }

        @Override
        public void send(final String body) {
            this.body = body;
            this.sent = true;
        }

        @Override
        public void send(final byte[] body) {
            this.body = new String(body);
            this.sent = true;
        }

        @Override
        public void json(final Object body) {
            this.body = String.valueOf(body);
            this.sent = true;
        }

        @Override
        public void cookie(final Cookie cookie) {
            // no-op
        }

        @Override
        public void deleteCookie(final String name) {
            // no-op
        }

        @Override
        public OutputStream bodyAsStream() {
            throw new UnsupportedOperationException();
        }
    }
}
