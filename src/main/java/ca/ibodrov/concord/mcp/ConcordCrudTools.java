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
import com.walmartlabs.concord.server.OperationResult;
import com.walmartlabs.concord.server.jooq.enums.OutVariablesMode;
import com.walmartlabs.concord.server.jooq.enums.ProcessExecMode;
import com.walmartlabs.concord.server.jooq.enums.RawPayloadMode;
import com.walmartlabs.concord.server.org.OrganizationEntry;
import com.walmartlabs.concord.server.org.OrganizationManager;
import com.walmartlabs.concord.server.org.OrganizationVisibility;
import com.walmartlabs.concord.server.org.project.ProjectDao;
import com.walmartlabs.concord.server.org.project.ProjectEntry;
import com.walmartlabs.concord.server.org.project.ProjectManager;
import com.walmartlabs.concord.server.org.project.ProjectRepositoryManager;
import com.walmartlabs.concord.server.org.project.ProjectVisibility;
import com.walmartlabs.concord.server.org.project.RepositoryDao;
import com.walmartlabs.concord.server.org.project.RepositoryEntry;
import com.walmartlabs.concord.server.org.secret.SecretManager;
import com.walmartlabs.concord.server.org.secret.SecretManager.DecryptedKeyPair;
import com.walmartlabs.concord.server.org.secret.SecretVisibility;
import com.walmartlabs.concord.server.sdk.ConcordApplicationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

class ConcordCrudTools {

    private static final int MAX_SECRET_BYTES = positiveIntegerProperty("concord.mcp.maxSecretBytes", 1024 * 1024);

    private final OrganizationManager orgManager;
    private final ProjectManager projectManager;
    private final ProjectDao projectDao;
    private final ProjectRepositoryManager repositoryManager;
    private final RepositoryDao repositoryDao;
    private final SecretManager secretManager;

    @Inject
    ConcordCrudTools(
            OrganizationManager orgManager,
            ProjectManager projectManager,
            ProjectDao projectDao,
            ProjectRepositoryManager repositoryManager,
            RepositoryDao repositoryDao,
            SecretManager secretManager) {

        this.orgManager = orgManager;
        this.projectManager = projectManager;
        this.projectDao = projectDao;
        this.repositoryManager = repositoryManager;
        this.repositoryDao = repositoryDao;
        this.secretManager = secretManager;
    }

    OrganizationResult createOrg(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var name = args.requireString("name");
        var visibility = args.optionalEnum("visibility", OrganizationVisibility.class, OrganizationVisibility.PRIVATE);

        var entry = new OrganizationEntry(
                null, name, null, visibility, args.optionalObject("meta"), args.optionalObject("cfg"));
        var result = orgManager.createOrUpdate(entry);

        return new OrganizationResult(
                true, "organization", result.result().name(), result.orgId().toString(), name, visibility.name());
    }

    ProjectResult createProject(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var orgName = args.requireString("orgName");
        var name = args.requireString("name");
        var visibility = args.optionalEnum("visibility", ProjectVisibility.class, ProjectVisibility.PRIVATE);

        var entry = new ProjectEntry(
                null,
                name,
                args.optionalString("description"),
                null,
                null,
                null,
                args.optionalObject("cfg"),
                visibility,
                null,
                RawPayloadMode.DISABLED,
                args.optionalObject("meta"),
                OutVariablesMode.DISABLED,
                ProcessExecMode.READERS,
                null);
        var result = projectManager.createOrUpdate(orgName, entry);

        return new ProjectResult(
                true,
                "project",
                result.result().name(),
                orgName,
                result.projectId().toString(),
                name,
                visibility.name());
    }

