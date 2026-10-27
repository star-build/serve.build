/*-
 * #%L
 * Serve Compression
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
package build.serve.compression;

import build.serve.foundation.DelegatingExchange;
import build.serve.foundation.Handler;
import build.serve.foundation.Response;
import build.serve.foundation.http.Cookie;
import build.serve.foundation.middleware.Middleware;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A {@link Middleware} that compresses response bodies based on the {@code Accept-Encoding} request header.
 * <p>
 * Supports gzip and deflate encodings. Only compresses responses whose content type matches the configured
 * compressible types and whose body exceeds the minimum size threshold.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public final class CompressionMiddleware implements Middleware {

    private static final Set<String> DEFAULT_COMPRESSIBLE_TYPES = Set.of(
        "text/*",
        "application/json",
        "application/javascript",
        "application/xml",
        "image/svg+xml"
    );

    private static final int DEFAULT_MINIMUM_SIZE = 1024;

    private final Set<String> encodings;
    private final int minimumSize;
    private final Set<String> compressibleTypes;

    private CompressionMiddleware(final Builder builder) {
        this.encodings = Set.copyOf(builder.encodings);
        this.minimumSize = builder.minimumSize;
        this.compressibleTypes = Set.copyOf(builder.compressibleTypes);
    }

    /**
     * Creates a {@link CompressionMiddleware} with default settings: gzip support, 1KB minimum threshold,
     * and standard compressible content types.
     *
     * @return a default {@link CompressionMiddleware}
     */
    public static CompressionMiddleware defaults() {
        return builder().gzip().build();
    }

    /**
     * Creates a new {@link Builder} for configuring a {@link CompressionMiddleware}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Handler apply(final Handler next) {
        return exchange -> {
            final var response = exchange.response();

            // WebSocket upgrades cannot be compressed; pass through the original exchange unchanged
            final var upgrade = exchange.request().header("Upgrade").orElse(null);

            if ("websocket".equalsIgnoreCase(upgrade)) {
                next.handle(exchange);

                return;
            }

            response.header("Vary", "Accept-Encoding");

            final var acceptEncoding = exchange.request().header("Accept-Encoding").orElse(null);

            if (acceptEncoding == null) {
                next.handle(exchange);

                return;
            }

            final var encoding = selectEncoding(acceptEncoding);

            if (encoding == null) {
                next.handle(exchange);

                return;
            }

            final var bufferingResponse = new BufferingResponse(response);
            final var wrappedExchange = DelegatingExchange.withResponse(exchange, bufferingResponse);

            next.handle(wrappedExchange);

            final var body = bufferingResponse.bufferedBody();

            if (body == null) {
                return;
            }

            final var contentType = bufferingResponse.contentType();

            if (bufferingResponse.hasContentEncoding()) {
                response.status(bufferingResponse.status());
                copyHeaders(bufferingResponse, response);
                response.send(body);

                return;
            }

            if (!isCompressibleType(contentType) || body.length < minimumSize) {
                response.status(bufferingResponse.status());
                copyHeaders(bufferingResponse, response);
                response.send(body);

                return;
            }

            final var compressed = compress(body, encoding);

            response.status(bufferingResponse.status());
            copyHeaders(bufferingResponse, response);
            response.header("Content-Encoding", encoding);
            response.send(compressed);
        };
    }

    private String selectEncoding(final String acceptEncoding) {
        final var lower = acceptEncoding.toLowerCase();

        if (encodings.contains("gzip") && lower.contains("gzip")) {
            return "gzip";
        }

        if (encodings.contains("deflate") && lower.contains("deflate")) {
            return "deflate";
        }

        return null;
    }

    private boolean isCompressibleType(final String contentType) {
        if (contentType == null) {
            return false;
        }

        final var baseType = contentType.contains(";")
            ? contentType.substring(0, contentType.indexOf(';')).trim()
            : contentType.trim();

        for (final var pattern : compressibleTypes) {
            if (pattern.endsWith("/*")) {
                final var prefix = pattern.substring(0, pattern.length() - 1);

                if (baseType.startsWith(prefix)) {
                    return true;
                }
            } else if (baseType.equalsIgnoreCase(pattern)) {
                return true;
            }
        }

        return false;
    }

    private static byte[] compress(final byte[] data, final String encoding) {
        try {
            final var buffer = new ByteArrayOutputStream();

            try (var out = "gzip".equals(encoding)
                ? new GZIPOutputStream(buffer)
                : new DeflaterOutputStream(buffer)) {
                out.write(data);
            }

            return buffer.toByteArray();
        } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException("Compression failed", e);
        }
    }

    private static void copyHeaders(final BufferingResponse source, final Response target) {
        source.headers().forEach(target::header);
    }

    /**
     * A buffering {@link Response} wrapper that captures the response body for post-processing.
     */
    private static final class BufferingResponse implements Response {

        private final Response delegate;
        private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        private int statusCode = 200;
        private byte[] body;
        private String contentType;
        private boolean hasContentEncoding;

        BufferingResponse(final Response delegate) {
            this.delegate = delegate;
        }

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

            if ("Content-Type".equalsIgnoreCase(name)) {
                contentType = value;
            }

            if ("Content-Encoding".equalsIgnoreCase(name)) {
                hasContentEncoding = true;
            }

            return this;
        }

        @Override
        public void send(final String body) {
            this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void send(final byte[] body) {
            this.body = body;
        }

        @Override
        public void removeHeader(final String name) {
            headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));

            if ("Content-Type".equalsIgnoreCase(name)) {
                contentType = null;
            }

            if ("Content-Encoding".equalsIgnoreCase(name)) {
                hasContentEncoding = false;
            }
        }

        /**
         * Serialization belongs to the transport, so the status and headers buffered so far are applied to the
         * delegate before it writes the body, which is therefore not compressed.
         */
        @Override
        public void json(final Object body) {
            delegate.status(statusCode);
            headers.forEach(delegate::header);
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
            throw new UnsupportedOperationException("Streaming not supported with compression middleware");
        }

        byte[] bufferedBody() {
            return body;
        }

        String contentType() {
            return contentType;
        }

        boolean hasContentEncoding() {
            return hasContentEncoding;
        }

        java.util.Map<String, String> headers() {
            return headers;
        }
    }

    /**
     * A builder for configuring a {@link CompressionMiddleware}.
     *
     * @author reed.vonredwitz
     * @since Mar-2026
     */
    public static final class Builder {

        private final Set<String> encodings = new LinkedHashSet<>();
        private int minimumSize = DEFAULT_MINIMUM_SIZE;
        private final Set<String> compressibleTypes = new LinkedHashSet<>(DEFAULT_COMPRESSIBLE_TYPES);

        private Builder() {
        }

        /**
         * Enables gzip encoding.
         *
         * @return this {@link Builder}
         */
        public Builder gzip() {
            encodings.add("gzip");

            return this;
        }

        /**
         * Enables deflate encoding.
         *
         * @return this {@link Builder}
         */
        public Builder deflate() {
            encodings.add("deflate");

            return this;
        }

        /**
         * Sets the minimum response body size for compression.
         *
         * @param minimumSize the minimum size in bytes
         * @return this {@link Builder}
         */
        public Builder minimumSize(final int minimumSize) {
            this.minimumSize = minimumSize;

            return this;
        }

        /**
         * Sets the compressible content type patterns.
         *
         * @param types the content type patterns (e.g., {@code "text/*"}, {@code "application/json"})
         * @return this {@link Builder}
         */
        public Builder compressibleTypes(final String... types) {
            Objects.requireNonNull(types, "types");
            compressibleTypes.clear();

            for (final var type : types) {
                compressibleTypes.add(type);
            }

            return this;
        }

        /**
         * Builds the {@link CompressionMiddleware}.
         *
         * @return a new {@link CompressionMiddleware}
         */
        public CompressionMiddleware build() {
            if (encodings.isEmpty()) {
                encodings.add("gzip");
            }

            return new CompressionMiddleware(this);
        }
    }
}
