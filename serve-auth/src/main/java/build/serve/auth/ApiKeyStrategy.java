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

import build.base.telemetry.TelemetryRecorder;
import build.base.telemetry.foundation.PrintStreamTelemetryRecorder;
import build.serve.foundation.Request;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link AuthStrategy} that validates an API key extracted from a request header or query parameter.
 * <pre>{@code
 * ApiKeyStrategy.fromHeader("X-Api-Key", key -> lookup(key))
 * ApiKeyStrategy.fromQueryParam("api_key", key -> lookup(key))
 * }</pre>
 *
 * @author reed.vonredwitz
 * @since Apr-2026
 */
public final class ApiKeyStrategy implements AuthStrategy {

    private static final TelemetryRecorder DEFAULT_RECORDER =
        PrintStreamTelemetryRecorder.of(URI.create("serve://auth"), System.out, System.err);

    private final Function<Request, Optional<String>> extractor;
    private final Function<String, Optional<Principal>> validator;

    private ApiKeyStrategy(final Function<Request, Optional<String>> extractor,
                           final Function<String, Optional<Principal>> validator) {
        this.extractor = extractor;
        this.validator = validator;
    }

    /**
     * Creates an {@link ApiKeyStrategy} that reads the key from a request header.
     *
     * @param headerName the header name (e.g., {@code "X-Api-Key"})
     * @param validator  a function mapping the key to a {@link Principal}, or empty if invalid
     * @return a new {@link ApiKeyStrategy}
     */
    public static ApiKeyStrategy fromHeader(final String headerName,
                                            final Function<String, Optional<Principal>> validator) {
        Objects.requireNonNull(headerName, "headerName");
        Objects.requireNonNull(validator, "validator");
        return new ApiKeyStrategy(req -> req.header(headerName), validator);
    }

    /**
     * Creates an {@link ApiKeyStrategy} that reads the key from a query parameter.
     * <p>
     * <strong>Prefer {@link #fromHeader(String, Function)} where possible.</strong> A key carried
     * in the URL ends up in server access logs, proxy logs, and browser history, and is exposed
     * by anything that logs or shares the request URL — none of which apply to a header value.
     * Use this only when the caller genuinely cannot set a header (e.g. a webhook provider that
     * only supports query-string authentication).
     *
     * @param paramName the query parameter name (e.g., {@code "api_key"})
     * @param validator a function mapping the key to a {@link Principal}, or empty if invalid
     * @return a new {@link ApiKeyStrategy}
     */
    public static ApiKeyStrategy fromQueryParam(final String paramName,
                                                final Function<String, Optional<Principal>> validator) {
        return fromQueryParam(paramName, validator, DEFAULT_RECORDER);
    }

    /**
     * Creates an {@link ApiKeyStrategy} that reads the key from a query parameter.
     * <p>
     * <strong>Prefer {@link #fromHeader(String, Function)} where possible.</strong> A key carried
     * in the URL ends up in server access logs, proxy logs, and browser history, and is exposed
     * by anything that logs or shares the request URL — none of which apply to a header value.
     * Use this only when the caller genuinely cannot set a header (e.g. a webhook provider that
     * only supports query-string authentication).
     *
     * @param paramName the query parameter name (e.g., {@code "api_key"})
     * @param validator a function mapping the key to a {@link Principal}, or empty if invalid
     * @param recorder  the {@link TelemetryRecorder} to record the query-string usage warning with
     * @return a new {@link ApiKeyStrategy}
     */
    public static ApiKeyStrategy fromQueryParam(final String paramName,
                                                final Function<String, Optional<Principal>> validator,
                                                final TelemetryRecorder recorder) {
        Objects.requireNonNull(paramName, "paramName");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(recorder, "recorder");
        recorder.warn("ApiKeyStrategy.fromQueryParam(\"%s\") carries the API key in the URL —"
            + " it will appear in access logs, proxy logs, and browser history. Prefer fromHeader(...)"
            + " unless the caller cannot set a header.", paramName);
        return new ApiKeyStrategy(req -> req.queryParam(paramName), validator);
    }

    @Override
    public Optional<Principal> authenticate(final Request request) {
        return extractor.apply(request).flatMap(validator);
    }
}
