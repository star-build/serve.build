package build.serve.websocket;

import build.serve.foundation.option.RequestTimeout;
import build.serve.foundation.routing.RouterBuilder;
import build.serve.testing.TestServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketTests {

    @Test
    void echoTextMessage() {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> ws.onText(msg -> {
                try {
                    ws.sendText("Echo: " + msg);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }))).build());
             var ws = server.connectWebSocket("/ws")) {
            ws.sendText("hello");
            assertThat(ws.nextText()).isEqualTo("Echo: hello");
        }
    }

    @Test
    void binaryMessage() {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> ws.onBinary(data -> {
                try {
                    ws.sendBinary(data);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }))).build());
             var ws = server.connectWebSocket("/ws")) {
            ws.sendBinary(new byte[]{1, 2, 3});
            assertThat(ws.nextBinary()).isEqualTo(new byte[]{1, 2, 3});
        }
    }

    @Test
    void pingPong() {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> {
            }))
            .build());
             var ws = server.connectWebSocket("/ws")) {
            var pong = ws.sendPingAndAwaitPong("ping".getBytes());
            assertThat(new String(pong)).isEqualTo("ping");
        }
    }

    @Test
    void cleanClose() throws Exception {
        var serverClosed = new CountDownLatch(1);
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> ws.onClose(serverClosed::countDown)))
            .build());
             var ws = server.connectWebSocket("/ws")) {
            ws.close();
            assertThat(serverClosed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void multipleMessages() throws Exception {
        var messages = new CopyOnWriteArrayList<String>();
        var allReceived = new CountDownLatch(3);

        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> ws.onText(msg -> {
                try {
                    ws.sendText("Got: " + msg);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }))).build());
             var ws = server.connectWebSocket("/ws")) {
            ws.sendText("one");
            messages.add(ws.nextText());
            allReceived.countDown();

            ws.sendText("two");
            messages.add(ws.nextText());
            allReceived.countDown();

            ws.sendText("three");
            messages.add(ws.nextText());
            allReceived.countDown();

            assertThat(allReceived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(messages).containsExactly("Got: one", "Got: two", "Got: three");
        }
    }

    @Test
    void shouldRejectUpgradeFromDisallowedOrigin() throws Exception {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> {
                },
                Set.of("https://allowed.example.com")))
            .build())) {
            // HttpURLConnection silently drops Upgrade/Connection headers; use a raw socket
            var statusLine = sendRawUpgradeRequest(server.port(), "https://evil.example.com");
            assertThat(statusLine).contains("403");
        }
    }

    @Test
    void shouldAllowUpgradeFromAllowedOrigin() {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> {
                },
                Set.of("https://allowed.example.com")))
            .build());
             var ws = server.connectWebSocket("/ws",
                 Map.of("Origin", "https://allowed.example.com"))) {
            ws.sendText("hi");
        }
    }

    @Test
    void shouldRejectUpgradeWithOriginByDefault() throws Exception {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> {
            }))
            .build())) {
            var statusLine = sendRawUpgradeRequest(server.port(), "https://evil.example.com");
            assertThat(statusLine).contains("403");
        }
    }

    @Test
    void shouldAllowUpgradeWithNoOriginByDefault() {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> {
            }))
            .build());
             var ws = server.connectWebSocket("/ws")) {
            ws.sendText("hi");
        }
    }

    /**
     * A WebSocket is long-lived by design, so the transport's request timeout must stop applying once the
     * upgrade completes; otherwise the connection is interrupted when the timeout elapses.
     */
    @Test
    void shouldKeepConnectionOpenPastRequestTimeout() throws Exception {
        try (var server = TestServer.of(RouterBuilder.create()
            .route("/ws", WebSocketUpgrade.upgrade(ws -> ws.onText(msg -> {
                try {
                    ws.sendText("Echo: " + msg);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }))).build(), RequestTimeout.of(Duration.ofMillis(300)));
             var ws = server.connectWebSocket("/ws")) {
            Thread.sleep(1_000);
            ws.sendText("still here");
            assertThat(ws.nextText()).isEqualTo("Echo: still here");
        }
    }

    private String sendRawUpgradeRequest(final int port, final String origin) throws Exception {
        try (var socket = new Socket("127.0.0.1", port);
             var out = new PrintWriter(socket.getOutputStream(), true);
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            final var key = Base64.getEncoder().encodeToString(new byte[16]);
            out.print("GET /ws HTTP/1.1\r\n");
            out.print("Host: 127.0.0.1:" + port + "\r\n");
            out.print("Upgrade: websocket\r\n");
            out.print("Connection: Upgrade\r\n");
            out.print("Sec-WebSocket-Key: " + key + "\r\n");
            out.print("Sec-WebSocket-Version: 13\r\n");
            out.print("Origin: " + origin + "\r\n");
            out.print("\r\n");
            out.flush();
            return in.readLine();
        }
    }
}
