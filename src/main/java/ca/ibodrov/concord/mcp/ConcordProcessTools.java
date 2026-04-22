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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.common.ConfigurationUtils;
import com.walmartlabs.concord.common.ObjectMapperProvider;
import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.server.org.OrganizationDao;
import com.walmartlabs.concord.server.org.ResourceAccessLevel;
import com.walmartlabs.concord.server.org.project.ProjectAccessManager;
import com.walmartlabs.concord.server.org.project.ProjectDao;
import com.walmartlabs.concord.server.org.project.RepositoryDao;
import com.walmartlabs.concord.server.process.Payload;
import com.walmartlabs.concord.server.process.PayloadBuilder;
import com.walmartlabs.concord.server.process.ProcessEntry;
import com.walmartlabs.concord.server.process.ProcessManager;
import com.walmartlabs.concord.server.process.queue.ProcessQueueManager;
import com.walmartlabs.concord.server.sdk.ConcordApplicationException;
import com.walmartlabs.concord.server.sdk.PartialProcessKey;
import com.walmartlabs.concord.server.sdk.ProcessKey;
import com.walmartlabs.concord.server.security.Roles;
import com.walmartlabs.concord.server.security.UnauthorizedException;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.security.sessionkey.SessionKeyPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

class ConcordProcessTools {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapperProvider().get();
    private static final int MAX_PARTS = positiveIntegerProperty("concord.mcp.maxProcessParts", 64);
    private static final int MAX_PART_BYTES =
            positiveIntegerProperty("concord.mcp.maxProcessPartBytes", 16 * 1024 * 1024);
    private static final int MAX_TOTAL_PART_BYTES =
            positiveIntegerProperty("concord.mcp.maxProcessTotalBytes", 64 * 1024 * 1024);

    private final OrganizationDao orgDao;
    private final ProjectDao projectDao;
    private final RepositoryDao repositoryDao;
    private final ProcessManager processManager;
    private final ProcessQueueManager processQueueManager;
    private final ProjectAccessManager projectAccessManager;

    @Inject
    ConcordProcessTools(
            OrganizationDao orgDao,
            ProjectDao projectDao,
            RepositoryDao repositoryDao,
            ProcessManager processManager,
            ProcessQueueManager processQueueManager,
            ProjectAccessManager projectAccessManager) {

        this.orgDao = orgDao;
        this.projectDao = projectDao;
        this.repositoryDao = repositoryDao;
        this.processManager = processManager;
        this.processQueueManager = processQueueManager;
        this.projectAccessManager = projectAccessManager;
    }

    ProcessStartResult startProcess(Map<String, Object> arguments, HttpServletRequest request) {
        var parts = parseParts(arguments.get("parts"));
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("'parts' must contain at least one part");
        }

        if (Boolean.parseBoolean(stringPart(parts, Constants.Multipart.SYNC))) {
            throw new ConcordApplicationException(
                    "Synchronous process start is not supported", Response.Status.BAD_REQUEST);
        }

        var processKey = PartialProcessKey.create();
        var parentInstanceId = uuidPart(parts, Constants.Multipart.PARENT_INSTANCE_ID);
        var orgId = orgId(parts);
        var projectId = projectId(parts, orgId);
        var repoId = repoId(parts, projectId);
        if (repoId != null && projectId == null) {
            projectId = repositoryDao.getProjectId(repoId);
        }

        var entryPoint = stringPart(parts, Constants.Multipart.ENTRY_POINT);
        var out = outExpressions(parts);
        var meta = meta(parts);
        var initiator = UserPrincipal.assertCurrent();

