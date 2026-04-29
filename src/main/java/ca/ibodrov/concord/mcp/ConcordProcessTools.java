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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.server.process.Payload;
import com.walmartlabs.concord.server.process.PayloadManager;
import com.walmartlabs.concord.server.process.ProcessAccessManager;
import com.walmartlabs.concord.server.process.ProcessDataInclude;
import com.walmartlabs.concord.server.process.ProcessEntry;
import com.walmartlabs.concord.server.process.ProcessManager;
import com.walmartlabs.concord.server.sdk.ConcordApplicationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartInput;

class ConcordProcessTools {

    private static final int MAX_PARTS = positiveIntegerProperty("concord.mcp.maxProcessParts", 64);
    private static final int MAX_PART_BYTES =
            positiveIntegerProperty("concord.mcp.maxProcessPartBytes", 16 * 1024 * 1024);
    private static final int MAX_TOTAL_PART_BYTES =
            positiveIntegerProperty("concord.mcp.maxProcessTotalBytes", 64 * 1024 * 1024);
    private static final Set<ProcessDataInclude> PROCESS_DATA_INCLUDES = Set.of();

    private final PayloadManager payloadManager;
    private final ProcessManager processManager;
    private final ProcessAccessManager processAccessManager;

    @Inject
    ConcordProcessTools(
            PayloadManager payloadManager, ProcessManager processManager, ProcessAccessManager processAccessManager) {

        this.payloadManager = payloadManager;
        this.processManager = processManager;
        this.processAccessManager = processAccessManager;
    }

    ProcessStartResult startProcess(Map<String, Object> arguments, HttpServletRequest request) {
        var input = parseParts(arguments.get("parts"));
        if (input.getParts().isEmpty()) {
            throw new IllegalArgumentException("'parts' must contain at least one part");
        }

        if (Boolean.parseBoolean(input.stringPart(Constants.Multipart.SYNC))) {
            throw new ConcordApplicationException(
                    "Synchronous process start is not supported", Response.Status.BAD_REQUEST);
        }

        try {
            var payload = payloadManager.createPayload(input, request);
            var result = processManager.start(payload);
            var process = processAccessManager.assertAccess(result.getInstanceId(), PROCESS_DATA_INCLUDES);

            return ProcessStartResult.started(
                    result.getInstanceId().toString(),
                    payload.getHeader(Payload.ORGANIZATION_ID),
                    payload.getHeader(Payload.PROJECT_ID),
                    payload.getHeader(Payload.REPOSITORY_ID),
                    payload.getHeader(Payload.ENTRY_POINT),
                    process);
        } catch (IOException e) {
            throw new ConcordApplicationException("Error while creating process payload: " + e.getMessage(), e);
        } finally {
            input.close();
        }
    }

    ProcessResult getProcess(Map<String, Object> arguments, HttpServletRequest request) {
        var args = new ToolArguments(arguments);
        var instanceId = UUID.fromString(args.requireString("instanceId"));
        var process = assertProcess(instanceId);
        return ProcessResult.from(process);
    }

    ProcessEntry assertProcess(UUID instanceId) {
        return processAccessManager.assertAccess(instanceId, PROCESS_DATA_INCLUDES);
    }

    ProcessEntry getProcessEntry(UUID instanceId) {
        return processAccessManager.assertAccess(instanceId, PROCESS_DATA_INCLUDES);
    }

    @SuppressWarnings("unchecked")
    private static McpMultipartInput parseParts(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'parts' must be an array");
        }

        if (list.size() > MAX_PARTS) {
            throw new IllegalArgumentException("'parts' must contain at most " + MAX_PARTS + " entries");
        }

        var result = new ArrayList<InputPart>(list.size());
        var totalBytes = 0L;
        for (var item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("'parts' entries must be objects");
            }

            var part = parsePart((Map<String, Object>) raw);
            if (part.data.length > MAX_PART_BYTES) {
                throw new IllegalArgumentException(
                        "Part '" + part.name + "' exceeds the " + MAX_PART_BYTES + " byte limit");
            }

