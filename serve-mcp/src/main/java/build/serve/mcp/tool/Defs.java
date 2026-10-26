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

/**
 * Merge helper for {@code $defs} maps shared by every {@link ToolParam#defs()} implementation.
 *
 * <p>{@code defName} is an author-chosen string with no enforced namespacing, so two independently
 * declared {@link ToolParam#lazy} params can collide on the same name. Merging silently (last-write-wins)
 * would leave one param's {@code $ref} pointing at the wrong schema with no signal anything went wrong —
 * so a collision between two <em>different</em> schemas for the same {@code defName} is rejected outright.
 * Re-merging the same {@code defName} with an identical schema (e.g. the same {@code lazy} param reachable
 * through more than one path) is fine and a no-op.
 */
final class Defs {

    private Defs() {
    }

    static void mergeInto(final Map<String, JsonObject> target, final Map<String, JsonObject> source) {
        source.forEach((defName, schema) -> {
            final var existing = target.putIfAbsent(defName, schema);
            if (existing != null && !existing.equals(schema)) {
                throw new IllegalStateException(
                    "Conflicting $defs entries for '" + defName + "': two different schemas were "
                        + "declared with the same defName.");
            }
        });
    }
}
