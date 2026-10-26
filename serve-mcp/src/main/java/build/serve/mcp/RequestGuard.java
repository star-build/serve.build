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

import build.base.telemetry.TelemetryRecorder;
import build.serve.foundation.Exchange;
import build.serve.foundation.context.RequestContext;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides whether an HTTP request may reach the MCP endpoint at all, from its {@code Origin} and
 * {@code Host} headers and its peer address. Every check fails closed.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class RequestGuard {

    private final TelemetryRecorder recorder;
    private final Set<String> allowedOrigins;
    private final Set<String> allowedHosts;
    private final boolean allowUnauthenticatedRemote;
    private final AtomicBoolean hostRejectionWarned = new AtomicBoolean();

    RequestGuard(final TelemetryRecorder recorder,
                 final Set<String> allowedOrigins,
                 final Set<String> allowedHosts,
                 final boolean allowUnauthenticatedRemote) {
        this.recorder = recorder;
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.allowUnauthenticatedRemote = allowUnauthenticatedRemote;
    }

    /**
     * Checks the request, and if it is refused, sends the refusal response.
     *
     * @return {@code true} if the request may proceed, {@code false} if it has been answered
     */
    boolean admit(final Exchange exchange) {
        if (!isOriginAllowed(exchange)) {
            exchange.response().status(403).send("Forbidden");
            return false;
        }
        if (!isHostAllowed(exchange)) {
            warnHostRejected(exchange);
            exchange.response().status(403).send("Forbidden");
            return false;
        }
        if (!isRemoteAccessAllowed(exchange)) {
            exchange.response().status(401).send("Authentication required");
            return false;
        }
        return true;
    }

    /**
     * Any request carrying an {@code Origin} header (i.e. from a browser) must match an
     * allowed origin, even if none have been registered. This is the primary DNS-rebinding
     * defense — a malicious webpage's {@code fetch()} always carries an {@code Origin} header.
     * Requests without an {@code Origin} header (server-to-server clients, the Anthropic API's
     * remote MCP client, {@code curl}) are not browser-originated and are always allowed through
     * this check.
     */
    private boolean isOriginAllowed(final Exchange exchange) {
        final var origin = exchange.request().header("Origin");
        return origin.isEmpty() || allowedOrigins.contains(origin.get());
    }

    /**
     * Unless explicit hosts have been registered, only requests whose {@code Host} header names a
     * loopback address are accepted. This stops the DNS-rebinding class of attack where an
     * attacker-controlled domain resolves to {@code 127.0.0.1} — the request's {@code Origin} can
     * be spoofed to look same-origin, but the {@code Host} header still names the rebound domain.
     */
    private boolean isHostAllowed(final Exchange exchange) {
        final var host = exchange.request().header("Host");
        if (host.isEmpty()) {
            return false;
        }
        final var hostname = hostOnly(host.get());
        if (!allowedHosts.isEmpty()) {
            return allowedHosts.contains(hostname);
        }
        return isLoopbackHost(hostname);
    }

    /**
     * Logs once, the first time a request is refused by the default loopback-only Host check, so
     * an operator who deployed under a public hostname without calling
     * {@link McpServer.Builder#allowHost} can see why every request gets 403. The server does not
     * know its bind address, so this fires on the first refused request rather than at startup.
     */
    private void warnHostRejected(final Exchange exchange) {
        if (allowedHosts.isEmpty() && hostRejectionWarned.compareAndSet(false, true)) {
            recorder.warn("Refusing requests with Host '%s': no hosts are registered, so only loopback hosts"
                    + " are accepted. Call allowHost(...) on the builder when serving under another hostname.",
                McpJson.sanitize(exchange.request().header("Host").orElse("")));
        }
    }

    /**
     * A peer that is not on loopback must have been authenticated by a layer in front of this
     * handler unless {@link McpServer.Builder#allowUnauthenticatedRemote()} was called. A
     * transport that cannot report a peer address is not restricted.
     */
    private boolean isRemoteAccessAllowed(final Exchange exchange) {
        if (allowUnauthenticatedRemote || RequestContext.PRINCIPAL.isBound()) {
            return true;
        }
        return exchange.request().remoteAddress()
            .map(address -> address.getAddress() != null && address.getAddress().isLoopbackAddress())
            .orElse(true);
    }

    private static String hostOnly(final String hostHeader) {
        if (hostHeader.startsWith("[")) {
            final var end = hostHeader.indexOf(']');
            return end >= 0 ? hostHeader.substring(0, end + 1) : hostHeader;
        }
        final var colon = hostHeader.indexOf(':');
        return colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
    }

    private static boolean isLoopbackHost(final String hostname) {
        return "localhost".equalsIgnoreCase(hostname)
            || "127.0.0.1".equals(hostname)
            || "[::1]".equals(hostname)
            || "::1".equals(hostname);
    }
}
