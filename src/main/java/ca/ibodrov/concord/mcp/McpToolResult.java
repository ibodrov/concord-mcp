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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

final class McpToolResult {

    private final boolean error;
    private final Map<String, Object> structuredContent;

    private McpToolResult(boolean error, Map<String, Object> structuredContent) {
        this.error = error;
        this.structuredContent = structuredContent;
    }

    static McpToolResult ok(Map<String, Object> structuredContent) {
        return new McpToolResult(false, structuredContent);
    }

    static McpToolResult error(String message) {
        return new McpToolResult(true, McpResource.orderedMap("ok", false, "error", message));
    }

    Map<String, Object> toResponse(ObjectMapper objectMapper) {
        return McpResource.orderedMap(
                "content",
                List.of(McpResource.orderedMap("type", "text", "text", toJson(objectMapper, structuredContent))),
                "structuredContent",
                structuredContent,
                "isError",
                error);
    }

    private static String toJson(ObjectMapper objectMapper, Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error while serializing MCP tool result", e);
        }
    }
}
