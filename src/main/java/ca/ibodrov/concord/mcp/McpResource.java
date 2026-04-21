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

import com.walmartlabs.concord.server.sdk.rest.Resource;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

@Path("/api/v1/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces({MediaType.APPLICATION_JSON, McpSseWriter.MEDIA_TYPE})
public class McpResource implements Resource {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String ORIGIN_HEADER = "Origin";
    private static final String X_ACCEL_BUFFERING_HEADER = "X-Accel-Buffering";
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_NAME = "concord-mcp-server";
    private static final String SERVER_VERSION = serverVersion();
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private final McpToolRegistry toolRegistry;

    @Inject
    public McpResource(McpToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @POST
    public Response post(Map<String, Object> message, @Context HttpServletRequest request) {
        if (!isOriginAllowed(request)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(JsonRpcErrorResponse.from(null, JsonRpcError.SERVER_ERROR, "Forbidden Origin header"))
                    .build();
        }

        var id = message != null ? message.get("id") : null;
        var hasId = message != null && message.containsKey("id");

        try {
            if (message == null || !JSON_RPC_VERSION.equals(message.get("jsonrpc"))) {
                return Response.ok(
                                JsonRpcErrorResponse.from(id, JsonRpcError.INVALID_REQUEST, "Invalid JSON-RPC request"))
                        .build();
            }

            var method = asString(message.get("method"));
            if (method == null || method.isBlank()) {
                return Response.ok(JsonRpcErrorResponse.from(
                                id, JsonRpcError.INVALID_REQUEST, "JSON-RPC method is required"))
                        .build();
            }

            if (!hasId) {
                handleNotification(method);
                return Response.accepted().build();
            }

            var params = asObject(message.get("params"));
            if ("tools/call".equals(method) && acceptsEventStream(request) && toolRegistry.isStreamableTool(params)) {
                return Response.ok(
                                (StreamingOutput) out -> toolRegistry.streamTool(params, id, request, out),
                                McpSseWriter.MEDIA_TYPE)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header(X_ACCEL_BUFFERING_HEADER, "no")
                        .build();
            }

            var result =
                    switch (method) {
                        case "initialize" -> InitializeResult.from(params);
                        case "ping" -> new EmptyResult();
                        case "logging/setLevel" -> new EmptyResult();
                        case "tools/list" -> toolRegistry.listTools();
                        case "tools/call" -> toolRegistry.callTool(params, request);
                        default -> null;
                    };

            if (result == null) {
                return Response.ok(JsonRpcErrorResponse.from(
                                id, JsonRpcError.METHOD_NOT_FOUND, "Method not found: " + method))
                        .build();
            }

            return Response.ok(JsonRpcResponse.success(id, result)).build();
        } catch (IllegalArgumentException e) {
            return Response.ok(JsonRpcErrorResponse.from(id, JsonRpcError.INVALID_PARAMS, e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.ok(JsonRpcErrorResponse.from(
                            id, JsonRpcError.INTERNAL_ERROR, "Internal error: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    public Response get() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
    }

    @DELETE
    public Response delete() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
    }

    private static void handleNotification(String method) {
        if (!"notifications/initialized".equals(method) && !method.startsWith("notifications/")) {
            throw new IllegalArgumentException("Unsupported notification: " + method);
        }
    }

    private static boolean isOriginAllowed(HttpServletRequest request) {
        var origin = request.getHeader(ORIGIN_HEADER);
        if (origin == null || origin.isBlank()) {
            return true;
        }

        var host = request.getHeader(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            return false;
        }

        try {
            var originUri = URI.create(origin);
            var originHost = originUri.getHost();
            if (originHost == null) {
                return false;
            }

            var originPort = originUri.getPort();
            var requestPort = request.getServerPort();
            var normalizedOrigin = normalizeHostPort(originHost, originPort);
            var normalizedHost = normalizeHostPort(host, requestPort);
            return normalizedOrigin.equalsIgnoreCase(normalizedHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        var accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains(McpSseWriter.MEDIA_TYPE);
    }

    private static String normalizeHostPort(String host, int defaultPort) {
        if (host.indexOf(':') >= 0) {
            return host;
        }
        return host + ":" + defaultPort;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("Expected an object");
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String serverVersion() {
        var implementationVersion = McpResource.class.getPackage().getImplementationVersion();
        return implementationVersion != null && !implementationVersion.isBlank()
                ? implementationVersion
                : "0.0.1-SNAPSHOT";
    }

    private record EmptyResult() {}

    private record InitializeResult(String protocolVersion, Capabilities capabilities, ServerInfo serverInfo) {

        static InitializeResult from(Map<String, Object> params) {
            var requestedProtocolVersion = asString(params.get("protocolVersion"));
            var protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.contains(requestedProtocolVersion)
                    ? requestedProtocolVersion
                    : DEFAULT_PROTOCOL_VERSION;

            return new InitializeResult(
                    protocolVersion,
                    new Capabilities(new ToolsCapability(false), new LoggingCapability()),
                    new ServerInfo(SERVER_NAME, SERVER_VERSION));
        }
    }

    private record Capabilities(ToolsCapability tools, LoggingCapability logging) {}

    private record ToolsCapability(boolean listChanged) {}

    private record LoggingCapability() {}

    private record ServerInfo(String name, String version) {}

    private record JsonRpcResponse(String jsonrpc, Object id, Object result) {

        static JsonRpcResponse success(Object id, Object result) {
            return new JsonRpcResponse(JSON_RPC_VERSION, id, result);
        }
    }

    private record JsonRpcErrorResponse(String jsonrpc, Object id, JsonRpcError error) {

        static JsonRpcErrorResponse from(Object id, int code, String message) {
            return new JsonRpcErrorResponse(JSON_RPC_VERSION, id, new JsonRpcError(code, message));
        }
    }

    private record JsonRpcError(int code, String message) {

        static final int SERVER_ERROR = -32000;
        static final int INVALID_REQUEST = -32600;
        static final int METHOD_NOT_FOUND = -32601;
        static final int INVALID_PARAMS = -32602;
        static final int INTERNAL_ERROR = -32603;
    }

    static Map<String, Object> orderedMap(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }

        var result = new LinkedHashMap<String, Object>();
        for (var i = 0; i < values.length; i += 2) {
            var value = values[i + 1];
            if (value != null) {
                result.put(String.valueOf(values[i]), value);
            }
        }
        return result;
    }
}
