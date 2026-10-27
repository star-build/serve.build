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

import build.base.json.Json;
import build.base.json.JsonArray;
import build.base.json.JsonObject;
import build.base.json.JsonValue;
import build.base.telemetry.TelemetryRecorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * The MCP stdio transport: newline-delimited JSON-RPC in, newline-delimited JSON out. Requests
 * are dispatched concurrently and every write to the output goes through one writer thread.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class StdioTransport {

    private record Line(String text, boolean oversized) {
    }

    private final TelemetryRecorder recorder;
    private final SessionRegistry sessions;
    private final RpcDispatcher dispatcher;
    private final Semaphore inFlightRequests;
    private final int maxLineLength;

    StdioTransport(final TelemetryRecorder recorder,
                   final SessionRegistry sessions,
                   final RpcDispatcher dispatcher,
                   final Semaphore inFlightRequests,
                   final int maxLineLength) {
        this.recorder = recorder;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.inFlightRequests = inFlightRequests;
        this.maxLineLength = maxLineLength;
    }

    /**
     * Serves requests from {@code in} until it reaches EOF, writing responses and notifications
     * to {@code out}.
     */
    void run(final InputStream in, final OutputStream out) {
        final var output = new LinkedBlockingQueue<Optional<String>>();
        sessions.attachStdio(output);
        final var writer = startWriter(out, output);
        final var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            // closing the executor waits for every in-flight dispatch, on EOF or on error
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Optional<Line> next;
                while ((next = readLine(reader)).isPresent()) {
                    if (next.get().oversized()) {
                        recorder.warn("Discarding stdio line longer than %d characters", maxLineLength);
                        continue;
                    }
                    final var line = next.get().text();
                    if (line.isBlank()) {
                        continue;
                    }
                    final JsonValue parsed;
                    try {
                        parsed = Json.parse(line);
                    } catch (final RuntimeException e) {
                        recorder.warn(e, "Rejecting malformed stdio line");
                        output.offer(Optional.of(McpJson.parseError().toJsonString()));
                        continue;
                    }
                    if (!(parsed instanceof JsonArray) && !(parsed instanceof JsonObject)) {
                        recorder.warn("Rejecting stdio line that is neither a request nor a batch");
                        output.offer(Optional.of(McpJson.invalidRequest().toJsonString()));
                        continue;
                    }
                    final var permits = parsed instanceof JsonArray batch ? dispatcher.permitsFor(batch) : 1;
                    // waits for free slots, so a flood of lines backs up in the pipe rather than in threads;
                    // only this thread acquires, so a batch waiting for its permits cannot deadlock
                    inFlightRequests.acquireUninterruptibly(permits);
                    executor.execute(() -> serve(parsed, output, permits));
                }
            }
            recorder.info("stdio session ended (clean EOF)");
        } catch (final IOException e) {
            recorder.warn(e, "stdio session ended unexpectedly");
        } finally {
            sessions.detachStdio();
            output.offer(Optional.empty()); // poison pill — stops the writer thread
            try {
                writer.join(5_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void serve(final JsonValue parsed, final BlockingQueue<Optional<String>> output, final int permits) {
        try {
            if (parsed instanceof JsonArray batch) {
                dispatcher.dispatchBatch(batch, SessionRegistry.STDIO_SESSION_ID)
                    .ifPresent(response -> output.offer(Optional.of(response.toJsonString())));
            } else {
                dispatcher.dispatch(parsed.asObject(), SessionRegistry.STDIO_SESSION_ID)
                    .ifPresent(response -> output.offer(Optional.of(response.toJsonString())));
            }
        } catch (final RuntimeException e) {
            recorder.warn(e, "Failed to dispatch stdio request");
            answerWithInternalError(parsed, output);
        } catch (final Error e) {
            // there is no connection to abort on stdio, so answer the request
            // rather than leave the client waiting, then let the Error propagate
            answerWithInternalError(parsed, output);
            throw e;
        } finally {
            inFlightRequests.release(permits);
        }
    }

    /**
     * Answers the request rather than leave the client waiting.
     */
    private static void answerWithInternalError(final JsonValue parsed, final BlockingQueue<Optional<String>> output) {
        if (parsed instanceof JsonObject request) {
            RpcDispatcher.internalError(request)
                .ifPresent(response -> output.offer(Optional.of(response.toJsonString())));
        }
    }

    /**
     * Starts the single thread that owns all writes to {@code out}, so that
     * {@code PipedInputStream}'s writer-thread tracking stays pointed at one live thread.
     */
    private Thread startWriter(final OutputStream out, final BlockingQueue<Optional<String>> output) {
        return Thread.ofVirtual().name("mcp-stdio-writer").start(() -> {
            final var printWriter = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
            try {
                boolean failureReported = false;
                Optional<String> item;
                while ((item = output.take()).isPresent()) {
                    printWriter.println(item.get());
                    // PrintWriter swallows IOExceptions, so a closed output only shows up here
                    if (printWriter.checkError() && !failureReported) {
                        failureReported = true;
                        recorder.warn("stdio output failed; responses are being dropped");
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Reads one line without buffering more than {@code maxLineLength} characters. A longer
     * line is consumed to its end and reported as oversized, with no text.
     *
     * @return the line, or empty at end of input
     */
    private Optional<Line> readLine(final BufferedReader reader) throws IOException {
        final var line = new StringBuilder();
        boolean oversized = false;
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                return Optional.of(new Line(oversized ? "" : line.toString(), oversized));
            }
            if (c == '\r') {
                // a raw CR cannot occur inside a JSON string, so this only drops CRLF terminators
                continue;
            }
            if (!oversized) {
                if (line.length() >= maxLineLength) {
                    oversized = true;
                    line.setLength(0);
                } else {
                    line.append((char) c);
                }
            }
        }
        if (oversized || !line.isEmpty()) {
            return Optional.of(new Line(oversized ? "" : line.toString(), oversized));
        }
        return Optional.empty();
    }
}
