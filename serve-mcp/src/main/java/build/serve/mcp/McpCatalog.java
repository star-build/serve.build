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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The tools, resources, resource templates and prompts a server offers, fixed when it is built.
 * A later registration under the same name or URI replaces an earlier one, and listings keep
 * registration order.
 *
 * @param tools     the tools, by name
 * @param resources the resources, by URI
 * @param templates the resource templates, in registration order
 * @param prompts   the prompts, by name
 * @author reed.vonredwitz
 * @since Oct-2026
 */
record McpCatalog(Map<String, McpTool> tools,
                  Map<String, McpResource> resources,
                  List<TemplateEntry> templates,
                  Map<String, McpPrompt> prompts) {

    static McpCatalog of(final List<McpTool> tools,
                         final List<McpResource> resources,
                         final List<McpResourceTemplate> templates,
                         final List<McpPrompt> prompts) {
        return new McpCatalog(
            indexed(tools, McpTool::name),
            indexed(resources, McpResource::uri),
            templates.stream()
                .map(template -> new TemplateEntry(template, uriTemplateToPattern(template.uriTemplate())))
                .toList(),
            indexed(prompts, McpPrompt::name));
    }

    /**
     * Finds the template whose URI pattern matches a concrete resource URI.
     */
    Optional<McpResourceTemplate> templateMatching(final String uri) {
        return findTemplate(entry -> entry.pattern().matcher(uri).matches());
    }

    /**
     * Finds the template declared with exactly the given URI template string.
     */
    Optional<McpResourceTemplate> templateDeclaring(final String uriTemplate) {
        return findTemplate(entry -> entry.template().uriTemplate().equals(uriTemplate));
    }

    private Optional<McpResourceTemplate> findTemplate(final Predicate<TemplateEntry> matches) {
        return templates.stream().filter(matches).map(TemplateEntry::template).findFirst();
    }

    private static <T> Map<String, T> indexed(final List<T> items, final Function<T, String> key) {
        final var index = new LinkedHashMap<String, T>();
        for (final var item : items) {
            index.put(key.apply(item), item);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Pattern uriTemplateToPattern(final String uriTemplate) {
        final var regex = new StringBuilder();
        final var variable = Pattern.compile("\\{\\+?([^}]+)}").matcher(uriTemplate);
        int position = 0;
        while (variable.find()) {
            regex.append(Pattern.quote(uriTemplate.substring(position, variable.start())));
            regex.append(variable.group().startsWith("{+") ? ".+" : "[^/]+");
            position = variable.end();
        }
        regex.append(Pattern.quote(uriTemplate.substring(position)));
        return Pattern.compile(regex.toString());
    }

    record TemplateEntry(McpResourceTemplate template, Pattern pattern) {
    }
}
