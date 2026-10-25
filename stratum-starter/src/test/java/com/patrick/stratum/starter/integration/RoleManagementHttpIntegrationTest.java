package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证角色创建、精确权限配置及当前管理员、目录和版本约束。 */
class RoleManagementHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private AccountPermissions permissions;
    @Autowired private AuthSessionApplicationService authentication;

    /**
     * 空权限角色经配置和成员分配产生精确权限，角色清空立即可见且不依赖新JWT。
     * @throws Exception HTTP失败
     */
    @Test
    void createsAndConfiguresLiveRolePermissions() throws Exception {
        String code = "role-http-" + UUID.randomUUID(); UUID role = createRole(code, "库存查询");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_permission WHERE role_id=?", Integer.class, role.toString()));
        assertEquals(409, request("POST", "/api/auth/roles", accessToken(), Map.of("code", code, "name", "重复角色")).statusCode());
        var grants = List.of(grant(PermissionCatalog.INVENTORY_READ), grant(PermissionCatalog.INVENTORY_FEATURE));
        var configured = request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 0, "name", "库存只读", "permissions", grants));
        assertEquals(1, data(configured, 200).path("version").asLong());
        assertTrue(configured.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        String username = "role-member-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password);
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(role))), 200);
        var session = login(username, password);
        assertEquals(Set.of(PermissionCatalog.INVENTORY_READ.permission(), PermissionCatalog.INVENTORY_FEATURE.permission()),
                permissions.findByUserId(user, "role-http").orElseThrow().permissions());
        assertEquals(2, data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 1, "name", "无授权", "permissions", List.of())), 200).path("version").asLong());
        assertTrue(permissions.findByUserId(user, "role-http").orElseThrow().permissions().isEmpty());
        assertEquals(1, authentication.authenticateAccess(session.accessToken()).version());
        assertEquals(code, jdbc.queryForObject("SELECT code FROM auth_role WHERE id=?", String.class, role.toString()));
    }

    /**
     * 当前管理员之外的身份不能创建或配置，自报主体字段不改变判定。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsAnonymousAndOrdinaryRoleManagement() throws Exception {
        String username = "ordinary-role-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        createAccount(username, password); var ordinary = login(username, password);
        UUID role = createRole("auth-role-" + UUID.randomUUID(), "受保护角色");
        var creation = Map.of("code", "forged-" + UUID.randomUUID(), "name", "越权角色", "userId", subjectId());
        assertEquals(401, request("POST", "/api/auth/roles", null, creation).statusCode());
        assertEquals(401, request("POST", "/api/auth/roles", refreshToken(), creation).statusCode());
        assertEquals(403, request("POST", "/api/auth/roles", ordinary.accessToken(), creation).statusCode());
        var config = Map.of("version", 0, "name", "越权配置", "permissions", List.of(), "userId", subjectId());
        assertEquals(403, request("PUT", "/api/auth/roles/" + role, ordinary.accessToken(), config).statusCode());
        assertEquals(400, request("PUT", "/api/auth/roles/" + role, accessToken(), config, "2").statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, role.toString()));
    }

    /**
     * 目录外权限、错误类型、保留管理员角色、过期版本和未知目标按明确协议拒绝。
     * @throws Exception HTTP失败
     */
    @Test
    void protectsCatalogReservedRoleAndObservedVersion() throws Exception {
        UUID role = createRole("catalog-role-" + UUID.randomUUID(), "原名称");
        assertEquals(400, request("POST", "/api/auth/roles", accessToken(), Map.of("code", "administrator", "name", "覆盖管理员")).statusCode());
        for (var grant : List.of(Map.of("code", "unknown.permission", "type", "API"), Map.of("code", "erp.material.read", "type", "FEAT"))) {
            assertEquals(400, request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 0, "name", "非法权限", "permissions", List.of(grant))).statusCode());
        }
        String administrator = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        long adminVersion = jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, administrator);
        assertEquals(400, request("PUT", "/api/auth/roles/" + administrator, accessToken(), Map.of("version", adminVersion, "name", "改写管理员", "permissions", List.of())).statusCode());
        assertEquals(409, request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 1, "name", "旧版本", "permissions", List.of())).statusCode());
        assertEquals(404, request("PUT", "/api/auth/roles/" + UUID.randomUUID(), accessToken(), Map.of("version", 0, "name", "不存在", "permissions", List.of())).statusCode());
        assertEquals("原名称", jdbc.queryForObject("SELECT name FROM auth_role WHERE id=?", String.class, role.toString()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, role.toString()));
    }

    /**
     * 字段、嵌套权限与集合边界在HTTP入口验证，不允许空元素或非法枚举进入应用。
     * @throws Exception HTTP失败
     */
    @Test
    void validatesNestedPermissionsAndNames() throws Exception {
        UUID role = createRole("input-role-" + UUID.randomUUID(), "合法名称"); String path = "/api/auth/roles/" + role;
        assertEquals(400, request("POST", "/api/auth/roles", accessToken(), Map.of("code", "Bad Code", "name", "名称")).statusCode());
        for (String name : List.of(" ", "bad\nname", "名".repeat(129))) {
            assertEquals(400, request("PUT", path, accessToken(), Map.of("version", 0, "name", name, "permissions", List.of())).statusCode());
        }
        assertEquals(400, request("PUT", path, accessToken(), Map.of("name", "缺少版本", "permissions", List.of())).statusCode());
        assertEquals(400, request("PUT", path, accessToken(), Map.of("version", 0, "name", "空元素", "permissions", Arrays.asList((Object) null))).statusCode());
        for (var grant : List.of(Map.of("code", "erp.material.read", "type", "api"), Map.of("code", "invalid code", "type", "API"), Map.of("type", "API"))) {
            assertEquals(400, request("PUT", path, accessToken(), Map.of("version", 0, "name", "非法字段", "permissions", List.of(grant))).statusCode());
        }
        var overflow = IntStream.range(0, 1025).mapToObj(index -> Map.of("code", "bound." + index, "type", "API")).toList();
        assertEquals(400, request("PUT", path, accessToken(), Map.of("version", 0, "name", "超界", "permissions", overflow)).statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, role.toString()));
    }

    /**
     * 将权威目录项转换为HTTP输入形状。
     * @param entry 已登记权限
     * @return 编码和精确类型
     */
    private static Map<String, String> grant(PermissionCatalog entry) {
        return Map.of("code", entry.permission().code(), "type", entry.permission().type().name());
    }
}
