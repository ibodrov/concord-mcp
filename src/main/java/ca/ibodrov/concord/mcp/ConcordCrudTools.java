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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.core.Response;

@Singleton
class ConcordCrudTools {

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

    Map<String, Object> createOrg(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var name = args.requireString("name");
        var visibility = args.optionalEnum("visibility", OrganizationVisibility.class, OrganizationVisibility.PUBLIC);

        var entry = new OrganizationEntry(
                null, name, null, visibility, args.optionalObject("meta"), args.optionalObject("cfg"));
        var result = orgManager.createOrUpdate(entry);

        return McpResource.orderedMap(
                "ok",
                true,
                "entity",
                "organization",
                "result",
                result.result().name(),
                "orgId",
                result.orgId().toString(),
                "name",
                name,
                "visibility",
                visibility.name());
    }

    Map<String, Object> createProject(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var orgName = args.requireString("orgName");
        var name = args.requireString("name");
        var visibility = args.optionalEnum("visibility", ProjectVisibility.class, ProjectVisibility.PUBLIC);

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

        return McpResource.orderedMap(
                "ok",
                true,
                "entity",
                "project",
                "result",
                result.result().name(),
                "orgName",
                orgName,
                "projectId",
                result.projectId().toString(),
                "name",
                name,
                "visibility",
                visibility.name());
    }

    Map<String, Object> createRepository(Map<String, Object> arguments) {
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

        return McpResource.orderedMap(
                "ok", true,
                "entity", "repository",
                "result", result.name(),
                "orgName", orgName,
                "projectName", projectName,
                "repositoryId", saved.getId().toString(),
                "name", saved.getName(),
                "url", saved.getUrl(),
                "branch", saved.getBranch(),
                "path", saved.getPath(),
                "disabled", saved.isDisabled());
    }

    Map<String, Object> createDataSecret(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var data = args.optionalString("data");
        var dataBase64 = args.optionalString("dataBase64");
        if ((data == null || data.isBlank()) && (dataBase64 == null || dataBase64.isBlank())) {
            throw new IllegalArgumentException("'data' or 'dataBase64' is required");
        }

        var bytes = dataBase64 != null ? Base64.getDecoder().decode(dataBase64) : data.getBytes(StandardCharsets.UTF_8);
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

        return secretResult(
                org, args.requireString("name"), "DATA", created.getId(), visibility, storeType, projectIds);
    }

    Map<String, Object> createUsernamePasswordSecret(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var org = orgManager.assertAccess(args.requireString("orgName"), true);
        var projectIds = projectIds(org.getId(), args);
        var storeType = storeType(args);
        var visibility = visibility(args);
        var password = args.requireString("password");

        var created = secretManager.createUsernamePassword(
                org.getId(),
                projectIds,
                args.requireString("name"),
                args.optionalString("storePassword"),
                args.requireString("username"),
                password.toCharArray(),
                visibility,
                storeType);

        return secretResult(
                org,
                args.requireString("name"),
                "USERNAME_PASSWORD",
                created.getId(),
                visibility,
                storeType,
                projectIds);
    }

    Map<String, Object> createKeyPairSecret(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var org = orgManager.assertAccess(args.requireString("orgName"), true);
        var projectIds = projectIds(org.getId(), args);
        var storeType = storeType(args);
        var visibility = visibility(args);

        var created = createKeyPairSecret(args, org.getId(), projectIds, visibility, storeType);

        return McpResource.orderedMap(
                "ok",
                true,
                "entity",
                "secret",
                "result",
                OperationResult.CREATED.name(),
                "orgName",
                org.getName(),
                "secretId",
                created.getId().toString(),
                "name",
                args.requireString("name"),
                "type",
                "KEY_PAIR",
                "visibility",
                visibility.name(),
                "storeType",
                storeType,
                "projectIds",
                projectIds == null
                        ? null
                        : projectIds.stream().map(UUID::toString).toList(),
                "publicKey",
                new String(created.getData(), StandardCharsets.UTF_8));
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
            return secretManager.createKeyPair(
                    orgId,
                    projectIds,
                    args.requireString("name"),
                    args.optionalString("storePassword"),
                    new ByteArrayInputStream(args.requireString("publicKey").getBytes(StandardCharsets.UTF_8)),
                    new ByteArrayInputStream(args.requireString("privateKey").getBytes(StandardCharsets.UTF_8)),
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
        return args.optionalEnum("visibility", SecretVisibility.class, SecretVisibility.PUBLIC);
    }

    private static Map<String, Object> secretResult(
            OrganizationEntry org,
            String name,
            String type,
            UUID secretId,
            SecretVisibility visibility,
            String storeType,
            Set<UUID> projectIds) {

        return McpResource.orderedMap(
                "ok",
                true,
                "entity",
                "secret",
                "result",
                OperationResult.CREATED.name(),
                "orgName",
                org.getName(),
                "secretId",
                secretId.toString(),
                "name",
                name,
                "type",
                type,
                "visibility",
                visibility.name(),
                "storeType",
                storeType,
                "projectIds",
                projectIds == null
                        ? null
                        : projectIds.stream().map(UUID::toString).toList());
    }
}
