/*-
 * #%L
 * Serve Foundation
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
package build.serve.foundation.option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ListenAddress}.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
class ListenAddressTests {

    @Test
    void shouldDefaultToLoopback() {
        assertThat(ListenAddress.DEFAULT.isLoopback()).isTrue();
    }

    @Test
    void shouldTreatWildcardAsNonLoopback() {
        assertThat(ListenAddress.of("0.0.0.0").isLoopback()).isFalse();
        assertThat(ListenAddress.of("::").isLoopback()).isFalse();
    }

    @Test
    void shouldRecognizeLoopbackAddresses() {
        assertThat(ListenAddress.of("127.0.0.1").isLoopback()).isTrue();
        assertThat(ListenAddress.of("localhost").isLoopback()).isTrue();
        assertThat(ListenAddress.of("::1").isLoopback()).isTrue();
    }

    @Test
    void shouldTreatPublicAddressAsNonLoopback() {
        assertThat(ListenAddress.of("203.0.113.7").isLoopback()).isFalse();
    }
}
