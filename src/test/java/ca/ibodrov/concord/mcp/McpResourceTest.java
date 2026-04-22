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

import java.lang.reflect.Proxy;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

class McpResourceTest {

    @Test
    void allowsOriginUsingForwardedHttpsDefaults() {
        var resource = new McpResource(null);
        var request = request(
                Map.of(
                        "Origin",
                        "https://concord.example.com",
                        HttpHeaders.HOST,
                        "concord.example.com",
                        "X-Forwarded-Proto",
                        "https"),
                "http",
                8080);

        var response = resource.post(
                Map.of("jsonrpc", "2.0", "id", "init", "method", "initialize", "params", Map.of()), request);

        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsMismatchedOrigin() {
        var resource = new McpResource(null);
        var request = request(
                Map.of(
                        "Origin",
                        "https://evil.example.com",
                        HttpHeaders.HOST,
                        "concord.example.com",
                        "X-Forwarded-Proto",
                        "https"),
                "http",
                8080);

        var response = resource.post(
                Map.of("jsonrpc", "2.0", "id", "init", "method", "initialize", "params", Map.of()), request);

        assertEquals(403, response.getStatus());
    }

    private static HttpServletRequest request(Map<String, String> headers, String scheme, int serverPort) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                McpResourceTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (_proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get((String) args[0]);
                    case "getScheme" -> scheme;
                    case "getServerPort" -> serverPort;
                    default -> null;
                });
    }
}