        try {
            var payload = PayloadBuilder.start(processKey)
                    .parentInstanceId(parentInstanceId)
                    .configuration(configuration(parts))
                    .organization(orgId)
                    .project(projectId)
                    .repository(repoId)
                    .entryPoint(entryPoint)
                    .outExpressions(out)
                    .initiator(initiator.getId(), initiator.getUsername())
                    .meta(meta)
                    .request(request)
                    .build();

            payload = addAttachments(payload, parts);
            var result = processManager.start(payload);
            var process = processQueueManager.get(PartialProcessKey.from(result.getInstanceId()));

            return ProcessStartResult.started(
                    result.getInstanceId().toString(), orgId, projectId, repoId, entryPoint, process);
        } catch (IOException e) {
            throw new ConcordApplicationException("Error while creating process payload: " + e.getMessage(), e);
        }
    }

    ProcessResult getProcess(Map<String, Object> arguments, HttpServletRequest request) {
        var args = new ToolArguments(arguments);
        var instanceId = UUID.fromString(args.requireString("instanceId"));
        var process = assertProcess(instanceId);
        return ProcessResult.from(process);
    }

    ProcessEntry assertProcess(UUID instanceId) {
        var process = getProcessEntry(instanceId);
        assertProcessAccess(process);
        return process;
    }

    ProcessEntry getProcessEntry(UUID instanceId) {
        var process = processQueueManager.get(PartialProcessKey.from(instanceId), Collections.emptySet());
        if (process == null) {
            throw new ConcordApplicationException(
                    "Process instance not found: " + instanceId, Response.Status.NOT_FOUND);
        }
        return process;
    }

    private void assertProcessAccess(ProcessEntry process) {
        if (Roles.isAdmin() || Roles.isGlobalReader()) {
            return;
        }

        var current = UserPrincipal.assertCurrent();
        if (current.getId().equals(process.initiatorId())) {
            return;
        }

        var sessionKey = SessionKeyPrincipal.getCurrent();
        if (sessionKey != null) {
            var processKey = new ProcessKey(process.instanceId(), process.createdAt());
            if (processKey.partOf(sessionKey.getProcessKey())) {
                return;
            }
        }

        if (process.projectId() != null) {
            projectAccessManager.assertAccess(
                    process.orgId(), process.projectId(), null, ResourceAccessLevel.READER, false);
            return;
        }

        throw new UnauthorizedException(
                "User '" + current.getUsername() + "' is not authorized to access process " + process.instanceId());
    }

    private static Map<String, Object> configuration(List<Part> parts) {
        var cfg = new LinkedHashMap<String, Object>();
        for (var part : parts) {
            if (!part.isTextPlain()) {
                continue;
            }

            var nested = ConfigurationUtils.toNested(part.name(), part.stringValue());
            cfg = new LinkedHashMap<>(ConfigurationUtils.deepMerge(cfg, nested));
        }
        return cfg;
    }

    private static Payload addAttachments(Payload payload, List<Part> parts) throws IOException {
        var attachments = new LinkedHashMap<String, Path>();
        var baseDir = payload.getHeader(Payload.BASE_DIR);
        if (baseDir == null) {
            throw new IllegalStateException("Payload base directory is not initialized");
        }

        for (var part : parts) {
            if (part.isTextPlain()) {
                continue;
            }

            var target = baseDir.resolve(part.name()).normalize();
            if (!target.startsWith(baseDir)) {
                throw new IllegalArgumentException("Invalid attachment name: " + part.name());
            }

            Files.createDirectories(target.getParent());
            try (var in = new ByteArrayInputStream(part.data())) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            attachments.put(part.name(), target);
        }

        return payload.putAttachments(attachments);
    }

    private UUID orgId(List<Part> parts) {
        var orgId = uuidPart(parts, Constants.Multipart.ORG_ID);
        var orgName = stringPart(parts, Constants.Multipart.ORG_NAME);
        if (orgId == null && orgName != null) {
            orgId = orgDao.getId(orgName);
            if (orgId == null) {
                throw new ConcordApplicationException("Organization not found: " + orgName, Response.Status.NOT_FOUND);
            }
        }
        return orgId;
    }

    private UUID projectId(List<Part> parts, UUID orgId) {
        var projectId = uuidPart(parts, Constants.Multipart.PROJECT_ID);
        var projectName = stringPart(parts, Constants.Multipart.PROJECT_NAME);
        if (projectId == null && projectName != null) {
            if (orgId == null) {
                throw new IllegalArgumentException("Organization ID or name is required");
            }

            projectId = projectDao.getId(orgId, projectName);
            if (projectId == null) {
                throw new ConcordApplicationException("Project not found: " + projectName, Response.Status.NOT_FOUND);
            }
        }
        return projectId;
    }

    private UUID repoId(List<Part> parts, UUID projectId) {
        var repoId = uuidPart(parts, Constants.Multipart.REPO_ID);
        var repoName = stringPart(parts, Constants.Multipart.REPO_NAME);
        if (repoId == null && repoName != null) {
            if (projectId == null) {
                throw new IllegalArgumentException("Project ID or name is required");
            }

            repoId = repositoryDao.getId(projectId, repoName);
            if (repoId == null) {
                throw new ConcordApplicationException("Repository not found: " + repoName, Response.Status.NOT_FOUND);
            }
        }
        return repoId;
    }

    private static String[] outExpressions(List<Part> parts) {
        var value = stringPart(parts, Constants.Multipart.OUT_EXPR);
        return value != null ? value.split(",") : null;
    }

    private static Map<String, Object> meta(List<Part> parts) {
        var value = stringPart(parts, Constants.Multipart.META);
        if (value == null) {
            return Collections.emptyMap();
        }

        try {
            return value.isBlank() ? Collections.emptyMap() : OBJECT_MAPPER.readValue(value, MAP_TYPE);
        } catch (IOException e) {
            throw new IllegalArgumentException("'meta' must be a JSON object");
        }
    }

    private static UUID uuidPart(List<Part> parts, String name) {
        var value = stringPart(parts, name);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' must be a UUID");
        }
    }

    private static String stringPart(List<Part> parts, String name) {
        for (var part : parts) {
            if (part.name().equalsIgnoreCase(name)) {
                var value = part.stringValue();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Part> parseParts(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'parts' must be an array");
        }

        if (list.size() > MAX_PARTS) {
            throw new IllegalArgumentException("'parts' must contain at most " + MAX_PARTS + " entries");
        }

        var result = new ArrayList<Part>(list.size());
        var totalBytes = 0L;
        for (var item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("'parts' entries must be objects");
            }

            var part = parsePart((Map<String, Object>) raw);
            if (part.data().length > MAX_PART_BYTES) {
                throw new IllegalArgumentException(
                        "Part '" + part.name() + "' exceeds the " + MAX_PART_BYTES + " byte limit");
            }

            totalBytes += part.data().length;
            if (totalBytes > MAX_TOTAL_PART_BYTES) {
                throw new IllegalArgumentException("'parts' exceed the " + MAX_TOTAL_PART_BYTES + " byte total limit");
            }
            result.add(part);
        }
        return List.copyOf(result);
    }

    private static Part parsePart(Map<String, Object> raw) {
        var name = requireString(raw, "name");
        validateAttachmentName(name);

        var contentType = optionalString(raw, "contentType");
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.TEXT_PLAIN;
        }

        var text = optionalString(raw, "text");
        var base64 = optionalString(raw, "base64");
        if ((text == null) == (base64 == null)) {
            throw new IllegalArgumentException("Part '" + name + "' must contain exactly one of 'text' or 'base64'");
        }

        var data = text != null ? text.getBytes(UTF_8) : decodeBase64(name, base64, MAX_PART_BYTES);
        return new Part(name, MediaType.valueOf(contentType), data);
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

    private record Part(String name, MediaType mediaType, byte[] data) {

        boolean isTextPlain() {
            return mediaType.isCompatible(MediaType.TEXT_PLAIN_TYPE);
        }

        String stringValue() {
            return new String(data, UTF_8).trim();
        }
    }
}
