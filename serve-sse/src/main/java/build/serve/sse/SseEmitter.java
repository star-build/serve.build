/*-
 * #%L
 * Serve SSE
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
package build.serve.sse;

import java.io.IOException;

/**
 * Emitter interface for sending Server-Sent Events to a connected client.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
public interface SseEmitter extends AutoCloseable {

    /**
     * Sends a simple text data event.
     * <p>
     * Events are queued and written asynchronously, so a failed write is reported by a later call
     * rather than by the call that queued the event, and queued events are lost when the client goes away.
     *
     * @param data the event data
     * @throws IOException if the emitter is closed, or the client is not reading and its buffer is full
     */
    void send(String data) throws IOException;

    /**
     * Sends a structured SSE event.
     *
     * @param event the event to send
     * @throws IOException if the emitter is closed, or the client is not reading and its buffer is full
     * @see #send(String)
     */
    void send(SseEvent event) throws IOException;

    /**
     * Returns whether this emitter is still open and connected.
     *
     * @return {@code true} if the emitter is open
     */
    boolean isOpen();

    /**
     * Blocks until this emitter is closed, either by {@link #close()} or a failed {@link #send}.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    void awaitClose() throws InterruptedException;

    /**
     * Closes this emitter after giving buffered events a bounded time to reach the client;
     * events still queued after that are abandoned.
     */
    @Override
    void close();
}