    RepositoryResult createRepository(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var orgName = args.requireString("orgName");
        var projectName = args.requireString("projectName");
        var name = args.requireString("name");

        var org = orgManager.assertAccess(orgName, true);
        var projectId = projectDao.getId(org.getId(), projectName);
        if (projectId == null) {
            throw new ConcordApplicationException("Project not found: " + projectName, Response.Status.NOT_FOUND);
        }

        var existingRepoId = repositoryDao.getId(projectId, name);
        var branch = args.optionalString("branch");
        var commitId = args.optionalString("commitId");
        if ((branch == null || branch.isBlank()) == (commitId == null || commitId.isBlank())) {
            throw new IllegalArgumentException("Exactly one of 'branch' or 'commitId' is required");
        }

        var entry = new RepositoryEntry(
                existingRepoId,
                null,
                name,
                args.requireString("url"),
                branch,
                commitId,
                args.optionalString("path"),
                args.optionalBoolean("disabled", false),
                args.optionalUuid("secretId"),
                args.optionalString("secretName"),
                args.optionalString("secretStoreType"),
                args.optionalObject("meta"),
                args.optionalBoolean("triggersDisabled", false));

        repositoryManager.createOrUpdate(projectId, entry);
        var saved = repositoryManager.get(projectId, name);
        var result = existingRepoId == null ? OperationResult.CREATED : OperationResult.UPDATED;

        return new RepositoryResult(
                true,
                "repository",
                result.name(),
                orgName,
                projectName,
                saved.getId().toString(),
                saved.getName(),
                saved.getUrl(),
                saved.getBranch(),
                saved.getPath(),
                saved.isDisabled());
    }

    SecretResult createDataSecret(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var data = blankToNull(args.optionalString("data"));
        var dataBase64 = blankToNull(args.optionalString("dataBase64"));
        if ((data == null) == (dataBase64 == null)) {
            throw new IllegalArgumentException("Exactly one non-blank value of 'data' or 'dataBase64' is required");
        }

        var bytes = dataBase64 != null ? decodeBase64("dataBase64", dataBase64) : data.getBytes(UTF_8);
        if (bytes.length > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("Secret data exceeds the " + MAX_SECRET_BYTES + " byte limit");
        }

        var org = orgManager.assertAccess(args.requireString("orgName"), true);
        var projectIds = projectIds(org.getId(), args);
        var storeType = storeType(args);
        var visibility = visibility(args);

        var created = secretManager.createBinaryData(
                org.getId(),
                projectIds,
                args.requireString("name"),
                args.optionalString("storePassword"),
                new ByteArrayInputStream(bytes),
                visibility,
                storeType);

        return SecretResult.created(
                org, args.requireString("name"), "DATA", created.getId(), visibility, storeType, projectIds, null);
    }

    SecretResult createUsernamePasswordSecret(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var org = orgManager.assertAccess(args.requireString("orgName"), true);
        var projectIds = projectIds(org.getId(), args);
        var storeType = storeType(args);
        var visibility = visibility(args);
        var password = args.requireString("password");
        assertUtf8Size("password", password);

        var created = secretManager.createUsernamePassword(
                org.getId(),
                projectIds,
                args.requireString("name"),
                args.optionalString("storePassword"),
                args.requireString("username"),
                password.toCharArray(),
                visibility,
                storeType);

        return SecretResult.created(
                org,
                args.requireString("name"),
                "USERNAME_PASSWORD",
                created.getId(),
                visibility,
                storeType,
                projectIds,
                null);
    }

    SecretResult createKeyPairSecret(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var org = orgManager.assertAccess(args.requireString("orgName"), true);
        var projectIds = projectIds(org.getId(), args);
        var storeType = storeType(args);
        var visibility = visibility(args);

        var created = createKeyPairSecret(args, org.getId(), projectIds, visibility, storeType);

        return SecretResult.created(
                org,
                args.requireString("name"),
                "KEY_PAIR",
                created.getId(),
                visibility,
                storeType,
                projectIds,
                new String(created.getData(), UTF_8));
    }

