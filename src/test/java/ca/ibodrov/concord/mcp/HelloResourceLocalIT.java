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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.it.testingserver.TestingConcordServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

public class HelloResourceLocalIT {

    private static final String TEST_ADMIN_TOKEN = "YWRtaW50b2s=";

    private static PostgreSQLContainer<?> db;
    private static TestingConcordServer server;
    private static HttpClient client;
    private static ObjectMapper objectMapper;
    private static int serverPort;

    @BeforeAll
    static void setUp() throws Exception {
        serverPort = findFreePort();

        db = new PostgreSQLContainer<>("postgres:15-alpine");
        db.start();

        server = new TestingConcordServer(db, serverPort, createConfig(), createExtraModules());
        server.start();

        client = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testMcpEndpointCreatesConcordEntities() throws Exception {
        var initialize = postMcp(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2025-06-18",
                        "clientInfo",
                        Map.of("name", "concord-mcp-it", "version", "0.0.1")));
        var initializeResult = object(initialize.get("result"));
        assertEquals("2025-06-18", initializeResult.get("protocolVersion"));

        var tools = postMcp("tools/list", Map.of());
        assertTrue(
                object(tools.get("result")).get("tools").toString().contains("concord_create_org"), tools.toString());

        var org = callTool("concord_create_org", Map.of("name", "mcp-it-org"));
        assertEquals("organization", org.get("entity"));
        assertEquals("CREATED", org.get("result"));

        var project = callTool(
                "concord_create_project",
                Map.of("orgName", "mcp-it-org", "name", "mcp-it-project", "description", "MCP integration test"));
        assertEquals("project", project.get("entity"));
        assertEquals("CREATED", project.get("result"));

        var repository = callTool(
                "concord_create_repository",
                Map.of(
                        "orgName",
                        "mcp-it-org",
                        "projectName",
                        "mcp-it-project",
                        "name",
                        "mcp-it-repo",
                        "url",
                        "https://example.com/concord-mcp-it.git",
                        "branch",
                        "main",
                        "disabled",
                        true));
        assertEquals("repository", repository.get("entity"));
        assertEquals("CREATED", repository.get("result"));
        assertEquals(true, repository.get("disabled"));

        var secret = callTool(
                "concord_create_data_secret",
                Map.of(
                        "orgName",
                        "mcp-it-org",
                        "name",
                        "mcp-it-secret",
                        "data",
                        "secret-value",
                        "projectNames",
                        List.of("mcp-it-project")));
        assertEquals("secret", secret.get("entity"));
        assertEquals("DATA", secret.get("type"));
        assertFalse(secret.toString().contains("secret-value"), secret.toString());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void testPluginResourceLoads() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getApiBaseUrl() + "/api/v1/mcp/hello"))
                .header("Authorization", TEST_ADMIN_TOKEN)
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"message\":\"" + HelloResource.MESSAGE + "\""), response.body());
    }

    private static Map<String, Object> createConfig() {
        return Map.of("db.changeLogParameters.defaultAdminToken", TEST_ADMIN_TOKEN);
    }

    private static List<Function<com.typesafe.config.Config, com.google.inject.Module>> createExtraModules() {
        return List.of(_cfg -> new PluginModule());
    }

    private static Map<String, Object> postMcp(String method, Map<String, Object> params) throws Exception {
        var payload = Map.of("jsonrpc", "2.0", "id", method, "method", method, "params", params);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getApiBaseUrl() + "/api/v1/mcp"))
                .header("Authorization", TEST_ADMIN_TOKEN)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private static Map<String, Object> callTool(String name, Map<String, Object> arguments) throws Exception {
        var response = postMcp("tools/call", Map.of("name", name, "arguments", arguments));
        assertFalse(response.containsKey("error"), response.toString());
        var result = object(response.get("result"));
        assertEquals(false, result.get("isError"), response.toString());
        return object(result.get("structuredContent"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
