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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.common.ObjectMapperProvider;
import com.walmartlabs.concord.it.testingserver.TestingConcordAgent;
import com.walmartlabs.concord.it.testingserver.TestingConcordServer;
import com.walmartlabs.concord.server.sdk.ProcessStatus;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

public class McpResourceLocalIT {

    private static final String TEST_ADMIN_TOKEN = "YWRtaW50b2s=";
    private static final EnumSet<ProcessStatus> TERMINAL_STATUSES =
            EnumSet.of(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.CANCELLED, ProcessStatus.TIMED_OUT);

    private static PostgreSQLContainer<?> db;
    private static TestingConcordServer server;
    private static TestingConcordAgent agent;
    private static HttpClient client;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() throws Exception {
        int serverPort = findFreePort();

        db = new PostgreSQLContainer<>("postgres:15-alpine");
        db.start();

        server = new TestingConcordServer(db, serverPort, createConfig(), createExtraModules());
        server.start();
        agent = new TestingConcordAgent(server, TestingAgentSupport.agentConfig(), List.of());
        agent.start();

        client = HttpClient.newHttpClient();
        objectMapper = new ObjectMapperProvider().get();
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
        assertEquals("PRIVATE", org.get("visibility"));

        var project = callTool(
                "concord_create_project",
                Map.of("orgName", "mcp-it-org", "name", "mcp-it-project", "description", "MCP integration test"));
        assertEquals("project", project.get("entity"));
        assertEquals("CREATED", project.get("result"));
        assertEquals("PRIVATE", project.get("visibility"));

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
        assertEquals("PRIVATE", secret.get("visibility"));
        assertFalse(secret.toString().contains("secret-value"), secret.toString());

        var invalidSecret = callToolError(
                "concord_create_data_secret",
                Map.of(
                        "orgName",
                        "mcp-it-org",
                        "name",
                        "mcp-it-invalid-secret",
                        "data",
                        "secret-value",
                        "dataBase64",
                        "b3RoZXI="));
        assertTrue(
                invalidSecret.get("error").toString().contains("Exactly one non-blank value"),
                invalidSecret.toString());
    }

    @Test
    void testMcpEndpointStartsProcessAndReadsLogs() throws Exception {
        var archive = zipBase64(
                Map.of(
                        "concord.yml",
                        """
                configuration:
                  runtime: concord-v2
                flows:
                  default:
                    - log: "hello ${name.first} ${name.last}"
                """));
        var requestJson = objectMapper.writeValueAsString(
                Map.of("arguments", Map.of("name", Map.of("first", "Ada", "last", "Lovelace"))));

        var started = callTool(
                "concord_start_process",
                Map.of(
                        "parts",
                        List.of(
                                Map.of(
                                        "name", "archive",
                                        "contentType", "application/zip",
                                        "base64", archive),
                                Map.of(
                                        "name", "request",
                                        "contentType", "application/json",
                                        "text", requestJson))));

        var instanceId = (String) started.get("instanceId");
        assertNotNull(instanceId);

        var process = waitForTerminalProcess(instanceId);
        assertEquals(ProcessStatus.FINISHED.name(), process.get("status"), process.toString());

        var segments = callTool(
                "concord_list_process_log_segments",
                Map.of("instanceId", instanceId, "includeSystem", true, "limit", 20));
        assertFalse(list(segments.get("segments")).isEmpty(), segments.toString());

        var rawLog = callTool(
                "concord_read_process_log",
                Map.of("instanceId", instanceId, "format", "raw", "startOffset", 0, "maxBytes", 65536));
        assertTrue(rawLog.get("text").toString().contains("hello Ada Lovelace"), rawLog.toString());

        var prefixedLog = callTool(
                "concord_read_process_log",
                Map.of("instanceId", instanceId, "format", "prefixed", "startOffset", 0, "maxBytes", 65536));
        assertTrue(prefixedLog.get("text").toString().contains("hello Ada Lovelace"), prefixedLog.toString());

        var sse = postMcpSse(
                "tools/call",
                Map.of(
                        "name",
                        "concord_stream_process_log",
                        "arguments",
                        Map.of("instanceId", instanceId, "startOffset", 0, "follow", false, "maxBytesPerPoll", 65536)));
        assertEquals(200, sse.statusCode(), sse.body());
        assertTrue(sse.body().contains("notifications/message"), sse.body());
        assertTrue(sse.body().contains("hello Ada Lovelace"), sse.body());
        assertTrue(sse.body().contains("\"result\""), sse.body());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (agent != null) {
            agent.close();
        }
        if (server != null) {
            server.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static Map<String, Object> createConfig() {
        return Map.of("db.changeLogParameters.defaultAdminToken", TEST_ADMIN_TOKEN);
    }

    private static List<Function<com.typesafe.config.Config, com.google.inject.Module>> createExtraModules() {
        return List.of(_cfg -> new PluginModule());
    }

    private static Map<String, Object> postMcp(String method, Map<String, Object> params) throws Exception {
        var response = postMcpRaw(method, params, "application/json, text/event-stream");
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private static HttpResponse<String> postMcpSse(String method, Map<String, Object> params) throws Exception {
        return postMcpRaw(method, params, "text/event-stream");
    }

    private static HttpResponse<String> postMcpRaw(String method, Map<String, Object> params, String accept)
            throws Exception {
        var payload = Map.of("jsonrpc", "2.0", "id", method, "method", method, "params", params);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(server.getApiBaseUrl() + "/api/v1/mcp"))
                .header(HttpHeaders.AUTHORIZATION, TEST_ADMIN_TOKEN)
                .header(HttpHeaders.ACCEPT, accept)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> callTool(String name, Map<String, Object> arguments) throws Exception {
        var response = postMcp("tools/call", Map.of("name", name, "arguments", arguments));
        assertFalse(response.containsKey("error"), response.toString());
        var result = object(response.get("result"));
        assertEquals(false, result.get("isError"), response.toString());
        return object(result.get("structuredContent"));
    }

    private static Map<String, Object> callToolError(String name, Map<String, Object> arguments) throws Exception {
        var response = postMcp("tools/call", Map.of("name", name, "arguments", arguments));
        assertFalse(response.containsKey("error"), response.toString());
        var result = object(response.get("result"));
        assertEquals(true, result.get("isError"), response.toString());
        return object(result.get("structuredContent"));
    }

    private static Map<String, Object> waitForTerminalProcess(String instanceId) throws Exception {
        for (var i = 0; i < 60; i++) {
            var process = callTool("concord_get_process", Map.of("instanceId", instanceId));
            var status = ProcessStatus.valueOf(process.get("status").toString());
            if (TERMINAL_STATUSES.contains(status)) {
                return process;
            }
            Thread.sleep(1000);
        }
        fail("Timed out waiting for process " + instanceId);
        return Map.of();
    }

    private static String zipBase64(Map<String, String> files) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out, UTF_8)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(UTF_8));
                zip.closeEntry();
            }
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
