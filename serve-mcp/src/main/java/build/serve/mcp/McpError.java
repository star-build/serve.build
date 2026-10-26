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

/**
 * A failure that a request handler reports to the client as a JSON-RPC error response.
 *
 * <p>The message is sent to the client verbatim, so it must never carry text from an exception
 * thrown by user code.
 *
 * @author reed.vonredwitz
 * @since Oct-2026
 */
final class McpError extends RuntimeException {

    static final int RESOURCE_NOT_FOUND = -32002;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private final int code;

    McpError(final int code, final String message) {
        super(message, null, false, false);
        this.code = code;
    }

    int code() {
        return code;
    }
}
