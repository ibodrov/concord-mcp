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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class McpSseWriter {

    static final String MEDIA_TYPE = "text/event-stream";

    private final OutputStream out;
    private final ObjectMapper objectMapper;

    McpSseWriter(OutputStream out, ObjectMapper objectMapper) {
        this.out = out;
        this.objectMapper = objectMapper;
    }

    void sendLogMessage(Object data) {
        send(new JsonRpcNotification(
                "2.0", "notifications/message", new LogMessageParams("info", "concord.process.log", data)));
    }

    void sendFinalResponse(Object id, Object result) {
        send(new JsonRpcResponse("2.0", id, result));
    }

    private void send(Object value) {
        try {
            out.write("event: message\n".getBytes(StandardCharsets.UTF_8));
            out.write("data: ".getBytes(StandardCharsets.UTF_8));
            objectMapper.writeValue(out, value);
            out.write("\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Error while writing SSE response", e);
        }
    }

    private record JsonRpcNotification(String jsonrpc, String method, LogMessageParams params) {}

    private record LogMessageParams(String level, String logger, Object data) {}

    private record JsonRpcResponse(String jsonrpc, Object id, Object result) {}
}
