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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static int serverPort;

    @BeforeAll
    static void setUp() throws Exception {
        serverPort = findFreePort();

        db = new PostgreSQLContainer<>("postgres:15-alpine");
        db.start();

        server = new TestingConcordServer(db, serverPort, createConfig(), createExtraModules());
        server.start();

        client = HttpClient.newHttpClient();
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

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
