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

import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LiveReloadTests {

    @Test
    void shouldExposeDefaultPath() {
        assertThat(LiveReload.PATH).isEqualTo("/__dev/reload");
    }

    @Test
    void shouldEmitReloadClientScriptTargetingDefaultPath() {
        final var tag = LiveReload.scriptTag();
        assertThat(tag)
            .startsWith("<script>")
            .endsWith("</script>")
            .contains("/__dev/reload")
            .contains("location.reload()")
            .contains("WebSocket");
    }

    @Test
    void shouldAllowCustomScriptPath() {
        final var tag = LiveReload.scriptTag("/custom/reload");
        assertThat(tag).contains("/custom/reload").doesNotContain("/__dev/reload");
    }

    @Test
    void shouldReconnectInClientScript() {
        assertThat(LiveReload.scriptTag()).contains("setTimeout(connect,");
    }

    @Test
    void shouldTolerateMissingDirectory() {
        try (var reload = LiveReload.watching(Path.of("/does/not/exist/abc123"))) {
            assertThat(reload).isNotNull();
        }
    }

    @Test
    void shouldWarnAboutMissingDirectoryWhosePathContainsPercent() {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var recorder = PrintStreamTelemetryRecorder.of(URI.create("serve://devtools-test"), stream, stream);

        try (var reload = LiveReload.watching(recorder, Path.of("/does/not/exist/100%d"))) {
            assertThat(reload).isNotNull();
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("/does/not/exist/100%d");
    }

    @Test
    void shouldExposeHandler() {
        try (var reload = LiveReload.watching(Path.of("."))) {
            assertThat(reload.handler()).isNotNull();
        }
    }

    @Test
    void shouldBeNoopBroadcastWhenNoClients() {
        try (var reload = LiveReload.watching(Path.of("."))) {
            // Should not throw
            reload.broadcast();
        }
    }
}
