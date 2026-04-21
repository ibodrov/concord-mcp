package ca.ibodrov.concord.mcp;

/*-
 * ~~~~~~
 * Concord MCP Server Plugin
 * ------
 * Copyright (C) 2026 Ivan Bodrov <ibodrov@gmail.com>
 * ------
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
 * ======
 */

import java.util.Map;
import javax.servlet.http.HttpServletRequest;

record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Handler handler,
        StreamingHandler streamingHandler) {

    ToolDefinition definition() {
        return new ToolDefinition(name, description, inputSchema);
    }

    boolean streamable() {
        return streamingHandler != null;
    }

    @FunctionalInterface
    interface Handler {
        Object call(Map<String, Object> arguments, HttpServletRequest request);
    }

    @FunctionalInterface
    interface StreamingHandler {
        Object call(Map<String, Object> arguments, HttpServletRequest request, McpSseWriter writer);
    }

    record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {}
}
