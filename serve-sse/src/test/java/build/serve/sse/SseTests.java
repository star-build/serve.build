package build.serve.sse;

import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestServer;
import build.serve.testing.TestSseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseTests {

    @Test
    void basicTextStream() {
        try (var server = TestServer.of(RouterBuilder.create()
            .get("/events", SseUpgrade.sse(emitter -> {
                emitter.send("one");
                emitter.send("two");
                emitter.send("three");
            })).build());
             var stream = server.sse("/events")) {
            var events = stream.collectAll(Duration.ofSeconds(5));
            assertThat(events).extracting(TestSseEvent::data)
                .containsExactly("one", "two", "three");
        }
    }

    @Test
    void eventWithId() {
        try (var server = TestServer.of(RouterBuilder.create()
            .get("/events", SseUpgrade.sse(emitter ->
                emitter.send(new SseEvent("hello", "42", null, null))))
            .build());
             var stream = server.sse("/events")) {
            var events = stream.collectAll(Duration.ofSeconds(5));
            assertThat(events).hasSize(1);
            assertThat(events.get(0).data()).isEqualTo("hello");
            assertThat(events.get(0).id()).contains("42");
        }
    }

    @Test
    void eventWithType() {
        try (var server = TestServer.of(RouterBuilder.create()
            .get("/events", SseUpgrade.sse(emitter ->
                emitter.send(SseEvent.of("tick", "count=0"))))
            .build());
             var stream = server.sse("/events")) {
            var events = stream.collectAll(Duration.ofSeconds(5));
            assertThat(events).hasSize(1);
            assertThat(events.get(0).data()).isEqualTo("count=0");
            assertThat(events.get(0).event()).contains("tick");
        }
    }

    @Test
    void emitterCloseEndsStream() {
        try (var server = TestServer.of(RouterBuilder.create()
            .get("/events", SseUpgrade.sse(emitter -> {
                emitter.send("before");
                emitter.close();
            })).build());
             var stream = server.sse("/events")) {
            var events = stream.collectAll(Duration.ofSeconds(5));
            assertThat(events).extracting(TestSseEvent::data).containsExactly("before");
        }
    }

    @Test
    void connectionDropHandled() throws Exception {
        var detected = new CompletableFuture<Boolean>();
        var started = new CountDownLatch(1);

        try (var server = TestServer.of(RouterBuilder.create()
            .get("/events", SseUpgrade.sse(emitter -> {
                started.countDown();
                try {
                    for (int i = 0; i < 1000; i++) {
                        if (!emitter.isOpen()) {
                            detected.complete(true);
                            return;
                        }
                        try {
                            emitter.send("tick " + i);
                        } catch (final Exception e) {
                            detected.complete(!emitter.isOpen());
                            return;
                        }
                        Thread.sleep(10);
                    }
                    detected.complete(false);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    detected.complete(false);
                }
            })).build())) {
            var stream = server.sse("/events");
            started.await(5, TimeUnit.SECONDS);
            Thread.sleep(50);
            stream.close();
        }

        assertThat(detected.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(30)
    void shouldNotBlockSenderIndefinitelyWhenReaderStalls() throws Exception {
        var release = new CountDownLatch(1);
        var emitter = new SseEmitterImpl(stalledStream(release), Duration.ofMillis(200), Duration.ofMillis(200));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sender = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 1000 && emitter.isOpen(); i++) {
                    try {
                        emitter.send("event " + i);
                    } catch (final IOException e) {
                        return;
                    }
                }
            }, executor);

            try {
                // a reader that never drains fills the queue, which fails the sender fast and closes the emitter
                assertThatCode(() -> sender.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
                assertThat(emitter.isOpen()).isFalse();
            } finally {
                release.countDown();
                emitter.close();
            }
        }
    }

    @Test
    @Timeout(30)
    void shouldDeliverQueuedEventsBeforeCloseReturns() throws Exception {
        var sink = new ByteArrayOutputStream();
        var emitter = new SseEmitterImpl(sink);

        for (int i = 0; i < 50; i++) {
            emitter.send("event " + i);
        }
        emitter.close();

        var written = sink.toString(StandardCharsets.UTF_8);
        assertThat(written).contains("data: event 0").contains("data: event 49");
    }

    @Test
    @Timeout(30)
    void shouldAbandonQueuedEventsWhenCloseDrainTimesOut() throws Exception {
        var release = new CountDownLatch(1);
        var drainTimeout = Duration.ofMillis(500);
        var emitter = new SseEmitterImpl(stalledStream(release), Duration.ofSeconds(5), drainTimeout);
        emitter.send("stuck");

        var start = System.nanoTime();
        emitter.close();
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(emitter.isOpen()).isFalse();
        assertThat(elapsed).isGreaterThanOrEqualTo(drainTimeout.minusMillis(100));
        assertThatCode(emitter::awaitClose).doesNotThrowAnyException();
        release.countDown();
    }

    @Test
    @Timeout(30)
    void shouldAbsorbBurstLargerThanQueueFromReaderThatKeepsUp() throws Exception {
        var sink = new ByteArrayOutputStream();
        var emitter = new SseEmitterImpl(slowStream(sink), Duration.ofSeconds(10), Duration.ofSeconds(10));
        var count = SseEmitterImpl.QUEUE_CAPACITY * 4;

        for (int i = 0; i < count; i++) {
            emitter.send("event " + i);
        }
        emitter.close();

        var written = sink.toString(StandardCharsets.UTF_8);
        assertThat(written).contains("data: event 0").contains("data: event " + (count - 1));
    }

    @Test
    @Timeout(30)
    void shouldDrainFullQueueOnCloseWhenReaderKeepsUp() throws Exception {
        var sink = new ByteArrayOutputStream();
        var emitter = new SseEmitterImpl(slowStream(sink), Duration.ofSeconds(10), Duration.ofSeconds(10));
        var count = SseEmitterImpl.QUEUE_CAPACITY * 2;

        for (int i = 0; i < count; i++) {
            emitter.send("event " + i);
        }
        emitter.close();

        assertThat(sink.toString(StandardCharsets.UTF_8)).contains("data: event " + (count - 1));
    }

    @Test
    @Timeout(30)
    void shouldRejectSendsAfterClose() throws Exception {
        var emitter = new SseEmitterImpl(new ByteArrayOutputStream());
        emitter.close();

        assertThatThrownBy(() -> emitter.send("late")).isInstanceOf(IOException.class);
    }

    /**
     * An output stream that makes progress, but slower than a producer sending in a tight loop.
     */
    private static OutputStream slowStream(final OutputStream delegate) {
        return new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                delegate.write(b);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException {
                try {
                    Thread.sleep(1);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                delegate.write(b, off, len);
            }
        };
    }

    /**
     * An output stream whose writes block until released, like a socket to a client that stopped reading.
     */
    private static OutputStream stalledStream(final CountDownLatch release) {
        return new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException {
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
    }

    @Test
    void shouldRejectNullData() {
        assertThatNullPointerException()
            .isThrownBy(() -> new SseEvent(null, null, null, null))
            .withMessage("data must not be null");
    }
}
