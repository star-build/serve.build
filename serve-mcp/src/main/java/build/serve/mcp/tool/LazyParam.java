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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A lazy, self-referential parameter whose property schema is a {@code $ref} to a named {@code $defs} entry.
 *
 * <p>The {@code $ref} schema ({@code {"$ref":"#/$defs/<defName>"}}) is pre-computed at construction time
 * so that {@link #propertySchema()} never triggers the supplier. The supplier is resolved exactly once,
 * on the first call to {@link #extract}, {@link #extractSelf}, or {@link #defs}.
 *
 * <p>{@link #defs()} walks the resolved schema transitively, collecting {@code defs()} of every nested
 * param (including further {@code lazy} params reachable through it), so {@link ToolDef#inputSchema()}
 * ends up with a complete {@code $defs} block even for mutually recursive types. A thread-local guard
 * keyed on {@code defName} breaks cycles: once a def name is being collected, re-entering it (directly
 * self-referential, or via a longer mutual-recursion chain) short-circuits to an empty map instead of
 * recursing forever, since an ancestor frame is already contributing that entry.
 *
 * @author reed.vonredwitz
 * @since May-2026
 */
final class LazyParam<T> implements ToolParam<T> {

    private static final ThreadLocal<Set<String>> DEFS_IN_PROGRESS = ThreadLocal.withInitial(LinkedHashSet::new);

    private final String name;
    private final String description;
    private final String defName;
    private final Supplier<ToolParam<T>> supplier;
    private final boolean required;
    private final T defaultValue;
    private final JsonObject refSchema;
    private volatile ToolParam<T> resolved;

    LazyParam(final String name,
              final String description,
              final String defName,
              final Supplier<ToolParam<T>> supplier) {
        this(name, description, defName, true, null, supplier);
    }

    private LazyParam(final String name,
                      final String description,
                      final String defName,
                      final boolean required,
                      final T defaultValue,
                      final Supplier<ToolParam<T>> supplier) {
        this.name = name;
        this.description = description;
        this.defName = defName;
        this.supplier = supplier;
        this.required = required;
        this.defaultValue = defaultValue;
        this.refSchema = JsonObject.builder().put("$ref", "#/$defs/" + defName).build();
    }

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
        return refSchema;
    }

    @Override
    public Map<String, JsonObject> defs() {
        final Set<String> inProgress = DEFS_IN_PROGRESS.get();
        if (!inProgress.add(defName)) {
            return Map.of();
        }
        try {
            final var resolvedParam = resolve();
            final var merged = new LinkedHashMap<String, JsonObject>();
            merged.put(defName, resolvedParam.propertySchema());
            Defs.mergeInto(merged, resolvedParam.defs());
            return merged;
        } finally {
            inProgress.remove(defName);
        }
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
        return extractSelf(obj.get(name));
    }

    @Override
    public T extractSelf(final JsonValue value) {
        if (value instanceof JsonNull) {
            if (required) {
                throw new IllegalArgumentException("Value must not be null.");
            }
            return defaultValue;
        }
        return resolve().extractSelf(value);
    }

    @Override
    public LazyParam<T> optional() {
        return new LazyParam<>(name, description, defName, false, null, supplier);
    }

    @Override
    public LazyParam<T> optional(final T defaultValue) {
        return new LazyParam<>(name, description, defName, false, defaultValue, supplier);
    }

    @Override
    public ToolParam<T> refine(final Predicate<T> predicate, final String message) {
        return new LazyParam<>(name, description, defName, required, defaultValue,
            () -> resolve().refine(predicate, message));
    }

    private ToolParam<T> resolve() {
        if (resolved == null) {
            synchronized (this) {
                if (resolved == null) {
                    resolved = supplier.get();
                }
            }
        }
        return resolved;
    }
}
