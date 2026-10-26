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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ObjectParam extends ToolParamBase<JsonObject, ObjectParam> {

    private final List<ToolParam<?>> fields;

    ObjectParam(final String name,
                final String description,
                final boolean required,
                final JsonObject defaultValue,
                final ToolParam.Extractor<JsonObject> extractor,
                final JsonObject propertySchema,
                final List<ToolParam<?>> fields) {
        super(name, description, required, defaultValue, extractor, propertySchema);
        this.fields = fields;
    }

    @Override
    protected ObjectParam copy(final boolean required, final JsonObject defaultValue,
                               final ToolParam.Extractor<JsonObject> extractor, final JsonObject propertySchema) {
        return new ObjectParam(name, description, required, defaultValue, extractor, propertySchema, fields);
    }

    /**
     * Aggregates {@code defs()} from every field, evaluated lazily at call time (not at construction)
     * so that fields backed by {@link ToolParam#lazy} do not have their supplier resolved prematurely.
     */
    @Override
    public Map<String, JsonObject> defs() {
        final var merged = new LinkedHashMap<String, JsonObject>();
        for (final ToolParam<?> field : fields) {
            Defs.mergeInto(merged, field.defs());
        }
        return merged;
    }
}