    private DecryptedKeyPair createKeyPairSecret(
            ToolArguments args, UUID orgId, Set<UUID> projectIds, SecretVisibility visibility, String storeType) {

        var publicKey = args.optionalString("publicKey");
        var privateKey = args.optionalString("privateKey");
        if (publicKey == null && privateKey == null) {
            return secretManager.createKeyPair(
                    orgId,
                    projectIds,
                    args.requireString("name"),
                    args.optionalString("storePassword"),
                    visibility,
                    storeType);
        }

        try {
            publicKey = args.requireString("publicKey");
            privateKey = args.requireString("privateKey");
            assertUtf8Size("publicKey", publicKey);
            assertUtf8Size("privateKey", privateKey);

            return secretManager.createKeyPair(
                    orgId,
                    projectIds,
                    args.requireString("name"),
                    args.optionalString("storePassword"),
                    new ByteArrayInputStream(publicKey.getBytes(UTF_8)),
                    new ByteArrayInputStream(privateKey.getBytes(UTF_8)),
                    visibility,
                    storeType);
        } catch (IOException e) {
            throw new ConcordApplicationException("Error while creating key pair secret: " + e.getMessage(), e);
        }
    }

    private Set<UUID> projectIds(UUID orgId, ToolArguments args) {
        var result = new LinkedHashSet<UUID>();
        for (var projectId : args.optionalStringList("projectIds")) {
            result.add(UUID.fromString(projectId));
        }
        for (var projectName : args.optionalStringList("projectNames")) {
            var projectId = projectDao.getId(orgId, projectName);
            if (projectId == null) {
                throw new ConcordApplicationException("Project not found: " + projectName, Response.Status.NOT_FOUND);
            }
            result.add(projectId);
        }
        return result.isEmpty() ? null : result;
    }

    private String storeType(ToolArguments args) {
        var storeType = args.optionalString("storeType");
        if (storeType == null || storeType.isBlank()) {
            return secretManager.getDefaultSecretStoreType();
        }

        var active = secretManager.getActiveSecretStores().stream()
                .anyMatch(store -> store.getType().equalsIgnoreCase(storeType));
        if (!active) {
            throw new IllegalArgumentException("Secret store of type " + storeType + " is not available");
        }
        return storeType;
    }

    private static SecretVisibility visibility(ToolArguments args) {
        return args.optionalEnum("visibility", SecretVisibility.class, SecretVisibility.PRIVATE);
    }

    private static byte[] decodeBase64(String name, String base64) {
        if (maxDecodedBytes(base64) > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("'" + name + "' exceeds the " + MAX_SECRET_BYTES + " byte limit");
        }

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' must be valid base64");
        }
    }

    private static long maxDecodedBytes(String base64) {
        return ((long) base64.length() + 3) / 4 * 3;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void assertUtf8Size(String name, String value) {
        if (value.getBytes(UTF_8).length > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("'" + name + "' exceeds the " + MAX_SECRET_BYTES + " byte limit");
        }
    }

    private static int positiveIntegerProperty(String name, int defaultValue) {
        var value = Integer.getInteger(name, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrganizationResult(boolean ok, String entity, String result, String orgId, String name, String visibility) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProjectResult(
            boolean ok,
            String entity,
            String result,
            String orgName,
            String projectId,
            String name,
            String visibility) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RepositoryResult(
            boolean ok,
            String entity,
            String result,
            String orgName,
            String projectName,
            String repositoryId,
            String name,
            String url,
            String branch,
            String path,
            boolean disabled) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SecretResult(
            boolean ok,
            String entity,
            String result,
            String orgName,
            String secretId,
            String name,
            String type,
            String visibility,
            String storeType,
            List<String> projectIds,
            String publicKey) {

        static SecretResult created(
                OrganizationEntry org,
                String name,
                String type,
                UUID secretId,
                SecretVisibility visibility,
                String storeType,
                Set<UUID> projectIds,
                String publicKey) {

            return new SecretResult(
                    true,
                    "secret",
                    OperationResult.CREATED.name(),
                    org.getName(),
                    secretId.toString(),
                    name,
                    type,
                    visibility.name(),
                    storeType,
                    projectIds(projectIds),
                    publicKey);
        }

        private static List<String> projectIds(Set<UUID> projectIds) {
            return projectIds == null
                    ? null
                    : projectIds.stream().map(UUID::toString).toList();
        }
    }
}