            totalBytes += part.data.length;
            if (totalBytes > MAX_TOTAL_PART_BYTES) {
                throw new IllegalArgumentException("'parts' exceed the " + MAX_TOTAL_PART_BYTES + " byte total limit");
            }
            result.add(part);
        }
        return new McpMultipartInput(List.copyOf(result));
    }

    private static McpInputPart parsePart(Map<String, Object> raw) {
        var name = requireString(raw, "name");
        validateAttachmentName(name);

        var contentType = optionalString(raw, "contentType");
        var contentTypeFromMessage = contentType != null && !contentType.isBlank();
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.TEXT_PLAIN;
        }

        var text = optionalString(raw, "text");
        var base64 = optionalString(raw, "base64");
        if ((text == null) == (base64 == null)) {
            throw new IllegalArgumentException("Part '" + name + "' must contain exactly one of 'text' or 'base64'");
        }

        var data = text != null ? text.getBytes(UTF_8) : decodeBase64(name, base64, MAX_PART_BYTES);
        return new McpInputPart(name, MediaType.valueOf(contentType), contentTypeFromMessage, data);
    }

    private static byte[] decodeBase64(String name, String base64, int maxBytes) {
        if (maxDecodedBytes(base64) > maxBytes) {
            throw new IllegalArgumentException("Part '" + name + "' exceeds the " + maxBytes + " byte limit");
        }

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Part '" + name + "' must contain valid base64");
        }
    }

    private static long maxDecodedBytes(String base64) {
        return ((long) base64.length() + 3) / 4 * 3;
    }

    private static void validateAttachmentName(String name) {
        if (name.isBlank()
                || name.startsWith("/")
                || name.contains("..")
                || name.contains("\"")
                || name.contains("\r")
                || name.contains("\n")
                || name.indexOf('\0') >= 0
                || Paths.get(name).isAbsolute()) {
            throw new IllegalArgumentException("Invalid attachment name: " + name);
        }
    }

    private static String requireString(Map<String, Object> raw, String name) {
        var value = optionalString(raw, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Part '" + name + "' is required");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> raw, String name) {
        var value = raw.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Part '" + name + "' must be a string");
    }

    private static int positiveIntegerProperty(String name, int defaultValue) {
        var value = Integer.getInteger(name, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessStartResult(
            boolean ok,
            String entity,
            String result,
            String instanceId,
            String orgId,
            String projectId,
            String repositoryId,
            String entryPoint,
            String status) {

        static ProcessStartResult started(
                String instanceId, UUID orgId, UUID projectId, UUID repoId, String entryPoint, ProcessEntry process) {

            return new ProcessStartResult(
                    true,
                    "process",
                    "STARTED",
                    instanceId,
                    uuid(orgId),
                    uuid(projectId),
                    uuid(repoId),
                    entryPoint,
                    process != null ? process.status().name() : null);
        }

        private static String uuid(UUID value) {
            return value != null ? value.toString() : null;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessResult(
            boolean ok,
            String entity,
            String instanceId,
            String status,
            String kind,
            String createdAt,
            String startAt,
            String lastUpdatedAt,
            String lastRunAt,
            Long totalRuntimeMs,
            String orgId,
            String orgName,
            String projectId,
            String projectName,
            String repositoryId,
            String repositoryName,
            String repositoryPath,
            String commitId,
            String commitBranch,
            String runtime,
            String initiator,
            String initiatorId) {

        static ProcessResult from(ProcessEntry process) {
            return new ProcessResult(
                    true,
                    "process",
                    process.instanceId().toString(),
                    process.status().name(),
                    process.kind().name(),
                    time(process.createdAt()),
                    time(process.startAt()),
                    time(process.lastUpdatedAt()),
                    time(process.lastRunAt()),
                    process.totalRuntimeMs(),
                    uuid(process.orgId()),
                    process.orgName(),
                    uuid(process.projectId()),
                    process.projectName(),
                    uuid(process.repoId()),
                    process.repoName(),
                    process.repoPath(),
                    process.commitId(),
                    process.commitBranch(),
                    process.runtime(),
                    process.initiator(),
                    uuid(process.initiatorId()));
        }

        private static String uuid(UUID value) {
            return value != null ? value.toString() : null;
        }

        private static String time(Object value) {
            return value != null ? value.toString() : null;
        }
    }

    private record McpMultipartInput(List<InputPart> parts) implements MultipartInput {

        @Override
        public List<InputPart> getParts() {
            return parts;
        }

        @Override
        public String getPreamble() {
            return null;
        }

        @Override
        public void close() {
            // In-memory parts do not allocate temporary files.
        }

        String stringPart(String name) {
            for (var part : parts) {
                if (!(part instanceof McpInputPart inputPart) || !inputPart.hasName(name)) {
                    continue;
                }

                try {
                    var value = part.getBodyAsString();
                    return value.isEmpty() ? null : value;
                } catch (IOException e) {
                    throw new IllegalArgumentException("Error reading part '" + name + "': " + e.getMessage());
                }
            }
            return null;
        }
    }

    private static final class McpInputPart implements InputPart {

        private final String name;
        private final boolean contentTypeFromMessage;
        private final byte[] data;
        private MediaType mediaType;
        private MultivaluedMap<String, String> headers;

        private McpInputPart(String name, MediaType mediaType, boolean contentTypeFromMessage, byte[] data) {
            this.name = name;
            this.mediaType = mediaType;
            this.contentTypeFromMessage = contentTypeFromMessage;
            this.data = data;
        }

        boolean hasName(String value) {
            return name.equalsIgnoreCase(value);
        }

        @Override
        public MultivaluedMap<String, String> getHeaders() {
            if (headers == null) {
                headers = new MultivaluedHashMap<>();
                headers.putSingle(HttpHeaders.CONTENT_DISPOSITION, "form-data; name=\"" + name + "\"");
                headers.putSingle(HttpHeaders.CONTENT_TYPE, mediaType.toString());
            }
            return headers;
        }

        @Override
        public String getBodyAsString() {
            return new String(data, UTF_8).trim();
        }

        @Override
        public <T> T getBody(Class<T> type, Type genericType) throws IOException {
            if (InputStream.class.equals(type)) {
                return type.cast(new ByteArrayInputStream(data));
            }
            if (String.class.equals(type)) {
                return type.cast(getBodyAsString());
            }
            if (byte[].class.equals(type)) {
                return type.cast(data.clone());
            }
            throw new IOException("Unsupported multipart body type: " + type);
        }

        @Override
        public <T> T getBody(GenericType<T> type) throws IOException {
            @SuppressWarnings("unchecked")
            var rawType = (Class<T>) type.getRawType();
            return getBody(rawType, type.getType());
        }

        @Override
        public MediaType getMediaType() {
            return mediaType;
        }

        @Override
        public boolean isContentTypeFromMessage() {
            return contentTypeFromMessage;
        }

        @Override
        public void setMediaType(MediaType mediaType) {
            this.mediaType = mediaType;
            this.headers = null;
        }
    }
}
