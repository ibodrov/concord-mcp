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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.common.ObjectMapperProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolResultTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProvider().get();

    @Test
    void testStructuredContent() {
        var result = McpToolResult.ok(Map.of("ok", true)).toResponse(OBJECT_MAPPER);

        assertEquals(false, result.isError());
        assertEquals(Map.of("ok", true), result.structuredContent());
    }

    @Test
    void testRecordStructuredContent() {
        var payload = new TestResult(true, "value", null);

        var result = McpToolResult.ok(payload).toResponse(OBJECT_MAPPER);
        var text = result.content().get(0).text();

        assertEquals("{\"ok\":true,\"value\":\"value\"}", text);
        assertEquals(payload, result.structuredContent());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TestResult(boolean ok, String value, String omitted) {}
}
