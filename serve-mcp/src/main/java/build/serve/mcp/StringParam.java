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

import build.base.json.JsonArray;
import build.base.json.JsonObject;

import java.util.List;

public final class StringParam extends ToolParamBase<String, StringParam> {

    StringParam(final String name,
                final String description,
                final boolean required,
                final String defaultValue,
                final ToolParam.Extractor<String> extractor,
                final JsonObject propertySchema) {
        super(name, description, required, defaultValue, extractor, propertySchema);
    }

    @Override
    protected StringParam copy(final boolean required, final String defaultValue,
                               final ToolParam.Extractor<String> extractor, final JsonObject propertySchema) {
        return new StringParam(name, description, required, defaultValue, extractor, propertySchema);
    }

    public StringParam values(final List<String> values) {
        final var enumArray = JsonArray.builder();
        values.forEach(enumArray::add);
        final var schema = schemaBuilder().put("enum", enumArray.build()).build();
        final var outer = extractor;
        return copy(required, defaultValue,
            val -> {
                final var s = outer.extract(val);
                if (!values.contains(s)) {
                    throw new IllegalArgumentException(
                        "'" + name + "' must be one of " + values + " but was '" + s + "'.");
                }
                return s;
            },
            schema);
    }

    public StringParam minLength(final int min) {
        final var schema = schemaBuilder().put("minLength", min).build();
        final var outer = extractor;
        return copy(required, defaultValue,
            val -> {
                final var s = outer.extract(val);
                if (s.codePointCount(0, s.length()) < min) {
                    throw new IllegalArgumentException("'" + name + "' must be at least " + min + " character(s).");
                }
                return s;
            },
            schema);
    }

    public StringParam maxLength(final int max) {
        final var schema = schemaBuilder().put("maxLength", max).build();
        final var outer = extractor;
        return copy(required, defaultValue,
            val -> {
                final var s = outer.extract(val);
                if (s.codePointCount(0, s.length()) > max) {
                    throw new IllegalArgumentException("'" + name + "' must be at most " + max + " character(s).");
                }
                return s;
            },
            schema);
    }
}
