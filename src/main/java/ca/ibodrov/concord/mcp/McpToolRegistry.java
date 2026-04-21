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

import static com.walmartlabs.concord.sdk.MapUtils.getString;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.common.ObjectMapperProvider;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;

public class McpToolRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProvider().get();

    private final List<McpTool> tools;
    private final Map<String, McpTool> toolsByName;

    @Inject
    McpToolRegistry(ConcordCrudTools crudTools, ConcordProcessTools processTools, ConcordLogTools logTools) {
        var crudToolList = List.of(
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

        var processAndLogTools = List.of(
                tool(
                        "concord_start_process",
                        "Start a Concord process using the same multipart model as the REST API. Provide parts named archive for a ZIP workspace, request for JSON configuration/arguments, or text fields such as org, project, repo, entryPoint, out, and meta.",
                        objectSchema(
                                properties(
                                        "parts",
                                        array(
                                                "Multipart-style parts. Each part requires name and exactly one of text or base64. contentType defaults to text/plain; non-text parts become Concord payload attachments.",
                                                partSchema())),
                                "parts"),
                        processTools::startProcess),
                tool(
                        "concord_get_process",
                        "Get a compact Concord process status entry.",
                        objectSchema(properties("instanceId", string("Process instance UUID.")), "instanceId"),
                        processTools::getProcess),
                tool(
                        "concord_list_process_log_segments",
                        "List runtime-v2 log segments for a Concord process.",
                        objectSchema(
                                properties(
                                        "instanceId", string("Process instance UUID."),
                                        "includeSystem", bool("Include the system segment. Defaults to true."),
                                        "offset", integer("First segment offset. Defaults to 0."),
                                        "limit", integer("Maximum number of segments. Defaults to 100.")),
                                "instanceId"),
                        logTools::listSegments),
                tool(
                        "concord_read_process_log_segment",
                        "Read text from a single Concord runtime-v2 log segment.",
                        objectSchema(
                                properties(
                                        "instanceId", string("Process instance UUID."),
                                        "segmentId", integer("Log segment ID."),
                                        "startOffset", integer("First segment byte offset."),
                                        "endOffset", integer("Exclusive segment byte offset."),
                                        "tailBytes", integer("Read the last N bytes when startOffset is omitted."),
                                        "maxBytes", integer("Maximum bytes to return. Defaults to 8192.")),
                                "instanceId",
                                "segmentId"),
                        logTools::readSegment),
                tool(
                        "concord_read_process_log",
                        "Read the combined Concord process log. format=raw returns Concord's global log order; format=prefixed adds segment-name prefixes to chunks.",
                        objectSchema(
                                properties(
                                        "instanceId", string("Process instance UUID."),
                                        "format", stringEnum("Output format. Defaults to raw.", "raw", "prefixed"),
                                        "includeSystem",
                                                bool("Include the system segment in prefixed mode. Defaults to true."),
                                        "startOffset", integer("First global log byte offset."),
                                        "endOffset", integer("Exclusive global log byte offset."),
                                        "tailBytes", integer("Read the last N bytes when startOffset is omitted."),
                                        "maxBytes", integer("Maximum bytes to return. Defaults to 8192.")),
                                "instanceId"),
                        logTools::readLog),
                streamingTool(
                        "concord_stream_process_log",
                        "Stream Concord process log text over POST SSE when the client accepts text/event-stream. JSON clients receive a bounded final result.",
                        objectSchema(
                                properties(
                                        "instanceId", string("Process instance UUID."),
                                        "segmentId",
                                                integer(
                                                        "Optional log segment ID. Omit to stream the combined process log."),
                                        "format",
                                                stringEnum(
                                                        "Combined-log output format. Defaults to raw.",
                                                        "raw",
                                                        "prefixed"),
                                        "includeSystem",
                                                bool(
                                                        "Include the system segment in prefixed combined mode. Defaults to true."),
                                        "startOffset", integer("First byte offset. Defaults to 0."),
                                        "tailBytes",
                                                integer("Start at the current log tail when startOffset is omitted."),
                                        "follow",
                                                bool(
                                                        "Poll until the process reaches a terminal status or maxDurationMillis expires."),
                                        "pollMillis", integer("Polling interval. Defaults to 1000."),
                                        "maxDurationMillis", integer("Maximum streaming duration. Defaults to 60000."),
                                        "maxBytesPerPoll",
                                                integer("Maximum bytes read per polling iteration. Defaults to 16384."),
                                        "maxBufferedBytes",
                                                integer("Maximum bytes retained in the final JSON response.")),
                                "instanceId"),
                        (arguments, request) -> logTools.streamLog(arguments, null),
                        (arguments, request, writer) -> logTools.streamLog(arguments, writer)));

        var allTools = new java.util.ArrayList<McpTool>(crudToolList.size() + processAndLogTools.size());
        allTools.addAll(crudToolList);
        allTools.addAll(processAndLogTools);
        this.tools = List.copyOf(allTools);

        var toolsByName = new LinkedHashMap<String, McpTool>();
        for (var tool : this.tools) {
            toolsByName.put(tool.name(), tool);
        }
        this.toolsByName = Map.copyOf(toolsByName);
    }

    ToolListResult listTools() {
        return new ToolListResult(tools.stream().map(McpTool::definition).toList());
    }

    Object callTool(Map<String, Object> params, HttpServletRequest request) {
        var name = assertString(params, "name");
        var tool = toolsByName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }

        try {
            var arguments = asObject(params.get("arguments"));
            return McpToolResult.ok(tool.handler().call(arguments, request)).toResponse(OBJECT_MAPPER);
        } catch (WebApplicationException | IllegalArgumentException e) {
            return McpToolResult.error(e.getMessage()).toResponse(OBJECT_MAPPER);
        } catch (RuntimeException e) {
            return McpToolResult.error("Tool execution failed: " + e.getClass().getSimpleName())
                    .toResponse(OBJECT_MAPPER);
        }
    }

    boolean isStreamableTool(Map<String, Object> params) {
        var name = getString(params, "name");
        var tool = name != null ? toolsByName.get(name) : null;
        return tool != null && tool.streamable();
    }

    void streamTool(Map<String, Object> params, Object id, HttpServletRequest request, OutputStream out) {
        var writer = new McpSseWriter(out, OBJECT_MAPPER);
        Object result;
        try {
            var name = assertString(params, "name");
            var tool = toolsByName.get(name);
            if (tool == null) {
                throw new IllegalArgumentException("Unknown tool: " + name);
            }
            if (!tool.streamable()) {
                throw new IllegalArgumentException("Tool is not streamable: " + name);
            }

            var arguments = asObject(params.get("arguments"));
            result = McpToolResult.ok(tool.streamingHandler().call(arguments, request, writer))
                    .toResponse(OBJECT_MAPPER);
        } catch (WebApplicationException | IllegalArgumentException e) {
            result = McpToolResult.error(e.getMessage()).toResponse(OBJECT_MAPPER);
        } catch (RuntimeException e) {
            result = McpToolResult.error(
                            "Tool execution failed: " + e.getClass().getSimpleName())
                    .toResponse(OBJECT_MAPPER);
        }
        writer.sendFinalResponse(id, result);
    }

    private static McpTool tool(
            String name, String description, Map<String, Object> inputSchema, SimpleHandler handler) {
        return new McpTool(name, description, inputSchema, (arguments, request) -> handler.call(arguments), null);
    }

    private static McpTool tool(
            String name, String description, Map<String, Object> inputSchema, McpTool.Handler handler) {
        return new McpTool(name, description, inputSchema, handler, null);
    }

    private static McpTool streamingTool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            McpTool.Handler handler,
            McpTool.StreamingHandler streamingHandler) {
        return new McpTool(name, description, inputSchema, handler, streamingHandler);
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

    private static Map<String, Object> integer(String description) {
        return McpResource.orderedMap("type", "integer", "description", description);
    }

    private static Map<String, Object> object(String description) {
        return McpResource.orderedMap("type", "object", "description", description);
    }

    private static Map<String, Object> stringArray(String description) {
        return McpResource.orderedMap("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    private static Map<String, Object> array(String description, Map<String, Object> items) {
        return McpResource.orderedMap("type", "array", "description", description, "items", items);
    }

    private static Map<String, Object> partSchema() {
        return McpResource.orderedMap(
                "type",
                "object",
                "properties",
                properties(
                        "name", string("Multipart part name."),
                        "contentType", string("Part media type. Defaults to text/plain."),
                        "text", string("UTF-8 text content."),
                        "base64", string("Base64-encoded bytes.")),
                "required",
                List.of("name"),
                "additionalProperties",
                false);
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

    private static String assertString(Map<String, Object> values, String name) {
        var value = getString(values, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

    @FunctionalInterface
    private interface SimpleHandler {
        Object call(Map<String, Object> arguments);
    }

    record ToolListResult(List<McpTool.ToolDefinition> tools) {}
}
