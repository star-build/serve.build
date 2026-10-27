/*-
 * #%L
 * Serve Application
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
package build.serve.application;

import build.base.network.option.Port;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.serve.foundation.option.ListenAddress;
import build.serve.foundation.option.TlsConfig;
import build.serve.foundation.routing.Router;
import build.serve.foundation.routing.RouterBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the warning a {@link ServerApplication} records when it listens beyond the loopback interface.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class ListenAddressWarningTests {

    /**
     * A wildcard address exposes the server to other hosts, so starting on it must be recorded as a warning.
     */
    @Test
    void shouldWarnWhenListeningBeyondLoopback() throws IOException {
        assertThat(startAndCaptureLog(ListenAddress.of("0.0.0.0")))
            .contains("reachable from other hosts");
    }

    /**
     * The default loopback address is the safe choice, so starting on it must not warn.
     */
    @Test
    void shouldNotWarnWhenListeningOnLoopback() throws IOException {
        assertThat(startAndCaptureLog(ListenAddress.DEFAULT))
            .doesNotContain("reachable from other hosts");
    }

    /**
     * The HTTPS start path records the same warning as the plain HTTP one.
     */
    @Test
    void shouldWarnWhenListeningBeyondLoopbackOverHttps() throws Exception {
        final var tls = TlsConfig.of(SSLContext.getDefault());

        assertThat(startAndCaptureLog(ListenAddress.of("0.0.0.0"), tls))
            .contains("reachable from other hosts");
    }

    private String startAndCaptureLog(final ListenAddress listenAddress) throws IOException {
        return startAndCaptureLog(listenAddress, null);
    }

    private String startAndCaptureLog(final ListenAddress listenAddress, final TlsConfig tls) throws IOException {
        final var output = new ByteArrayOutputStream();
        final var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        final var server = new ServerApplication.Implementation() {
            @Override
            protected Router configure() {
                return RouterBuilder.create().build();
            }
        };
        server.recorder(PrintStreamTelemetryRecorder.of(URI.create("serve://application-test"), stream, stream));

        try {
            if (tls == null) {
                server.start(listenAddress, Port.of(0));
            } else {
                server.start(listenAddress, Port.of(0), tls);
            }
        } finally {
            server.close();
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
