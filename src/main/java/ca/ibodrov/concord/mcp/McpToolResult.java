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

final class McpToolResult {

    private final boolean error;
    private final Object structuredContent;

    private McpToolResult(boolean error, Object structuredContent) {
        this.error = error;
        this.structuredContent = structuredContent;
    }

    static McpToolResult ok(Object structuredContent) {
        return new McpToolResult(false, structuredContent);
    }

    static McpToolResult error(String message) {
        return new McpToolResult(true, new ErrorResult(false, message));
    }

    ToolCallResult toResponse(ObjectMapper objectMapper) {
        return new ToolCallResult(
                List.of(new ContentBlock("text", toJson(objectMapper, structuredContent))), structuredContent, error);
    }

    private static String toJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error while serializing MCP tool result", e);
        }
    }

    record ToolCallResult(List<ContentBlock> content, Object structuredContent, boolean isError) {}

    record ContentBlock(String type, String text) {}

    record ErrorResult(boolean ok, String error) {}
}
