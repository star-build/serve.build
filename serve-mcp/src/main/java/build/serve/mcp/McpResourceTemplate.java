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

import java.util.List;
import java.util.Optional;

/**
 * A URI-template-based resource exposed by an MCP server.
 *
 * <p>Templates use simple RFC 6570 variable expansion: {@code {varName}} matches any
 * non-slash segment; {@code {+varName}} matches any character sequence including slashes.
 * The full resolved URI is passed to {@link #read(String)} — variable extraction is left
 * to the implementation. A URI containing a {@code ..} path segment, literal or percent-encoded,
 * matches no template.
 *
 * @author reed.vonredwitz
 * @since May-2026
 */
public interface McpResourceTemplate {

    /**
     * Returns the RFC 6570 URI template (e.g. {@code "file:///{path}"}).
     *
     * @return the URI template string
     */
    String uriTemplate();

    /**
     * Returns the human-readable name of this template.
     *
     * @return the name
     */
    String name();

    /**
     * Returns an optional description of this template.
     *
     * @return the description
     */
    Optional<String> description();

    /**
     * Returns the MIME type of resources matched by this template, if known.
     *
     * @return the MIME type
     */
    Optional<String> mimeType();

    /**
     * Reads the resource at the given resolved URI.
     *
     * @param uri the fully-resolved resource URI
     * @return the resource content
     * @throws Exception if reading fails
     */
    McpResourceContent read(String uri) throws Exception;

    /**
     * Returns completion suggestions for the named URI template variable given a partial value.
     * Defaults to an empty list; override to provide autocomplete for specific variables.
     *
     * @param argumentName the URI template variable name (e.g. {@code "table"} in {@code db://{table}/{id}})
     * @param value        the partial value typed so far
     * @return matching suggestions (may be empty)
     */
    default List<String> complete(final String argumentName, final String value) {
        return List.of();
    }
}
