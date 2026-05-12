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
import com.walmartlabs.concord.server.org.OrganizationEntry;
import com.walmartlabs.concord.server.sdk.ConcordApplicationException;
import com.walmartlabs.concord.server.security.Roles;
import com.walmartlabs.concord.server.security.UnauthorizedException;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.user.RoleEntry;
import com.walmartlabs.concord.server.user.UserDao;
import com.walmartlabs.concord.server.user.UserEntry;
import com.walmartlabs.concord.server.user.UserManager;
import com.walmartlabs.concord.server.user.UserType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

class ConcordUserTools {

    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final int MAX_LIST_LIMIT = positiveIntegerProperty("concord.mcp.maxUserListLimit", 1000);

    private final UserManager userManager;
    private final UserDao userDao;

    @Inject
    ConcordUserTools(UserManager userManager, UserDao userDao) {
        this.userManager = userManager;
        this.userDao = userDao;
    }

    UserResult createUser(Map<String, Object> arguments) {
        assertAdmin();

        var args = new ToolArguments(arguments);
        var username = args.requireString("username");
        var domain = args.optionalString("domain");
        var type = creatableUserType(args);
        var roles = optionalRoles(arguments, "roles");
        assertNoAdminRole(roles);
        var disabled = optionalBoolean(arguments, "disabled");

        var existingUserId = userManager.getId(username, domain, type).orElse(null);
        if (existingUserId == null) {
            var created = userManager.create(
                    username, domain, args.optionalString("displayName"), args.optionalString("email"), type, roles);
            if (Boolean.TRUE.equals(disabled)) {
                userManager.disable(created.getId());
                created = requireUser(created.getId());
            }
            return UserResult.from(OperationResult.CREATED, created);
        }

        var existing = requireUser(existingUserId);
        var updated = userManager
                .update(
                        existingUserId,
                        args.optionalString("displayName"),
                        args.optionalString("email"),
                        type,
                        disabled != null ? disabled : existing.isDisabled(),
                        roles)
                .orElseThrow(() -> notFound(existingUserId));
        return UserResult.from(OperationResult.UPDATED, updated);
    }

    UserResult getUser(Map<String, Object> arguments) {
        assertAdmin();

        var user = resolveUser(new ToolArguments(arguments));
        return UserResult.from(null, user);
    }

    UserListResult listUsers(Map<String, Object> arguments) {
        assertAdmin();

        var args = new ToolArguments(arguments);
        var offset = args.optionalInteger("offset", 0);
        var limit = args.optionalInteger("limit", DEFAULT_LIST_LIMIT);
        if (offset < 0) {
            throw new IllegalArgumentException("'offset' must be greater than or equal to 0");
        }
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("'limit' must be between 1 and " + MAX_LIST_LIMIT);
        }

