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

import build.base.json.JsonValue;
import build.serve.mcp.McpToolAnnotations;
import build.serve.mcp.McpToolResult;

import java.util.List;
import java.util.Optional;

record BuiltTool(String name,
                 String description,
                 List<ToolParam<?>> params,
                 List<ToolParam<?>> outputParams,
                 ToolDef.Handler handler,
                 Optional<McpToolAnnotations> annotations)
    implements ToolDef {

    @Override
    public McpToolResult handle(final JsonValue args) throws Exception {
        return handler.handle(args);
    }
}
