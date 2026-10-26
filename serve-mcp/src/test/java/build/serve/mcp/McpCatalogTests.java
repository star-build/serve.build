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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpCatalog}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class McpCatalogTests {

    @Test
    void shouldMatchTemplateWithPlusVariableAcrossSlashes() {
        var catalog = catalogOf("file:///{+path}");

        assertThat(catalog.templateMatching("file:///docs/guide/readme.md")).isPresent();
    }

    @Test
    void shouldNotMatchUriWithParentSegment() {
        var catalog = catalogOf("file:///{+path}");

        assertThat(catalog.templateMatching("file:///docs/../../etc/passwd")).isEmpty();
        assertThat(catalog.templateMatching("file:///..")).isEmpty();
    }

    @Test
    void shouldNotMatchUriWithPercentEncodedParentSegment() {
        var catalog = catalogOf("file:///{+path}");

        assertThat(catalog.templateMatching("file:///docs/%2e%2E/secret")).isEmpty();
    }

    @Test
    void shouldNotMatchUriWithPercentEncodedSeparatorBesideParentSegment() {
        var catalog = catalogOf("file:///{+path}");

        assertThat(catalog.templateMatching("file:///docs%2f..%2fsecret")).isEmpty();
        assertThat(catalog.templateMatching("file:///docs%2F%2e%2e%2Fsecret")).isEmpty();
        assertThat(catalog.templateMatching("file:///docs%5c..%5csecret")).isEmpty();
    }

    @Test
    void shouldNotMatchUriWithBackslashParentSegment() {
        var catalog = catalogOf("file:///{+path}");

        assertThat(catalog.templateMatching("file:///docs\\..\\secret")).isEmpty();
    }

    @Test
    void shouldMatchUriWhoseSegmentOnlyContainsDots() {
        var catalog = catalogOf("file:///{+path}");

        assertThat(catalog.templateMatching("file:///docs/notes..txt")).isPresent();
    }

    private static McpCatalog catalogOf(final String uriTemplate) {
        return McpCatalog.of(List.of(), List.of(), List.of(new StubTemplate(uriTemplate)), List.of());
    }

    private record StubTemplate(String uriTemplate) implements McpResourceTemplate {

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Optional<String> description() {
            return Optional.empty();
        }

        @Override
        public Optional<String> mimeType() {
            return Optional.empty();
        }

        @Override
        public McpResourceContent read(final String uri) {
            throw new UnsupportedOperationException();
        }
    }
}