        var users = userDao.list(args.optionalString("filter"), offset, limit);
        return new UserListResult(
                true,
                "users",
                offset,
                limit,
                users.stream().map(user -> UserResult.from(null, user)).toList());
    }

    UserResult setUserRoles(Map<String, Object> arguments) {
        assertAdmin();

        var userId = resolveUser(new ToolArguments(arguments)).getId();
        var roles = optionalRoles(arguments, "roles");
        if (roles == null) {
            throw new IllegalArgumentException("'roles' is required");
        }
        assertNoAdminRole(roles);

        userDao.updateRoles(userId, roles);
        return UserResult.from(OperationResult.UPDATED, requireUser(userId));
    }

    UserResult disableUser(Map<String, Object> arguments) {
        assertAdmin();

        var args = new ToolArguments(arguments);
        var userId = resolveUser(args).getId();
        if (args.optionalBoolean("permanently", false)) {
            userManager.permanentlyDisable(userId);
        } else {
            userManager.disable(userId);
        }
        return UserResult.from(OperationResult.UPDATED, requireUser(userId));
    }

    UserResult enableUser(Map<String, Object> arguments) {
        assertAdmin();

        var userId = resolveUser(new ToolArguments(arguments)).getId();
        userManager.enable(userId);
        return UserResult.from(OperationResult.UPDATED, requireUser(userId));
    }

    DeleteUserResult deleteUser(Map<String, Object> arguments) {
        assertAdmin();

        var user = resolveUser(new ToolArguments(arguments));
        userManager.delete(user.getId());
        return new DeleteUserResult(
                true, "user", OperationResult.DELETED.name(), user.getId().toString(), user.getName());
    }

    private UserEntry resolveUser(ToolArguments args) {
        var userId = args.optionalUuid("userId");
        var username = args.optionalString("username");
        if (userId != null && username != null && !username.isBlank()) {
            throw new IllegalArgumentException("Use either 'userId' or 'username', not both");
        }
        if (userId != null) {
            return requireUser(userId);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Either 'userId' or 'username' is required");
        }

        var domain = args.optionalString("domain");
        var type = args.optionalEnum("type", UserType.class, null);
        var id = userManager.getId(username, domain, type).orElseThrow(() -> notFound(username));
        return requireUser(id);
    }

    private UserEntry requireUser(UUID userId) {
        var user = userDao.get(userId);
        if (user == null) {
            throw notFound(userId);
        }
        return user;
    }

    private static UserType creatableUserType(ToolArguments args) {
        var type = args.optionalEnum("type", UserType.class, null);
        if (type == null) {
            type = UserPrincipal.assertCurrent().getType();
        }
        if (UserType.SSO.equals(type)) {
            throw new ConcordApplicationException(
                    "User type SSO cannot be created or updated", Response.Status.BAD_REQUEST);
        }
        return type;
    }

    private static Set<String> optionalRoles(Map<String, Object> arguments, String name) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
            return null;
        }
        var list = new ToolArguments(arguments).optionalStringList(name);
        return new LinkedHashSet<>(list);
    }

    private static Boolean optionalBoolean(Map<String, Object> arguments, String name) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
            return null;
        }
        var value = arguments.get(name);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("'" + name + "' must be a boolean");
    }

    private static void assertAdmin() {
        if (!Roles.isAdmin()) {
            throw new UnauthorizedException("Only Concord administrators can manage users");
        }
    }

    private static void assertNoAdminRole(Set<String> roles) {
        if (roles != null && roles.contains(Roles.ADMIN)) {
            throw new UnauthorizedException("MCP user-management tools cannot assign Concord administrator roles");
        }
    }

    private static ConcordApplicationException notFound(UUID userId) {
        return new ConcordApplicationException("User not found: " + userId, Response.Status.NOT_FOUND);
    }

    private static ConcordApplicationException notFound(String username) {
        return new ConcordApplicationException("User not found: " + username, Response.Status.NOT_FOUND);
    }

    private static int positiveIntegerProperty(String name, int defaultValue) {
        var value = Integer.getInteger(name, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserListResult(boolean ok, String entity, int offset, int limit, List<UserResult> users) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeleteUserResult(boolean ok, String entity, String result, String userId, String username) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserResult(
            boolean ok,
            String entity,
            String result,
            String userId,
            String username,
            String domain,
            String displayName,
            String email,
            String type,
            boolean disabled,
            boolean permanentlyDisabled,
            String disabledDate,
            List<RoleResult> roles,
            List<OrganizationResult> organizations) {

        static UserResult from(OperationResult result, UserEntry user) {
            return new UserResult(
                    true,
                    "user",
                    result != null ? result.name() : null,
                    user.getId().toString(),
                    user.getName(),
                    user.getDomain(),
                    user.getDisplayName(),
                    user.getEmail(),
                    user.getType().name(),
                    user.isDisabled(),
                    user.isPermanentlyDisabled(),
                    user.getDisabledDate() != null ? user.getDisabledDate().toString() : null,
                    roles(user.getRoles()),
                    organizations(user.getOrgs()));
        }

        private static List<RoleResult> roles(Set<RoleEntry> roles) {
            return roles == null
                    ? null
                    : roles.stream()
                            .map(role -> new RoleResult(
                                    role.getId().toString(), role.getName(), List.copyOf(role.getPermissions())))
                            .toList();
        }

        private static List<OrganizationResult> organizations(Set<OrganizationEntry> orgs) {
            return orgs == null
                    ? null
                    : orgs.stream()
                            .map(org -> new OrganizationResult(org.getId().toString(), org.getName()))
                            .toList();
        }
    }

    record RoleResult(String roleId, String name, List<String> permissions) {}

    record OrganizationResult(String orgId, String name) {}
}
