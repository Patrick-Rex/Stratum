package com.patrick.stratum.starter.integration;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AccountWarehouseAccess;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证角色仓库范围读写、独立版本、身份保护、输入边界及并发提交。 */
class RoleWarehouseScopeHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private MasterDataRepository masters;
    @Autowired private AccountWarehouseAccess warehouses;
    @Autowired private UnitOfWork work;
    private final List<UUID> ownedRoles = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /** 先移除本测试范围引用再删除独占主数据，父夹具精确清理角色和账号。 */
    @AfterEach
    void cleanWarehouses() {
        for (UUID role : ownedRoles) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role.toString());
        for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
    }

    /**
     * HTTP整组配置按独立版本推进，现有成员立即观察范围变化且保持同一会话有效。
     * @throws Exception HTTP失败
     */
    @Test
    void readsAndReplacesScopesWithIndependentVersionsAndLiveEffects() throws Exception {
        UUID role = role(), allowed = master(MasterData.Kind.WAREHOUSE), excluded = master(MasterData.Kind.WAREHOUSE);
        var initial = read(role); assertEquals("CUSTOM", initial.path("scopeType").asText()); assertEquals(0, initial.path("version").asLong());
        assertTrue(initial.path("warehouseIds").isEmpty());
        String username = "scope-http-member-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password);
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        var session = login(username, password); assertFalse(warehouses.permits(user, allowed, "scope-http"));
        assertEquals(1, update(role, 0, "CUSTOM", Set.of(allowed)));
        assertEquals(Set.of(allowed.toString()), read(role).path("warehouseIds").valueStream().map(JsonNode::asText).collect(Collectors.toSet()));
        assertTrue(warehouses.permits(user, allowed, "scope-http")); assertFalse(warehouses.permits(user, excluded, "scope-http"));
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 0, "name", "独立API版本", "permissions", Set.of())), 200);
        assertEquals(1, read(role).path("version").asLong());
        assertEquals(2, update(role, 1, "ALL", Set.of())); assertTrue(warehouses.permits(user, excluded, "scope-http"));
        assertEquals(2, update(role, 2, "ALL", Set.of()));
        assertEquals(409, request("PUT", path(role), accessToken(), input(1, "ALL", Set.of())).statusCode());
        assertEquals(3, update(role, 2, "CUSTOM", Set.of())); assertFalse(warehouses.permits(user, allowed, "scope-http"));
        data(request("GET", "/auth/me", session.accessToken(), null), 200);
        assertEquals(3, read(role).path("version").asLong()); assertTrue(read(role).path("warehouseIds").isEmpty());
    }

    /**
     * 匿名、Refresh、普通及已撤回管理员资格拒绝；未知角色404，管理员固定ALL不能修改。
     * @throws Exception HTTP失败
     */
    @Test
    void enforcesLiveAdministratorEligibilityAndProtectedScope() throws Exception {
        UUID role = role(); String username = "scope-http-reader-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        createAccount(username, password); var ordinary = login(username, password);
        for (String method : List.of("GET", "PUT")) {
            Object body = method.equals("PUT") ? input(0, "ALL", Set.of()) : null;
            assertEquals(401, request(method, path(role), null, body).statusCode());
            assertEquals(401, request(method, path(role), refreshToken(), body).statusCode());
            assertEquals(403, request(method, path(role), ordinary.accessToken(), body).statusCode());
        }
        String administrator = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", subjectId(), administrator);
        try {
            assertEquals(403, request("GET", path(role), accessToken(), null).statusCode());
            assertEquals(403, request("PUT", path(role), accessToken(), input(0, "ALL", Set.of())).statusCode());
        } finally { jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", subjectId(), administrator); }
        UUID missing = UUID.randomUUID();
        assertEquals(404, request("GET", path(missing), accessToken(), null).statusCode());
        assertEquals(404, request("PUT", path(missing), accessToken(), input(0, "ALL", Set.of())).statusCode());
        var admin = read(UUID.fromString(administrator)); assertEquals("ALL", admin.path("scopeType").asText());
        assertEquals(400, request("PUT", path(UUID.fromString(administrator)), accessToken(), input(admin.path("version").asLong(), "ALL", Set.of())).statusCode());
        assertEquals(0, read(role).path("version").asLong());
    }

    /**
     * 非法类型、缺失字段、负版本、空或超量UUID与引用错误拒绝，版本协议保持标准错误码。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInvalidInputsAndReferenceConflictsWithoutPartialUpdate() throws Exception {
        UUID role = role(), warehouse = master(MasterData.Kind.WAREHOUSE), material = master(MasterData.Kind.MATERIAL);
        var oversized = IntStream.range(0, 1025).mapToObj(index -> UUID.randomUUID().toString()).toList();
        for (Object body : List.of(
                Map.of("scopeType", "CUSTOM", "warehouseIds", Set.of()),
                Map.of("version", 0, "warehouseIds", Set.of()),
                Map.of("version", 0, "scopeType", "CUSTOM"),
                input(-1, "CUSTOM", Set.of()), input(0, "ORG", Set.of()), input(0, "all", Set.of()), input(0, "ALL ", Set.of()),
                input(0, "ALL", Set.of(warehouse)), input(0, "CUSTOM", oversized), input(0, "CUSTOM", List.of("bad")),
                input(0, "CUSTOM", java.util.Collections.singletonList(null)))) {
            var rejected = request("PUT", path(role), accessToken(), body); assertEquals(400, rejected.statusCode());
            assertEquals(40000, json.readTree(rejected.body()).path("code").asInt());
        }
        for (UUID invalid : Set.of(UUID.randomUUID(), material)) {
            assertEquals(409, request("PUT", path(role), accessToken(), input(0, "CUSTOM", Set.of(invalid))).statusCode());
        }
        for (String method : List.of("GET", "PUT")) {
            var rejected = request(method, path(role), accessToken(), method.equals("PUT") ? input(0, "CUSTOM", Set.of()) : null, "2");
            assertEquals(400, rejected.statusCode()); assertEquals(40001, json.readTree(rejected.body()).path("code").asInt());
        }
        var current = read(role); assertEquals(0, current.path("version").asLong()); assertTrue(current.path("warehouseIds").isEmpty());
    }

    /**
     * 两个真实HTTP请求用相同观察版本竞争，只有一份完整配置提交。
     * @throws Exception HTTP或并发任务失败
     */
    @Test
    void commitsOnlyOneConcurrentHttpConfiguration() throws Exception {
        UUID role = role(), first = master(MasterData.Kind.WAREHOUSE), second = master(MasterData.Kind.WAREHOUSE);
        String access = accessToken();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(
                    executor.submit(() -> request("PUT", path(role), access, input(0, "CUSTOM", Set.of(first))).statusCode()),
                    executor.submit(() -> request("PUT", path(role), access, input(0, "CUSTOM", Set.of(second))).statusCode()));
            var results = new ArrayList<Integer>(); for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(status -> status == 200).count()); assertEquals(1, results.stream().filter(status -> status == 409).count());
        }
        var current = read(role); assertEquals(1, current.path("version").asLong());
        var ids = current.path("warehouseIds").valueStream().map(JsonNode::asText).collect(Collectors.toSet());
        assertTrue(Set.of(Set.of(first.toString()), Set.of(second.toString())).contains(ids));
    }

    /**
     * 创建本测试角色并登记范围清理。
     * @return 角色UUID
     * @throws Exception HTTP失败
     */
    private UUID role() throws Exception { UUID role = createRole("scope-http-" + UUID.randomUUID(), "范围HTTP"); ownedRoles.add(role); return role; }

    /**
     * 保存本测试主数据。
     * @param kind 仓库或错误类别物料
     * @return 主数据UUID
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "范围HTTP", kind == MasterData.Kind.MATERIAL ? "件" : null); masterIds.add(value.getId());
        work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 获取角色完整配置并检查公开字段和缓存头。
     * @param role 角色UUID
     * @return 配置数据
     * @throws Exception HTTP失败
     */
    private JsonNode read(UUID role) throws Exception {
        var response = request("GET", path(role), accessToken(), null); var result = data(response, 200); cacheHeaders(response);
        assertEquals(Set.of("id", "version", "scopeType", "warehouseIds"), result.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        assertEquals(role.toString(), result.path("id").asText()); return result;
    }

    /**
     * 配置并核对仅返回已提交标识和独立版本。
     * @param role 目标角色
     * @param version 观察版本
     * @param type 范围类型
     * @param ids 完整指定集合
     * @return 新配置版本
     * @throws Exception HTTP失败
     */
    private long update(UUID role, long version, String type, Set<UUID> ids) throws Exception {
        var response = request("PUT", path(role), accessToken(), input(version, type, ids)); var result = data(response, 200); cacheHeaders(response);
        assertEquals(2, result.size()); assertEquals(role.toString(), result.path("id").asText()); return result.path("version").asLong();
    }

    /**
     * 构造真实输入，包括用于边界测试的集合元素。
     * @param version 观察版本
     * @param type 范围编码
     * @param ids 指定集合
     * @return JSON对象
     */
    private Map<String, Object> input(long version, String type, Object ids) { return Map.of("version", version, "scopeType", type, "warehouseIds", ids); }

    /**
     * 生成单角色配置路径。
     * @param role 角色UUID
     * @return API路径
     */
    private String path(UUID role) { return "/api/auth/roles/" + role + "/warehouse-scope"; }

    /**
     * 核对成功响应的禁止缓存协议。
     * @param response 成功响应
     */
    private void cacheHeaders(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
    }
}
