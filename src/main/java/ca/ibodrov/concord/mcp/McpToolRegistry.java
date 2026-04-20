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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;

@Singleton
class McpToolRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<McpTool> tools;
    private final Map<String, McpTool> toolsByName;

    @Inject
    McpToolRegistry(ConcordCrudTools crudTools) {
        this.tools = List.of(
                tool(
                        "concord_create_org",
                        "Create or update a Concord organization.",
                        objectSchema(
                                properties(
                                        "name", string("Organization name."),
                                        "visibility", stringEnum("Organization visibility.", "PUBLIC", "PRIVATE"),
                                        "meta", object("Organization metadata."),
                                        "cfg", object("Organization configuration.")),
                                "name"),
                        crudTools::createOrg),
                tool(
                        "concord_create_project",
                        "Create or update a Concord project.",
                        objectSchema(
                                properties(
                                        "orgName", string("Organization name."),
                                        "name", string("Project name."),
                                        "description", string("Project description."),
                                        "visibility", stringEnum("Project visibility.", "PUBLIC", "PRIVATE"),
                                        "meta", object("Project metadata."),
                                        "cfg", object("Project configuration.")),
                                "orgName",
                                "name"),
                        crudTools::createProject),
                tool(
                        "concord_create_repository",
                        "Create or update a Concord project repository.",
                        objectSchema(
                                properties(
                                        "orgName", string("Organization name."),
                                        "projectName", string("Project name."),
                                        "name", string("Repository name."),
                                        "url", string("Repository URL."),
                                        "branch", string("Repository branch. Required if commitId is omitted."),
                                        "commitId", string("Pinned commit ID. Required if branch is omitted."),
                                        "path", string("Path to the Concord project files inside the repository."),
                                        "secretId", string("Repository secret UUID."),
                                        "secretName", string("Repository secret name."),
                                        "secretStoreType", string("Repository secret store type."),
                                        "disabled", bool("Create the repository without loading process definitions."),
                                        "triggersDisabled", bool("Disable repository triggers."),
                                        "meta", object("Repository metadata.")),
                                "orgName",
                                "projectName",
                                "name",
                                "url"),
                        crudTools::createRepository),
                tool(
                        "concord_create_data_secret",
                        "Create a Concord data secret. The secret value is never returned.",
                        objectSchema(
                                properties(
                                        "orgName", string("Organization name."),
                                        "name", string("Secret name."),
                                        "data", string("Secret data as UTF-8 text."),
                                        "dataBase64", string("Secret data as base64-encoded bytes."),
                                        "storePassword", string("Optional secret store password."),
                                        "visibility", stringEnum("Secret visibility.", "PUBLIC", "PRIVATE"),
                                        "storeType", string("Secret store type."),
                                        "projectIds", stringArray("Project UUIDs to link."),
                                        "projectNames", stringArray("Project names to link.")),
                                "orgName",
                                "name"),
                        crudTools::createDataSecret),
                tool(
                        "concord_create_username_password_secret",
                        "Create a Concord username/password secret. The password is never returned.",
                        objectSchema(
                                properties(
                                        "orgName", string("Organization name."),
                                        "name", string("Secret name."),
                                        "username", string("Secret username."),
                                        "password", string("Secret password."),
                                        "storePassword", string("Optional secret store password."),
                                        "visibility", stringEnum("Secret visibility.", "PUBLIC", "PRIVATE"),
                                        "storeType", string("Secret store type."),
                                        "projectIds", stringArray("Project UUIDs to link."),
                                        "projectNames", stringArray("Project names to link.")),
                                "orgName",
                                "name",
                                "username",
                                "password"),
                        crudTools::createUsernamePasswordSecret),
                tool(
                        "concord_create_key_pair_secret",
                        "Create a Concord SSH key pair secret. Private key material is never returned.",
                        objectSchema(
                                properties(
                                        "orgName", string("Organization name."),
                                        "name", string("Secret name."),
                                        "publicKey",
                                                string(
                                                        "Public key. Omit both publicKey and privateKey to generate a pair."),
                                        "privateKey",
                                                string(
                                                        "Private key. Omit both publicKey and privateKey to generate a pair."),
                                        "storePassword", string("Optional secret store password."),
                                        "visibility", stringEnum("Secret visibility.", "PUBLIC", "PRIVATE"),
                                        "storeType", string("Secret store type."),
                                        "projectIds", stringArray("Project UUIDs to link."),
                                        "projectNames", stringArray("Project names to link.")),
                                "orgName",
                                "name"),
                        crudTools::createKeyPairSecret));

        var toolsByName = new LinkedHashMap<String, McpTool>();
        for (var tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
        this.toolsByName = Map.copyOf(toolsByName);
    }

    Map<String, Object> listTools() {
        return McpResource.orderedMap(
                "tools", tools.stream().map(McpTool::definition).toList());
    }

    Map<String, Object> callTool(Map<String, Object> params) {
        var name = asString(params.get("name"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }

        var tool = toolsByName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }

        try {
            var arguments = asObject(params.get("arguments"));
            return McpToolResult.ok(tool.handler().call(arguments)).toResponse(objectMapper);
        } catch (WebApplicationException | IllegalArgumentException e) {
            return McpToolResult.error(e.getMessage()).toResponse(objectMapper);
        } catch (RuntimeException e) {
            return McpToolResult.error("Tool execution failed: " + e.getClass().getSimpleName())
                    .toResponse(objectMapper);
        }
    }

    private static McpTool tool(
            String name, String description, Map<String, Object> inputSchema, McpTool.Handler handler) {
        return new McpTool(name, description, inputSchema, handler);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        return McpResource.orderedMap(
                "type",
                "object",
                "properties",
                properties,
                "required",
                List.of(required),
                "additionalProperties",
                false);
    }

    private static Map<String, Object> properties(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (var i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> string(String description) {
        return McpResource.orderedMap("type", "string", "description", description);
    }

    private static Map<String, Object> stringEnum(String description, String... values) {
        return McpResource.orderedMap("type", "string", "description", description, "enum", List.of(values));
    }

    private static Map<String, Object> bool(String description) {
        return McpResource.orderedMap("type", "boolean", "description", description);
    }

    private static Map<String, Object> object(String description) {
        return McpResource.orderedMap("type", "object", "description", description);
    }

    private static Map<String, Object> stringArray(String description) {
        return McpResource.orderedMap("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("'arguments' must be an object");
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
