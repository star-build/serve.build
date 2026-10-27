/*-
 * #%L
 * Serve Foundation
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
package build.serve.foundation.routing;

import build.base.json.JsonValue;
import build.serve.foundation.DelegatingExchange;
import build.serve.foundation.Exchange;
import build.serve.foundation.Handler;
import build.serve.foundation.Request;
import build.serve.foundation.error.NotFoundException;
import build.serve.foundation.http.Cookie;
import build.serve.foundation.middleware.Middleware;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A builder for constructing {@link Router} instances with routes and middleware.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public class RouterBuilder {

    /**
     * The routes registered with this builder.
     */
    private final List<Route> routes;

    /**
     * The middleware registered with this builder.
     */
    private final List<Middleware> middlewares;

    /**
     * Constructs a {@link RouterBuilder}.
     */
    private RouterBuilder() {
        this.routes = new ArrayList<>();
        this.middlewares = new ArrayList<>();
    }

    /**
     * Creates a new {@link RouterBuilder}.
     *
     * @return a new {@link RouterBuilder}
     */
    public static RouterBuilder create() {
        return new RouterBuilder();
    }

    /**
     * Registers a route for any HTTP method.
     *
     * @param pattern the path pattern
     * @param handler the {@link Handler}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder route(final String pattern,
                               final Handler handler) {
        routes.add(new Route(null, pattern, handler));

        return this;
    }

    /**
     * Registers a route for HTTP GET.
     *
     * @param pattern the path pattern
     * @param handler the {@link Handler}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder get(final String pattern,
                             final Handler handler) {
        routes.add(new Route("GET", pattern, handler));

        return this;
    }

    /**
     * Registers a route for HTTP POST.
     *
     * @param pattern the path pattern
     * @param handler the {@link Handler}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder post(final String pattern,
                              final Handler handler) {
        routes.add(new Route("POST", pattern, handler));

        return this;
    }

    /**
     * Registers a route for HTTP PUT.
     *
     * @param pattern the path pattern
     * @param handler the {@link Handler}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder put(final String pattern,
                             final Handler handler) {
        routes.add(new Route("PUT", pattern, handler));

        return this;
    }

    /**
     * Registers a route for HTTP DELETE.
     *
     * @param pattern the path pattern
     * @param handler the {@link Handler}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder delete(final String pattern,
                                final Handler handler) {
        routes.add(new Route("DELETE", pattern, handler));

        return this;
    }

    /**
     * Mounts a sub-{@link Router} at the specified prefix.
     * <p>
     * When a request path starts with the prefix, the remainder of the path is passed to the sub-router.
     *
     * @param prefix    the path prefix (e.g., "/api/v1")
     * @param subRouter the sub-{@link Router}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder route(final String prefix,
                               final Router subRouter) {
        routes.add(new Route(null, prefix, subRouter, true));

        return this;
    }

    /**
     * Mounts a sub-router (built from the specified {@link RouterBuilder}) at the specified prefix.
     *
     * @param prefix     the path prefix (e.g., "/api/v1")
     * @param subBuilder the {@link RouterBuilder} to build and mount
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder route(final String prefix,
                               final RouterBuilder subBuilder) {
        return route(prefix, subBuilder.build());
    }

    /**
     * Defines a group of routes that share a common set of middleware, without path prefix stripping.
     * <p>
     * Routes registered inside the group keep their full paths. Middleware registered on the group
     * applies only to those routes and does not affect routes outside the group.
     * <pre>{@code
     * RouterBuilder.create()
     *     .get("/health", healthHandler)          // no auth
     *     .group(g -> g
     *         .middleware(authMiddleware)
     *         .route("/mcp", mcpHandler)          // auth required
     *         .get("/api/data", dataHandler))     // auth required
     *     .build()
     * }</pre>
     *
     * @param configure a {@link Consumer} that configures the group's routes and middleware
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder group(final Consumer<RouterBuilder> configure) {
        final var group = new RouterBuilder();
        configure.accept(group);

        for (final var route : group.routes) {
            Handler handler = route.handler();
            for (int i = group.middlewares.size() - 1; i >= 0; i--) {
                handler = group.middlewares.get(i).apply(handler);
            }
            routes.add(new Route(route.method(), route.pattern(), handler, route.isPrefix()));
        }

        return this;
    }

    /**
     * Registers a {@link Middleware} to apply to all routes.
     *
     * @param middleware the {@link Middleware}
     * @return this {@link RouterBuilder} for fluent chaining
     */
    public RouterBuilder middleware(final Middleware middleware) {
        middlewares.add(middleware);

        return this;
    }

    /**
     * Builds the {@link Router} from the registered routes and middleware.
     *
     * @return a new {@link Router}
     */
    public Router build() {
        final List<Route> builtRoutes = List.copyOf(routes);
        final List<Middleware> builtMiddlewares = List.copyOf(middlewares);

        return exchange -> {
            final var request = exchange.request();
            final var path = request.path();
            final var method = request.method();

            for (final var route : builtRoutes) {
                final var match = route.match(method, path);

                if (match.isPresent()) {
                    final var routeMatch = match.get();

                    // Merge path params (preserve any from parent routers)
                    final Map<String, String> mergedParams = new HashMap<>(exchange.pathParams());

                    mergedParams.putAll(routeMatch.params());

                    // Build the handler chain with middleware
                    Handler handler = routeMatch.handler();

                    // Apply middleware in reverse order so the first registered wraps outermost
                    for (int i = builtMiddlewares.size() - 1; i >= 0; i--) {
                        handler = builtMiddlewares.get(i).apply(handler);
                    }

                    // For prefix routes (nested routers), rewrite the path
                    if (routeMatch.handler() instanceof Router) {
                        final var rewritten = DelegatingExchange.withRequest(
                            exchange, new PrefixStrippedRequest(request, routeMatch.remainingPath()));

                        rewritten.attribute(Exchange.PATH_PARAMS_ATTRIBUTE, mergedParams);
                        handler.handle(rewritten);
                    } else {
                        exchange.attribute(Exchange.PATH_PARAMS_ATTRIBUTE, mergedParams);
                        handler.handle(exchange);
                    }

                    return;
                }
            }

            // No route matched — 404
            throw new NotFoundException("No route matched: " + method + " " + path);
        };
    }

    /**
     * A {@link Request} wrapper that overrides the path for nested router delegation.
     */
    private static class PrefixStrippedRequest
        implements Request {

        private final Request delegate;
        private final String strippedPath;

        PrefixStrippedRequest(final Request delegate,
                              final String strippedPath) {
            this.delegate = delegate;
            this.strippedPath = strippedPath;
        }

        @Override
        public String method() {
            return delegate.method();
        }

        @Override
        public URI uri() {
            final var query = delegate.uri().getRawQuery();

            return URI.create(query == null ? strippedPath : strippedPath + "?" + query);
        }

        @Override
        public String path() {
            return strippedPath;
        }

        @Override
        public java.util.Optional<String> pathParam(final String name) {
            return delegate.pathParam(name);
        }

        @Override
        public java.util.List<String> queryParams(final String name) {
            return delegate.queryParams(name);
        }

        @Override
        public java.util.Optional<String> queryParam(final String name) {
            return delegate.queryParam(name);
        }

        @Override
        public java.util.Map<String, java.util.List<String>> headers() {
            return delegate.headers();
        }

        @Override
        public java.util.Optional<String> header(final String name) {
            return delegate.header(name);
        }

        @Override
        public List<Cookie> cookies() {
            return delegate.cookies();
        }

        @Override
        public Optional<Cookie> cookie(final String name) {
            return delegate.cookie(name);
        }

        @Override
        public java.io.InputStream bodyAsStream() {
            return delegate.bodyAsStream();
        }

        @Override
        public String bodyAsString() {
            return delegate.bodyAsString();
        }

        @Override
        public JsonValue bodyAsJson() {
            return delegate.bodyAsJson();
        }

        @Override
        public Optional<InetSocketAddress> remoteAddress() {
            return delegate.remoteAddress();
        }
    }
}
