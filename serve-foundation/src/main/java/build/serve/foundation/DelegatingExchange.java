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
package build.serve.foundation;

import build.base.configuration.Option;

import java.util.Objects;
import java.util.Optional;

/**
 * An {@link Exchange} that substitutes its {@link Request} or its {@link Response} and delegates everything else,
 * including attributes and {@link Option}s, to a parent {@link Exchange}.
 * <p>
 * Use this, rather than a new {@link SimpleExchange}, to hand a modified view of an exchange to the next
 * handler: state established before the wrapper is created stays visible, and state written through the wrapper
 * is visible to the parent afterwards.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
public final class DelegatingExchange
    implements Exchange {

    /**
     * The parent {@link Exchange}.
     */
    private final Exchange delegate;

    /**
     * The {@link Request} exposed by this {@link Exchange}.
     */
    private final Request request;

    /**
     * The {@link Response} exposed by this {@link Exchange}.
     */
    private final Response response;

    private DelegatingExchange(final Exchange delegate,
                               final Request request,
                               final Response response) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.response = Objects.requireNonNull(response, "response must not be null");
    }

    /**
     * Creates an {@link Exchange} that exposes the given {@link Request} in place of the parent's.
     *
     * @param delegate the parent {@link Exchange}
     * @param request  the {@link Request} to expose
     * @return a new {@link Exchange}
     */
    public static Exchange withRequest(final Exchange delegate,
                                       final Request request) {
        return new DelegatingExchange(delegate, request, delegate.response());
    }

    /**
     * Creates an {@link Exchange} that exposes the given {@link Response} in place of the parent's.
     *
     * @param delegate the parent {@link Exchange}
     * @param response the {@link Response} to expose
     * @return a new {@link Exchange}
     */
    public static Exchange withResponse(final Exchange delegate,
                                        final Response response) {
        return new DelegatingExchange(delegate, delegate.request(), response);
    }

    @Override
    public Request request() {
        return request;
    }

    @Override
    public Response response() {
        return response;
    }

    @Override
    public <T extends Option> Optional<T> option(final Class<T> optionClass) {
        return delegate.option(optionClass);
    }

    @Override
    public void option(final Option option) {
        delegate.option(option);
    }

    @Override
    public <T> Optional<T> attribute(final String key,
                                     final Class<T> type) {
        return delegate.attribute(key, type);
    }

    @Override
    public void attribute(final String key,
                          final Object value) {
        delegate.attribute(key, value);
    }
}
