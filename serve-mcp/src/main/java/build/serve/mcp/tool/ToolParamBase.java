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

import build.base.json.JsonNull;
import build.base.json.JsonObject;
import build.base.json.JsonValue;

import java.util.function.Function;
import java.util.function.Predicate;

sealed abstract class ToolParamBase<T, Self extends ToolParamBase<T, Self>>
    implements ToolParam<T>
    permits StringParam, NumberParam, BoolParam, ObjectParam, ArrayParam, GenericParam {

    final String name;
    final String description;
    final boolean required;
    final T defaultValue;
    final ToolParam.Extractor<T> extractor;
    final JsonObject propertySchema;

    ToolParamBase(final String name,
                  final String description,
                  final boolean required,
                  final T defaultValue,
                  final ToolParam.Extractor<T> extractor,
                  final JsonObject propertySchema) {
        this.name = name;
        this.description = description;
        this.required = required;
        this.defaultValue = defaultValue;
        this.extractor = extractor;
        this.propertySchema = propertySchema;
    }

    protected abstract Self copy(boolean required, T defaultValue, ToolParam.Extractor<T> extractor, JsonObject propertySchema);

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean isRequired() {
        return required;
    }

    @Override
    public JsonObject propertySchema() {
        return propertySchema;
    }

    @Override
    public T extract(final JsonValue args) {
        final var obj = args.asObject();
        if (!obj.has(name) || obj.get(name) instanceof JsonNull) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument '" + name + "'.");
            }
            return defaultValue;
        }
        try {
            return extractor.extract(obj.get(name));
        } catch (final IllegalArgumentException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Invalid value for '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public Self optional() {
        return copy(false, null, extractor, propertySchema);
    }

    @Override
    public Self optional(final T defaultValue) {
        return copy(false, defaultValue, extractor, propertySchema);
    }

    @Override
    public Self refine(final Predicate<T> predicate, final String message) {
        final var outer = extractor;
        return copy(required, defaultValue,
            val -> {
                final T value = outer.extract(val);
                if (!predicate.test(value)) {
                    throw new IllegalArgumentException(message);
                }
                return value;
            },
            propertySchema);
    }

    public <U> ToolParam<U> map(final Function<T, U> transform) {
        final U mappedDefault = defaultValue != null ? transform.apply(defaultValue) : null;
        final var outer = extractor;
        return new GenericParam<>(name, description, required, mappedDefault,
            val -> transform.apply(outer.extract(val)),
            propertySchema, this::defs);
    }

    final JsonObject.Builder schemaBuilder() {
        final var b = JsonObject.builder();
        propertySchema.members().forEach((k, v) -> b.put(k, v));
        return b;
    }

    @Override
    public T extractSelf(final JsonValue value) {
        if (value instanceof JsonNull) {
            if (required) {
                throw new IllegalArgumentException("Value must not be null.");
            }
            return defaultValue;
        }
        return extractor.extract(value);
    }
}
