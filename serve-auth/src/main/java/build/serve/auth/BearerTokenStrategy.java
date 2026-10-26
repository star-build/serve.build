/*-
 * #%L
 * Serve Auth
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
package build.serve.auth;

import build.serve.foundation.Request;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link AuthStrategy} that validates {@code Authorization: Bearer <token>} credentials.
 * <pre>{@code
 * BearerTokenStrategy.of(token ->
 *     Credentials.equal(token, secret) ? Optional.of(SimplePrincipal.of("reed")) : Optional.empty())
 * }</pre>
 * <p>
 * Use {@link Credentials#equal(String, String)} rather than {@link String#equals(Object)} when
 * comparing against a secret — {@code equals} short-circuits on the first mismatched character,
 * which leaks timing information an attacker can use to recover the secret byte-by-byte.
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public final class BearerTokenStrategy implements AuthStrategy {

    private static final String PREFIX = "Bearer ";

    private final Function<String, Optional<Principal>> validator;

    private BearerTokenStrategy(final Function<String, Optional<Principal>> validator) {
        this.validator = validator;
    }

    /**
     * Creates a {@link BearerTokenStrategy} with the given token validator.
     *
     * @param validator a function that maps a raw token to a {@link Principal}, or empty if invalid
     * @return a new {@link BearerTokenStrategy}
     */
    public static BearerTokenStrategy of(final Function<String, Optional<Principal>> validator) {
        return new BearerTokenStrategy(Objects.requireNonNull(validator, "validator"));
    }

    @Override
    public Optional<Principal> authenticate(final Request request) {
        return request.header("Authorization")
            .filter(h -> h.regionMatches(true, 0, PREFIX, 0, PREFIX.length()))
            .map(h -> h.substring(PREFIX.length()))
            .flatMap(validator);
    }

    @Override
    public String challenge() {
        return "Bearer";
    }
}
