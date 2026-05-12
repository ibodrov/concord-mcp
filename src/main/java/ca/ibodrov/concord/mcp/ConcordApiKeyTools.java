package ca.ibodrov.concord.mcp;

/*-
 * ~~~~~~
 * MCP Server Plugin for Concord
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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.walmartlabs.concord.server.OperationResult;
import com.walmartlabs.concord.server.sdk.ConcordApplicationException;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.security.apikey.ApiKeyDao;
import com.walmartlabs.concord.server.security.apikey.ApiKeyEntry;
import com.walmartlabs.concord.server.security.apikey.ApiKeyManager;
import com.walmartlabs.concord.server.security.apikey.CreateApiKeyRequest;
import com.walmartlabs.concord.server.security.apikey.CreateApiKeyResponse;
import com.walmartlabs.concord.server.user.UserDao;
import com.walmartlabs.concord.server.user.UserEntry;
import com.walmartlabs.concord.server.user.UserManager;
import com.walmartlabs.concord.server.user.UserType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

class ConcordApiKeyTools {

    private final ApiKeyManager apiKeyManager;
    private final ApiKeyDao apiKeyDao;
    private final UserManager userManager;
    private final UserDao userDao;

    @Inject
    ConcordApiKeyTools(ApiKeyManager apiKeyManager, ApiKeyDao apiKeyDao, UserManager userManager, UserDao userDao) {
        this.apiKeyManager = apiKeyManager;
        this.apiKeyDao = apiKeyDao;
        this.userManager = userManager;
        this.userDao = userDao;
    }

    ApiKeyCreateResult createApiKey(Map<String, Object> arguments) {
        var response = apiKeyManager.create(createRequest(arguments));
        return createResult(response);
    }

    ApiKeyCreateResult createOrUpdateApiKey(Map<String, Object> arguments) {
        var response = apiKeyManager.createOrUpdate(createRequest(arguments));
        return createResult(response);
    }

    ApiKeyListResult listApiKeys(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        var userId = resolveOptionalUserId(args);
        var entries = apiKeyManager.list(userId);
        var effectiveUserId =
                userId != null ? userId : UserPrincipal.assertCurrent().getId();
        var user = requireUser(effectiveUserId);

        return new ApiKeyListResult(
                true,
                "apiKeys",
                user.getId().toString(),
                user.getName(),
                user.getDomain(),
                user.getType().name(),
                entries.stream().map(ApiKeyResult::from).toList());
    }

    DeleteApiKeyResult deleteApiKey(Map<String, Object> arguments) {
        var keyId = new ToolArguments(arguments).optionalUuid("keyId");
        if (keyId == null) {
            throw new IllegalArgumentException("'keyId' is required");
        }

        apiKeyManager.deleteById(keyId);
        return new DeleteApiKeyResult(true, "apiKey", OperationResult.DELETED.name(), keyId.toString());
    }

    private CreateApiKeyRequest createRequest(Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        assertOneUserSelector(args);

        return new CreateApiKeyRequest(
                args.optionalUuid("userId"),
                args.optionalString("username"),
                args.optionalString("domain"),
                args.optionalEnum("type", UserType.class, null),
                args.optionalString("name"),
                args.optionalString("key"));
    }

    private ApiKeyCreateResult createResult(CreateApiKeyResponse response) {
        var userId = apiKeyDao.getUserId(response.getId());
        var user = requireUser(userId);
        var entry = apiKeyDao.list(userId).stream()
                .filter(e -> response.getId().equals(e.getId()))
                .findFirst()
                .orElse(null);

        return ApiKeyCreateResult.from(response, user, entry);
    }

    private UUID resolveOptionalUserId(ToolArguments args) {
        assertOneUserSelector(args);

        var userId = args.optionalUuid("userId");
        if (userId != null) {
            return userId;
        }

        var username = args.optionalString("username");
        if (username == null || username.isBlank()) {
            return null;
        }

        var domain = args.optionalString("domain");
        var type = args.optionalEnum("type", UserType.class, null);
        if (type == null) {
            type = UserPrincipal.assertCurrent().getType();
        }
        return userManager.getId(username, domain, type).orElseThrow(() -> notFound(username));
    }

    private static void assertOneUserSelector(ToolArguments args) {
        var userId = args.optionalUuid("userId");
        var username = args.optionalString("username");
        if (userId != null && username != null && !username.isBlank()) {
            throw new IllegalArgumentException("Use either 'userId' or 'username', not both");
        }
    }

    private UserEntry requireUser(UUID userId) {
        var user = userDao.get(userId);
        if (user == null) {
            throw notFound(userId);
        }
        return user;
    }

    private static ConcordApplicationException notFound(UUID userId) {
        return new ConcordApplicationException("User not found: " + userId, Response.Status.NOT_FOUND);
    }

    private static ConcordApplicationException notFound(String username) {
        return new ConcordApplicationException("User not found: " + username, Response.Status.NOT_FOUND);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApiKeyCreateResult(
            boolean ok,
            String entity,
            String result,
            String keyId,
            String name,
            String key,
            String expiredAt,
            String userId,
            String username,
            String domain,
            String type) {

        static ApiKeyCreateResult from(CreateApiKeyResponse response, UserEntry user, ApiKeyEntry entry) {
            return new ApiKeyCreateResult(
                    true,
                    "apiKey",
                    response.getResult().name(),
                    response.getId().toString(),
                    response.getName(),
                    response.getKey(),
                    entry != null && entry.getExpiredAt() != null
                            ? entry.getExpiredAt().toString()
                            : null,
                    user.getId().toString(),
                    user.getName(),
                    user.getDomain(),
                    user.getType().name());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApiKeyListResult(
            boolean ok,
            String entity,
            String userId,
            String username,
            String domain,
            String type,
            List<ApiKeyResult> apiKeys) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ApiKeyResult(String keyId, String name, String expiredAt) {

        static ApiKeyResult from(ApiKeyEntry entry) {
            return new ApiKeyResult(
                    entry.getId().toString(),
                    entry.getName(),
                    entry.getExpiredAt() != null ? entry.getExpiredAt().toString() : null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeleteApiKeyResult(boolean ok, String entity, String result, String keyId) {}
}
