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

public final class BoolParam extends ToolParamBase<Boolean, BoolParam> {

    BoolParam(final String name,
              final String description,
              final boolean required,
              final Boolean defaultValue,
              final ToolParam.Extractor<Boolean> extractor,
              final JsonObject propertySchema) {
        super(name, description, required, defaultValue, extractor, propertySchema);
    }

    @Override
    protected BoolParam copy(final boolean required, final Boolean defaultValue,
                             final ToolParam.Extractor<Boolean> extractor, final JsonObject propertySchema) {
        return new BoolParam(name, description, required, defaultValue, extractor, propertySchema);
    }
}
