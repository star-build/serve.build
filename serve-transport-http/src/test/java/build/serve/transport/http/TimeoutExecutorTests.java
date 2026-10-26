/*-
 * #%L
 * Serve Transport HTTP
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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TimeoutExecutor}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class TimeoutExecutorTests {

    @Test
    void shouldInterruptTaskThatRunsPastTimeout() throws Exception {
        try (var delegate = Executors.newVirtualThreadPerTaskExecutor();
             var executor = new TimeoutExecutor(delegate, Duration.ofMillis(50))) {
            var interrupted = new AtomicBoolean();
            var done = new CountDownLatch(1);

            executor.execute(() -> {
                try {
                    Thread.sleep(5_000);
                } catch (final InterruptedException e) {
                    interrupted.set(true);
                }
                done.countDown();
            });

            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
        }
    }

    @Test
    void shouldNotInterruptTaskAfterItCancelsItsTimeout() throws Exception {
        try (var delegate = Executors.newVirtualThreadPerTaskExecutor();
             var executor = new TimeoutExecutor(delegate, Duration.ofMillis(50))) {
            var interrupted = new AtomicBoolean();
            var done = new CountDownLatch(1);

            executor.execute(() -> {
                TimeoutExecutor.cancelCurrent().run();
                try {
                    Thread.sleep(300);
                } catch (final InterruptedException e) {
                    interrupted.set(true);
                }
                done.countDown();
            });

            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isFalse();
        }
    }

    @Test
    void shouldReturnNoOpCancelOutsideTimedTask() {
        TimeoutExecutor.cancelCurrent().run();
    }
}
