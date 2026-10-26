/*-
 * #%L
 * Serve DevTools
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
package build.serve.devtools;

import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.serve.foundation.Handler;
import build.serve.websocket.WebSocket;
import build.serve.websocket.WebSocketUpgrade;

import java.io.IOException;
import java.net.URI;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Live-reload for local development. Watches a file-system tree and pushes a reload
 * signal to every connected browser tab when any file changes.
 * <p>
 * Usage — one instance covers every connected tab:
 * <pre>{@code
 * var reload = LiveReload.watching(Path.of("target/classes"), Path.of("src/main/resources"));
 *
 * RouterBuilder.create()
 *     .route(LiveReload.PATH, reload.handler())
 *     ...
 * }</pre>
 * <p>
 * Inject {@link #scriptTag()} into your HTML {@code <head>} (or append it before {@code </body>})
 * so browsers connect back on page load.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public final class LiveReload implements AutoCloseable {

    /**
     * The default WebSocket endpoint path for live-reload clients.
     */
    public static final String PATH = "/__dev/reload";

    private static final TelemetryRecorder DEFAULT_RECORDER =
        PrintStreamTelemetryRecorder.of(URI.create("serve://devtools/live-reload"), System.out, System.err);

    private final CopyOnWriteArraySet<WebSocket> clients = new CopyOnWriteArraySet<>();
    private final WatchService watchService;
    private final Thread watcherThread;
    private final TelemetryRecorder recorder;
    private volatile boolean running = true;

    private LiveReload(final List<Path> roots, final TelemetryRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder must not be null");

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to create WatchService", e);
        }

        final Map<WatchKey, Path> keys = new HashMap<>();
        for (final var root : roots) {
            if (!Files.isDirectory(root)) {
                recorder.warn("LiveReload: skipping non-existent path %s", root);
                continue;
            }
            registerRecursive(root, keys);
        }

        this.watcherThread = Thread.ofVirtual()
            .name("live-reload-watcher")
            .start(() -> watchLoop(keys));
    }

    /**
     * Creates a live-reload handle that watches the given paths recursively.
     *
     * @param roots directories to watch (missing directories are skipped with a warning)
     * @return a new {@link LiveReload}
     */
    public static LiveReload watching(final Path... roots) {
        Objects.requireNonNull(roots, "roots");
        return new LiveReload(List.of(roots), DEFAULT_RECORDER);
    }

    /**
     * Creates a live-reload handle that watches the given paths recursively.
     *
     * @param recorder the {@link TelemetryRecorder} to record watch events with
     * @param roots    directories to watch (missing directories are skipped with a warning)
     * @return a new {@link LiveReload}
     */
    public static LiveReload watching(final TelemetryRecorder recorder, final Path... roots) {
        Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(roots, "roots");
        return new LiveReload(List.of(roots), recorder);
    }

    /**
     * Returns a WebSocket {@link Handler} that accepts live-reload client connections.
     *
     * @return the reload {@link Handler}
     */
    public Handler handler() {
        return WebSocketUpgrade.upgrade(ws -> {
            clients.add(ws);
            ws.onClose(() -> clients.remove(ws));
        });
    }

    /**
     * Broadcasts a reload signal to every connected client. Called automatically when a watched
     * file changes; exposed for manual triggering (e.g. after an async rebuild completes).
     */
    public void broadcast() {
        for (final var ws : clients) {
            try {
                ws.sendText("reload");
            } catch (final IOException e) {
                clients.remove(ws);
            }
        }
    }

    /**
     * The full {@code <script>} tag to inject into HTML pages. The client reconnects on drop
     * and calls {@code location.reload()} when the server sends any message.
     *
     * @return the script tag
     */
    public static String scriptTag() {
        return scriptTag(PATH);
    }

    /**
     * The full {@code <script>} tag, pointing at a custom WebSocket path.
     *
     * @param path the path the WebSocket handler is mounted at
     * @return the script tag
     */
    public static String scriptTag(final String path) {
        return "<script>(function(){"
            + "var url='ws://'+location.host+'" + path + "';"
            + "function connect(){"
            + "var ws=new WebSocket(url);"
            + "ws.onmessage=function(){location.reload();};"
            + "ws.onclose=function(){setTimeout(connect,1000);};"
            + "}connect();"
            + "})();</script>";
    }

    @Override
    public void close() {
        running = false;
        watcherThread.interrupt();
        try {
            watchService.close();
        } catch (final IOException ignored) {
            // best-effort
        }
        for (final var ws : clients) {
            try {
                ws.close();
            } catch (final Exception ignored) {
                // best-effort
            }
        }
        clients.clear();
    }

    private void registerRecursive(final Path root, final Map<WatchKey, Path> keys) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir,
                                                         final BasicFileAttributes attrs) throws IOException {
                    final var key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                    keys.put(key, dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            recorder.warn(e, "LiveReload: failed to register %s", root);
        }
    }

    private void watchLoop(final Map<WatchKey, Path> keys) {
        while (running) {
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (final InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            try {
                final var dir = keys.get(key);
                boolean anyChange = false;
                for (final var event : key.pollEvents()) {
                    // an overflow means events were lost, so a change may have been missed
                    anyChange = true;
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && dir != null) {
                        final var path = dir.resolve((Path) event.context());
                        if (Files.isDirectory(path)) {
                            registerRecursive(path, keys);
                        }
                    }
                }
                if (anyChange) {
                    broadcast();
                }
            } catch (final RuntimeException e) {
                if (!running) {
                    return;
                }
                recorder.error(e, "LiveReload: failed to process file system events");
            } finally {
                // always reset, or a failure above would silently stop events for this directory
                if (!key.reset()) {
                    keys.remove(key);
                }
            }
        }
    }

}
