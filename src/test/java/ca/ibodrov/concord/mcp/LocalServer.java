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

import com.typesafe.config.Config;
import com.walmartlabs.concord.it.testingserver.TestingConcordServer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.testcontainers.containers.PostgreSQLContainer;

public class LocalServer {

    private static final String ADMIN_TOKEN = randomToken();
    private static final int SERVER_PORT = Integer.getInteger("concord.mcp.serverPort", 8080);

    public static void main(String[] args) throws Exception {
        var db = new PostgreSQLContainer<>("postgres:15-alpine");
        db.start();

        try (db;
                var server = new TestingConcordServer(db, SERVER_PORT, createConfig(), createExtraModules())) {
            server.start();

            System.out.printf(
                    """
                    ==============================================================

                      UI: http://localhost:%d
                      DB:
                        JDBC URL: %s
                        username: %s
                        password: %s
                      API:
                        admin key: %s

                      curl -i -H 'Authorization: %s' http://localhost:%d/api/v1/mcp/hello
                      curl -i -H 'Authorization: %s' \\
                        -H 'Content-Type: application/json' \\
                        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \\
                        http://localhost:%d/api/v1/mcp

                    ==============================================================
                    %n""",
                    SERVER_PORT,
                    db.getJdbcUrl(),
                    db.getUsername(),
                    db.getPassword(),
                    ADMIN_TOKEN,
                    ADMIN_TOKEN,
                    SERVER_PORT,
                    ADMIN_TOKEN,
                    SERVER_PORT);

            Thread.sleep(Long.MAX_VALUE);
        }
    }

    private static Map<String, Object> createConfig() {
        return Map.of("db.changeLogParameters.defaultAdminToken", ADMIN_TOKEN);
    }

    private static List<Function<Config, com.google.inject.Module>> createExtraModules() {
        return List.of(_cfg -> new PluginModule());
    }

    private static String randomToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
