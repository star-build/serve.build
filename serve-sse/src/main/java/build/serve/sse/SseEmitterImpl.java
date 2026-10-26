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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal implementation of {@link SseEmitter} that writes SSE-formatted events
 * to an {@link OutputStream}.
 *
 * @author reed.vonredwitz
 * @since Mar-2026
 */
final class SseEmitterImpl implements SseEmitter {

    /**
     * The maximum number of events buffered for a client that is not reading.
     */
    static final int QUEUE_CAPACITY = 256;

    /**
     * How long a send waits for room in a full queue before concluding the client is not reading.
     */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How long {@link #close()} waits for buffered events to drain before abandoning them.
     */
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private static final byte[] CLOSE = new byte[0];

    private final OutputStream out;
    private final Duration sendTimeout;
    private final Duration drainTimeout;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writer;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean open = true;
    private final CountDownLatch closeLatch = new CountDownLatch(1);

    /**
     * Creates a new emitter writing to the given output stream.
     * <p>
     * Events are queued and written by a dedicated thread. A sender that finds the queue full waits
     * up to {@link #SEND_TIMEOUT} for the writer to make room, so a briefly slow client is tolerated
     * and a client that stops reading costs the sender a bounded wait, after which the emitter is closed.
     *
     * @param out the output stream to write events to
     */
    SseEmitterImpl(final OutputStream out) {
        this(out, SEND_TIMEOUT, DRAIN_TIMEOUT);
    }

    /**
     * Creates a new emitter with explicit bounds on how long sends and {@link #close()} may wait.
     *
     * @param out          the output stream to write events to
     * @param sendTimeout  how long a send waits for room in a full queue
     * @param drainTimeout how long {@link #close()} waits for buffered events to reach the client
     */
    SseEmitterImpl(final OutputStream out, final Duration sendTimeout, final Duration drainTimeout) {
        this.out = out;
        this.sendTimeout = sendTimeout;
        this.drainTimeout = drainTimeout;
        this.writer = Thread.ofVirtual().name("sse-writer").start(this::drain);
    }

    @Override
    public void send(final String data) throws IOException {
        send(SseEvent.of(data));
    }

    @Override
    public void send(final SseEvent event) throws IOException {
        if (!open) {
            throw new IOException("Emitter is closed");
        }
        final var bytes = event.serialize().getBytes(StandardCharsets.UTF_8);
        try {
            if (!queue.offer(bytes, sendTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                abort();
                throw new IOException("Client is not reading; emitter closed");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while sending");
        }
        // close() and a dying writer clear `open` before they stop consuming, so an event that was queued
        // behind the close marker, or after the writer gave up, is seen here rather than silently lost
        if (!open) {
            throw new IOException("Emitter is closed");
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void awaitClose() throws InterruptedException {
        closeLatch.await();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        open = false;
        if (closeLatch.getCount() == 0) {
            return;
        }
        final var deadline = System.nanoTime() + drainTimeout.toNanos();
        try {
            // wait for room like a sender does: a full queue behind a live reader is still draining
            if (!queue.offer(CLOSE, drainTimeout.toNanos(), TimeUnit.NANOSECONDS)
                || !closeLatch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                abort();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            abort();
        }
    }

    private void drain() {
        try {
            while (true) {
                final var bytes = queue.take();
                if (bytes == CLOSE) {
                    break;
                }
                out.write(bytes);
                out.flush();
            }
        } catch (final IOException ignored) {
            // the client went away; remaining events are dropped
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            open = false;
            closeQuietly();
            closeLatch.countDown();
        }
    }

    /**
     * Abandons buffered events and tears the stream down without waiting for the writer.
     * <p>
     * The stream is closed on its own thread: closing a chunked response writes the terminating chunk,
     * which waits on the stalled socket until the interrupt frees the writer.
     */
    private void abort() {
        open = false;
        closing.set(true);
        queue.clear();
        writer.interrupt();
        Thread.startVirtualThread(this::closeQuietly);
        closeLatch.countDown();
    }

    private void closeQuietly() {
        try {
            out.close();
        } catch (final IOException ignored) {
            // Best effort cleanup
        }
    }
}
