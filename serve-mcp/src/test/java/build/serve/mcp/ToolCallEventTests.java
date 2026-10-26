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

import build.base.json.JsonString;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ToolCallEvent}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class ToolCallEventTests {

    @Test
    void shouldNotIncludeArgumentsOrResultInToString() {
        var event = ToolCallEvent.succeeded(
            7L, "session-1", "my_tool", JsonString.of("hunter2"),
            McpToolResult.text("file-contents"), Duration.ofMillis(5));

        assertThat(event.toString())
            .contains("my_tool")
            .contains("session-1")
            .contains("SUCCEEDED")
            .doesNotContain("hunter2")
            .doesNotContain("file-contents");
    }

    @Test
    void shouldIncludeErrorInToStringOnFailure() {
        var event = ToolCallEvent.failed(
            8L, "local", "my_tool", JsonString.of("hunter2"),
            new IllegalStateException("boom"), Duration.ofMillis(5));

        assertThat(event.toString()).contains("FAILED").contains("boom").doesNotContain("hunter2");
    }
}
