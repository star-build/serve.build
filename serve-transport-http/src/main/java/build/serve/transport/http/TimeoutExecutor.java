/*-
 * #%L
 * Serve Transport (HTTP)
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
package build.serve.transport.http;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An {@link Executor} that wraps a delegate and interrupts each task's thread
 * if it runs longer than the configured timeout.
 * <p>
 * After interruption the thread's interrupted status is cleared so the virtual
 * thread pool can reuse it cleanly. Call {@link #close()} when the transport stops.
 */
final class TimeoutExecutor implements Executor, AutoCloseable {

    private final Executor delegate;
    private final long timeoutMillis;
    private final ScheduledExecutorService scheduler;

    private static final ThreadLocal<ScheduledFuture<?>> CURRENT = new ThreadLocal<>();

    /**
     * Obtains an action that cancels the timeout of the task running on the calling thread.
     *
     * @return the cancel action, or a no-op when the calling thread is not running a timed task
     */
    static Runnable cancelCurrent() {
        final var future = CURRENT.get();
        return future == null ? () -> {
        } : () -> future.cancel(false);
    }

    TimeoutExecutor(final Executor delegate, final Duration timeout) {
        this.delegate = delegate;
        this.timeoutMillis = timeout.toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("serve-timeout-scheduler").factory());
    }

    @Override
    public void execute(final Runnable command) {
        delegate.execute(() -> {
            final var thread = Thread.currentThread();
            final var future = scheduler.schedule(
                thread::interrupt, timeoutMillis, TimeUnit.MILLISECONDS);
            CURRENT.set(future);
            try {
                command.run();
            } finally {
                CURRENT.remove();
                future.cancel(false);
                Thread.interrupted(); // clear interrupt status so the thread can be reused
            }
        });
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
