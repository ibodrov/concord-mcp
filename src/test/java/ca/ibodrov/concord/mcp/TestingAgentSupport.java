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

import com.walmartlabs.concord.it.testingserver.TestingConcordServer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class TestingAgentSupport {

    private TestingAgentSupport() {}

    static Map<String, String> agentConfig() {
        var runnerV2Path = runnerV2Path();
        return Map.of(
                "runnerV2.path", runnerV2Path,
                "runtimes.concord-v2.path", runnerV2Path);
    }

    private static String runnerV2Path() {
        var classPath = System.getProperty("java.class.path", "");
        for (var entry : classPath.split(File.pathSeparator)) {
            if (entry.contains("concord-runner-v2")
                    && entry.endsWith("jar-with-dependencies.jar")
                    && Files.exists(Path.of(entry))) {
                return entry;
            }
        }

        var version = TestingConcordServer.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "2.38.0";
        }

        var path = Path.of(
                System.getProperty("user.home"),
                ".m2",
                "repository",
                "com",
                "walmartlabs",
                "concord",
                "runtime",
                "v2",
                "concord-runner-v2",
                version,
                "concord-runner-v2-" + version + "-jar-with-dependencies.jar");
        if (!Files.exists(path)) {
            throw new IllegalStateException("Concord runner v2 JAR not found: " + path);
        }
        return path.toString();
    }
}
