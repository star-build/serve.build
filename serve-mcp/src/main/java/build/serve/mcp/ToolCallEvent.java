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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An event published on each MCP tool invocation — once before the call (STARTED) and once
 * after it completes (SUCCEEDED or FAILED).
 *
 * <p>The {@code arguments} and {@code result} are the raw payloads and can hold secrets or file contents.
 * {@link #toString()} omits them; a subscriber that writes them anywhere must redact them first.
 *
 * <p>Subscribers attached via {@link build.base.flow.Publisher#subscribe} on
 * {@link McpServer#toolCallEvents()} receive two events per tool call. Both events carry the
 * same {@link #invocationId()}, allowing subscribers to correlate start and completion even
 * when concurrent calls share the same tool, session, or arguments.
 *
 * @param invocationId monotonically increasing counter — unique per call within a server instance
 * @param phase        lifecycle phase of this event
 * @param timestamp    the instant this event was created
 * @param sessionId    the MCP session ID that originated this call; {@code "local"} for sessionless calls
 * @param toolName     the name of the invoked tool
 * @param arguments    the raw JSON arguments passed to the tool
 * @param result       the tool result on success; empty otherwise
 * @param duration     wall-clock duration of the call; empty on STARTED events
 * @param error        the thrown exception on failure; empty otherwise
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public record ToolCallEvent(long invocationId,
                            Phase phase,
                            Instant timestamp,
                            String sessionId,
                            String toolName,
                            JsonValue arguments,
                            Optional<McpToolResult> result,
                            Optional<Duration> duration,
                            Optional<Throwable> error) {

    /**
     * Describes the call without its {@link #arguments()} or {@link #result()}, which can hold secrets or
     * file contents. A subscriber that logs the event therefore does not write them out by accident.
     *
     * @return the event's identity, phase, tool and outcome
     */
    @Override
    public String toString() {
        return "ToolCallEvent[invocationId=" + invocationId
            + ", phase=" + phase
            + ", timestamp=" + timestamp
            + ", sessionId=" + sessionId
            + ", toolName=" + toolName
            + ", duration=" + duration
            + ", error=" + error.map(Throwable::toString).orElse("none") + "]";
    }

    /**
     * Lifecycle phase of a {@link ToolCallEvent}.
     */
    public enum Phase {
        /**
         * Published immediately before the tool handler is invoked.
         */
        STARTED,
        /**
         * Published after the tool handler returns successfully.
         */
        SUCCEEDED,
        /**
         * Published after the tool handler throws an exception.
         */
        FAILED
    }

    /**
     * Creates a STARTED event published before the tool is invoked.
     *
     * @param invocationId the invocation ID shared with the completion event
     * @param sessionId    the MCP session ID; {@code "local"} for sessionless calls
     * @param toolName     the tool name
     * @param arguments    the arguments passed to the tool
     * @return the event
     */
    public static ToolCallEvent started(final long invocationId,
                                        final String sessionId,
                                        final String toolName,
                                        final JsonValue arguments) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        return new ToolCallEvent(
            invocationId,
            Phase.STARTED,
            Instant.now(),
            sessionId,
            toolName,
            arguments,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Creates a SUCCEEDED event published after the tool returns successfully.
     *
     * @param invocationId the invocation ID matching the corresponding STARTED event
     * @param sessionId    the MCP session ID; {@code "local"} for sessionless calls
     * @param toolName     the tool name
     * @param arguments    the arguments passed to the tool
     * @param result       the successful result
     * @param duration     elapsed time from call start to finish
     * @return the event
     */
    public static ToolCallEvent succeeded(final long invocationId,
                                          final String sessionId,
                                          final String toolName,
                                          final JsonValue arguments,
                                          final McpToolResult result,
                                          final Duration duration) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        return new ToolCallEvent(
            invocationId,
            Phase.SUCCEEDED,
            Instant.now(),
            sessionId,
            toolName,
            arguments,
            Optional.of(result),
            Optional.of(duration),
            Optional.empty()
        );
    }

    /**
     * Creates a FAILED event published after the tool throws an exception.
     *
     * @param invocationId the invocation ID matching the corresponding STARTED event
     * @param sessionId    the MCP session ID; {@code "local"} for sessionless calls
     * @param toolName     the tool name
     * @param arguments    the arguments passed to the tool
     * @param error        the exception thrown by the tool
     * @param duration     elapsed time from call start to finish
     * @return the event
     */
    public static ToolCallEvent failed(final long invocationId,
                                       final String sessionId,
                                       final String toolName,
                                       final JsonValue arguments,
                                       final Throwable error,
                                       final Duration duration) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        return new ToolCallEvent(
            invocationId,
            Phase.FAILED,
            Instant.now(),
            sessionId,
            toolName,
            arguments,
            Optional.empty(),
            Optional.of(duration),
            Optional.of(error)
        );
    }
}
