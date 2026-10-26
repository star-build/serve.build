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
package build.serve.mcp.tool;

import build.base.json.JsonObject;

public final class NumberParam<N extends Number> extends ToolParamBase<N, NumberParam<N>> {

    NumberParam(final String name,
                final String description,
                final boolean required,
                final N defaultValue,
                final ToolParam.Extractor<N> extractor,
                final JsonObject propertySchema) {
        super(name, description, required, defaultValue, extractor, propertySchema);
    }

    @Override
    protected NumberParam<N> copy(final boolean required, final N defaultValue,
                                  final ToolParam.Extractor<N> extractor, final JsonObject propertySchema) {
        return new NumberParam<>(name, description, required, defaultValue, extractor, propertySchema);
    }

    public NumberParam<N> min(final N minimum) {
        final var schema = schemaBuilder().put("minimum", minimum).build();
        final var outer = extractor;
        return copy(required, defaultValue,
            val -> {
                final N n = outer.extract(val);
                if (n.doubleValue() < minimum.doubleValue()) {
                    throw new IllegalArgumentException("'" + name + "' must be >= " + minimum + ".");
                }
                return n;
            },
            schema);
    }

    public NumberParam<N> max(final N maximum) {
        final var schema = schemaBuilder().put("maximum", maximum).build();
        final var outer = extractor;
        return copy(required, defaultValue,
            val -> {
                final N n = outer.extract(val);
                if (n.doubleValue() > maximum.doubleValue()) {
                    throw new IllegalArgumentException("'" + name + "' must be <= " + maximum + ".");
                }
                return n;
            },
            schema);
    }
}
