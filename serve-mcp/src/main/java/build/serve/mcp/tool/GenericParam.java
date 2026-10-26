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

import java.util.Map;
import java.util.function.Supplier;

final class GenericParam<T> extends ToolParamBase<T, GenericParam<T>> {

    private final Supplier<Map<String, JsonObject>> defs;

    GenericParam(final String name,
                 final String description,
                 final boolean required,
                 final T defaultValue,
                 final ToolParam.Extractor<T> extractor,
                 final JsonObject propertySchema) {
        this(name, description, required, defaultValue, extractor, propertySchema, Map::of);
    }

    /**
     * @param defs evaluated lazily at call time (not at construction), so a {@code defs} supplier
     *             derived from a {@link ToolParam#lazy} field does not have its supplier resolved
     *             prematurely — same reasoning as {@link ObjectParam}/{@link ArrayParam}.
     */
    GenericParam(final String name,
                 final String description,
                 final boolean required,
                 final T defaultValue,
                 final ToolParam.Extractor<T> extractor,
                 final JsonObject propertySchema,
                 final Supplier<Map<String, JsonObject>> defs) {
        super(name, description, required, defaultValue, extractor, propertySchema);
        this.defs = defs;
    }

    @Override
    protected GenericParam<T> copy(final boolean required, final T defaultValue,
                                   final ToolParam.Extractor<T> extractor, final JsonObject propertySchema) {
        return new GenericParam<>(name, description, required, defaultValue, extractor, propertySchema, defs);
    }

    @Override
    public Map<String, JsonObject> defs() {
        return defs.get();
    }
}
