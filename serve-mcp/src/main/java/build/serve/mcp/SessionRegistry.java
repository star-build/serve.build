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

import build.base.json.JsonObject;
import build.base.telemetry.TelemetryRecorder;
import build.serve.foundation.context.RequestContext;
import build.serve.sse.SseEmitter;
import build.serve.sse.SseEvent;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The live sessions of a server: HTTP sessions issued at {@code initialize}, plus the single
 * stdio session, and the delivery of server-initiated notifications to them.
 *
 * <p>A background thread evicts sessions that have been idle for longer than the idle timeout.
 * {@link #close()} stops it and ends every open session's SSE stream.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class SessionRegistry implements AutoCloseable {

    /**
     * The id of the stdio session, which does not appear in the HTTP session table.
     */
    static final String STDIO_SESSION_ID = "local";

    private static final Duration MIN_REAPER_INTERVAL = Duration.ofMillis(10);
    private static final Duration MAX_REAPER_INTERVAL = Duration.ofMinutes(5);

    /**
     * The mutable state of one session.
     */
    static final class SessionState {

        /**
         * The principal that initialized the session, or {@code null} if it was unauthenticated.
         */
        final Object principal;
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        final AtomicReference<SseEmitter> emitter = new AtomicReference<>();
        volatile Instant lastAccessed = Instant.now();
        volatile McpLogLevel logLevel;

        SessionState(final Object principal) {
            this.principal = principal;
        }
    }

    private final TelemetryRecorder recorder;
    private final int maxSessions;
    private final int maxSessionsPerPrincipal;
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Object reservationLock = new Object();
    private final Thread reaper;
    private volatile SessionState stdioSession;
    private volatile BlockingQueue<Optional<String>> stdioOutput;

    SessionRegistry(final TelemetryRecorder recorder,
                    final int maxSessions,
                    final int maxSessionsPerPrincipal,
                    final Duration idleTimeout) {
        this.recorder = recorder;
        this.maxSessions = maxSessions;
        this.maxSessionsPerPrincipal = maxSessionsPerPrincipal;
        this.reaper = startReaper(idleTimeout);
    }

    private Thread startReaper(final Duration idleTimeout) {
        final long intervalMs = Math.clamp(idleTimeout.toMillis() / 2,
            MIN_REAPER_INTERVAL.toMillis(), MAX_REAPER_INTERVAL.toMillis());
        return Thread.ofVirtual().name("mcp-session-reaper").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (final InterruptedException e) {
                    break;
                }
                final var cutoff = Instant.now().minus(idleTimeout);
                for (final var entry : sessions.entrySet()) {
                    if (entry.getValue().lastAccessed.isBefore(cutoff)
                        && sessions.remove(entry.getKey(), entry.getValue())) {
                        // an uncaught failure here would end the reaper and leak every later session
                        try {
                            closeEmitter(entry.getValue());
                        } catch (final RuntimeException e) {
                            recorder.warn(e, "Failed to close the stream of idle session %s", entry.getKey());
                        }
                    }
                }
            }
        });
    }

    /**
     * Stops the reaper and ends every open session, closing their SSE streams. Safe to call more
     * than once.
     */
    @Override
    public void close() {
        reaper.interrupt();
        for (final var entry : sessions.entrySet()) {
            if (sessions.remove(entry.getKey(), entry.getValue())) {
                closeEmitter(entry.getValue());
            }
        }
    }

    /**
     * Closes the session's SSE stream, if one is open, so its parked virtual thread and socket
     * are released when the session is evicted or deleted.
     */
    private static void closeEmitter(final SessionState state) {
        final var emitter = state.emitter.getAndSet(null);
        if (emitter != null) {
            emitter.close();
        }
    }

    // --- Lookup ---

    /**
     * Returns the state of the stdio session or HTTP session with the given id, whoever owns it,
     * or {@code null} if there is none.
     */
    SessionState find(final String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return STDIO_SESSION_ID.equals(sessionId) ? stdioSession : sessions.get(sessionId);
    }

    /**
     * Looks up an HTTP session, treating a session owned by a different principal as absent so
     * that a session id alone is never enough to use someone else's session. Principals are
     * compared with {@link Object#equals}, so an authentication layer must bind principals that
     * are equal across requests from the same caller (e.g. records).
     * <p>
     * When no principal is bound, as on a server using {@link McpServer.Builder#allowUnauthenticatedRemote()},
     * every session is owned by {@code null} and the unguessable session id is the only protection.
     */
    SessionState owned(final String sessionId) {
        final var state = sessions.get(sessionId);
        return state != null && Objects.equals(state.principal, currentPrincipal()) ? state : null;
    }

    private static Object currentPrincipal() {
        return RequestContext.PRINCIPAL.isBound() ? RequestContext.PRINCIPAL.get() : null;
    }

    // --- HTTP session lifecycle ---

    /**
     * Registers a new session for the current principal if there is capacity, both overall and
     * for that principal, checking and inserting under one lock so that concurrent
     * {@code initialize} calls cannot overshoot {@code maxSessions}. Removals need no lock, as
     * they only free capacity.
     *
     * @return the new session id, or {@code null} if the server is at capacity
     */
    String reserve() {
        synchronized (reservationLock) {
            if (sessions.size() >= maxSessions) {
                return null;
            }
            final var principal = currentPrincipal();
            if (principal != null && sessions.values().stream()
                .filter(state -> principal.equals(state.principal))
                .count() >= maxSessionsPerPrincipal) {
                return null;
            }
            final var id = UUID.randomUUID().toString();
            sessions.put(id, new SessionState(principal));
            return id;
        }
    }

    /**
     * Gives back a session that was reserved but never handed to a client.
     */
    void release(final String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /**
     * Ends the session, if it exists and is owned by the current principal, closing its stream.
     */
    void terminate(final String sessionId) {
        final var state = owned(sessionId);
        if (state != null && sessions.remove(sessionId, state)) {
            closeEmitter(state);
        }
    }

    /**
     * Makes {@code emitter} the session's notification stream, replacing any earlier one, and
     * blocks until the stream closes.
     */
    void serveStream(final String sessionId, final SessionState state, final SseEmitter emitter) {
        state.lastAccessed = Instant.now();
        final var previous = state.emitter.getAndSet(emitter);
        if (previous != null) {
            previous.close();
        }
        if (sessions.get(sessionId) != state) {
            // the session was evicted or deleted while this stream was connecting
            closeEmitter(state);
        }
        try {
            emitter.awaitClose();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            state.emitter.compareAndSet(emitter, null);
        }
    }

    // --- Stdio session ---

    /**
     * Opens the stdio session, whose notifications are queued on {@code output}.
     */
    void attachStdio(final BlockingQueue<Optional<String>> output) {
        stdioSession = new SessionState(null);
        stdioOutput = output;
    }

    void detachStdio() {
        stdioOutput = null;
        stdioSession = null;
    }

    private void queueForStdio(final String json) {
        final var output = stdioOutput;
        if (output != null) {
            output.offer(Optional.of(json));
        }
    }

    // --- Notifications ---

    /**
     * Sends a notification to one session. Does nothing if the session is gone or has no open
     * stream.
     */
    void send(final String sessionId, final JsonObject notification) {
        final var json = notification.toJsonString();
        if (STDIO_SESSION_ID.equals(sessionId)) {
            queueForStdio(json);
            return;
        }
        final var state = sessions.get(sessionId);
        if (state != null) {
            push(state, SseEvent.of("message", json), McpJson.string(notification, "method"));
        }
    }

    /**
     * Sends a notification to every session, stdio included, that {@code recipients} accepts.
     */
    void broadcast(final Predicate<SessionState> recipients, final JsonObject notification) {
        final var json = notification.toJsonString();
        final var event = SseEvent.of("message", json);
        final var method = McpJson.string(notification, "method");
        for (final var state : sessions.values()) {
            if (recipients.test(state)) {
                push(state, event, method);
            }
        }
        final var stdio = stdioSession;
        if (stdio != null && recipients.test(stdio)) {
            queueForStdio(json);
        }
    }

    private void push(final SessionState state, final SseEvent event, final String method) {
        final var emitter = state.emitter.get();
        if (emitter != null && emitter.isOpen()) {
            try {
                emitter.send(event);
            } catch (final IOException e) {
                recorder.warn(e, "Failed to push %s notification", method);
            }
        }
    }
}
