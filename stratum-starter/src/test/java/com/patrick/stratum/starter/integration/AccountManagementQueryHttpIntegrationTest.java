package com.patrick.stratum.starter.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证管理员账号分页、详情与角色关系，严格解析查询参数且不泄漏凭据。 */
class AccountManagementQueryHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    /**
     * 经写接口建夹具后验证分页、布尔状态、版本区间与完整角色关系，结果始终无凭据。
     * @throws Exception HTTP失败
     */
    @Test
    void readsAccountPagesDetailsAndRolesWithoutCredentials() throws Exception {
        String prefix = "read-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID first = createAccount(prefix + "-a", password); UUID second = createAccount(prefix + "-b", password);
        data(request("PATCH", "/api/auth/accounts/" + second + "/status", accessToken(), Map.of("version", 0, "enabled", false)), 200);
        var page = data(request("GET", "/api/auth/accounts?usernameContains=" + prefix + "&pageSize=1&sort=username,asc", accessToken(), null), 200);
        assertEquals(2, page.path("total").asLong()); assertEquals(first.toString(), page.path("items").get(0).path("id").asText());
        var next = data(request("GET", "/api/auth/accounts?usernameContains=" + prefix + "&pageSize=1&page=2&sort=username,asc", accessToken(), null), 200);
        assertEquals(second.toString(), next.path("items").get(0).path("id").asText());
        for (String enabled : List.of("true", "false")) {
            var filtered = data(request("GET", "/api/auth/accounts?usernameContains=" + prefix + "&enabled=" + enabled, accessToken(), null), 200);
            assertEquals(1, filtered.path("total").asLong()); assertEquals(Boolean.parseBoolean(enabled), filtered.path("items").get(0).path("enabled").asBoolean());
        }
        var changed = data(request("GET", "/api/auth/accounts?usernameContains=" + prefix + "&versionFrom=1&versionTo=1", accessToken(), null), 200);
        assertEquals(second.toString(), changed.path("items").get(0).path("id").asText());
        var response = request("GET", "/api/auth/accounts/" + first, accessToken(), null);
        var detail = data(response, 200); assertEquals(4, detail.size()); assertTrue(detail.path("enabled").asBoolean());
        assertFalse(response.body().contains(password)); assertFalse(response.body().contains("passwordHash")); assertFalse(response.body().contains("bootstrap"));
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(0, data(request("GET", "/api/auth/accounts/" + first + "/roles", accessToken(), null), 200).path("roleIds").size());
        UUID role = createRole("read-role-" + UUID.randomUUID(), "读取角色");
        data(request("PUT", "/api/auth/accounts/" + first + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(role))), 200);
        var member = data(request("GET", "/api/auth/accounts/" + first + "/roles", accessToken(), null), 200);
        assertEquals(1, member.path("version").asLong()); assertEquals(role.toString(), member.path("roleIds").get(0).asText());
        assertEquals(404, request("GET", "/api/auth/accounts/" + UUID.randomUUID(), accessToken(), null).statusCode());
        assertEquals(404, request("GET", "/api/auth/accounts/" + UUID.randomUUID() + "/roles", accessToken(), null).statusCode());
    }

    /**
     * 三个读端点均要求当前管理员，普通账号即使查询自己并伪造身份头也会被拒绝。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsUntrustedAndRemovedAdministratorReaders() throws Exception {
        String username = "reader-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID id = createAccount(username, password); var ordinary = login(username, password);
        var paths = List.of("/api/auth/accounts", "/api/auth/accounts/" + id, "/api/auth/accounts/" + id + "/roles");
        for (String path : paths) {
            assertEquals(401, request("GET", path, null, null).statusCode());
            assertEquals(401, request("GET", path, refreshToken(), null).statusCode());
            assertEquals(403, request("GET", path, ordinary.accessToken(), null).statusCode());
        }
        String role = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", subjectId(), role);
        try { for (String path : paths) assertEquals(403, request("GET", path, accessToken(), null).statusCode()); }
        finally { jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", subjectId(), role); }
    }

    /**
     * 不接受宽松布尔、重复参数、未知字段、非法分页/排序与不完整区间，防止静默改变筛选语义。
     * @throws Exception HTTP失败
     */
    @Test
    void strictlyValidatesQueryParameters() throws Exception {
        for (String suffix : List.of("enabled=yes", "enabled=1", "enabled=True", "enabled=", "enabled=false&enabled=true",
                "page=0", "pageSize=201", "sort=passwordHash,asc", "userId=" + subjectId(), "versionFrom=1", "versionFrom=2&versionTo=1", "id=1-1-1-1-1")) {
            assertEquals(400, request("GET", "/api/auth/accounts?" + suffix, accessToken(), null).statusCode());
        }
        assertEquals(400, request("GET", "/api/auth/accounts", accessToken(), null, "2").statusCode());
        var literal = data(request("GET", "/api/auth/accounts?usernameContains=%27%20OR%201%3D1%20--", accessToken(), null), 200);
        assertEquals(0, literal.path("total").asLong());
    }
}
