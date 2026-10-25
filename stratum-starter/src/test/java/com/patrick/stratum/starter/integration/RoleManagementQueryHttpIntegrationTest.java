package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证角色分页、权限关系和可授予目录，统一管理员检查与查询边界。 */
class RoleManagementQueryHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    /**
     * 写接口配置后读接口立即返回名称、版本和精确权限；目录显示与后端登记一致。
     * @throws Exception HTTP失败
     */
    @Test
    void readsRolesGrantsAndTheRegisteredCatalog() throws Exception {
        String prefix = "role-read-" + UUID.randomUUID(); UUID first = createRole(prefix + "-a", "采购只读"); UUID second = createRole(prefix + "-b", "销售只读");
        String listPath = "/api/auth/roles?codeContains=" + prefix + "&pageSize=1&sort=code,asc";
        var page = data(request("GET", listPath, accessToken(), null), 200); assertEquals(2, page.path("total").asLong());
        assertEquals(first.toString(), page.path("items").get(0).path("id").asText());
        assertEquals(second.toString(), data(request("GET", listPath + "&page=2", accessToken(), null), 200).path("items").get(0).path("id").asText());
        String path = "/api/auth/roles/" + first;
        assertEquals(0, data(request("GET", path + "/permissions", accessToken(), null), 200).path("permissions").size());
        var grant = Map.of("code", PermissionCatalog.PURCHASE_READ.permission().code(), "type", "API");
        data(request("PUT", path, accessToken(), Map.of("version", 0, "name", "采购查询", "permissions", List.of(grant))), 200);
        var detailResponse = request("GET", path, accessToken(), null); var detail = data(detailResponse, 200);
        assertEquals("采购查询", detail.path("name").asText()); assertEquals(1, detail.path("version").asLong());
        assertTrue(detailResponse.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        var grants = data(request("GET", path + "/permissions", accessToken(), null), 200);
        assertEquals(1, grants.path("version").asLong()); assertEquals(1, grants.path("permissions").size());
        assertEquals("API", grants.path("permissions").get(0).path("type").asText());
        assertEquals(grant.get("code"), grants.path("permissions").get(0).path("code").asText());
        var filtered = data(request("GET", "/api/auth/roles?codeContains=" + prefix + "&versionFrom=1&versionTo=1", accessToken(), null), 200);
        assertEquals(first.toString(), filtered.path("items").get(0).path("id").asText());
        for (Permission.Type type : Permission.Type.values()) {
            var catalogResponse = request("GET", "/api/auth/permissions?type=" + type + "&pageSize=200&sort=code,asc", accessToken(), null);
            var catalog = data(catalogResponse, 200);
            assertEquals(Arrays.stream(PermissionCatalog.values()).filter(entry -> entry.permission().type() == type).count(), catalog.path("total").asLong());
            for (var entry : catalog.path("items")) {
                assertEquals(type.name(), entry.path("type").asText()); assertFalse(entry.path("name").asText().isBlank());
            }
            assertTrue(catalogResponse.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        }
        assertEquals(404, request("GET", "/api/auth/roles/" + UUID.randomUUID(), accessToken(), null).statusCode());
        assertEquals(404, request("GET", "/api/auth/roles/" + UUID.randomUUID() + "/permissions", accessToken(), null).statusCode());
    }

    /**
     * 所有角色与目录读接口均拒绝匿名、普通账号及已移除管理员，不能以伪造头绕过。
     * @throws Exception HTTP失败
     */
    @Test
    void requiresLiveAdministratorForEveryReadRoute() throws Exception {
        UUID role = createRole("protected-read-" + UUID.randomUUID(), "保护角色");
        String username = "role-reader-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        createAccount(username, password); var ordinary = login(username, password);
        var paths = List.of("/api/auth/roles", "/api/auth/roles/" + role, "/api/auth/roles/" + role + "/permissions", "/api/auth/permissions");
        for (String path : paths) {
            assertEquals(401, request("GET", path, null, null).statusCode());
            assertEquals(403, request("GET", path, ordinary.accessToken(), null).statusCode());
        }
        String administrator = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", subjectId(), administrator);
        try { for (String path : paths) assertEquals(403, request("GET", path, accessToken(), null).statusCode()); }
        finally { jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", subjectId(), administrator); }
    }

    /**
     * 类型大小写、未知和重复字段、分页和版本区间严格验证，文本注入只作为字面条件。
     * @throws Exception HTTP失败
     */
    @Test
    void validatesCatalogAndRoleQueryInputs() throws Exception {
        for (String suffix : List.of("type=api", "type=OTHER", "type=API&type=FEAT", "userId=" + subjectId(), "sort=permissions,asc", "pageSize=201")) {
            assertEquals(400, request("GET", "/api/auth/permissions?" + suffix, accessToken(), null).statusCode());
        }
        for (String suffix : List.of("sort=members,asc", "enabled=true", "versionFrom=1", "versionFrom=2&versionTo=1", "page=0")) {
            assertEquals(400, request("GET", "/api/auth/roles?" + suffix, accessToken(), null).statusCode());
        }
        for (String path : List.of("/api/auth/roles", "/api/auth/permissions")) {
            assertEquals(400, request("GET", path, accessToken(), null, "2").statusCode());
            assertEquals(0, data(request("GET", path + "?codeContains=%27%20OR%201%3D1%20--", accessToken(), null), 200).path("total").asLong());
        }
    }
}
