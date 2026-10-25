package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.masterdata.MasterDataApplicationService;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.masterdata.MasterData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证四类主数据读写矩阵、功能权限隔离和幂等重放前的实时鉴权。 */
class MasterDataAuthorizationHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private VersionedCache cache;
    @Autowired private StringRedisTemplate redis;
    private final String trace = UUID.randomUUID().toString();
    private final List<String> ids = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private UUID role;
    private long roleVersion;
    private String token;

    /** 清理本测试随机主数据、缓存、事件和回执；父夹具处理账号及角色。 */
    @AfterEach
    void cleanBusiness() {
        jdbc.update("DELETE FROM outbox_event WHERE trace_id=?", trace);
        for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key=?", key);
        for (String id : ids) {
            jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
            String cacheKey = MasterDataApplicationService.cacheKey(id);
            cache.invalidate(cacheKey, 100, MasterDataApplicationService.CACHE_POLICY);
            redis.delete(List.of(cacheKey, "null:" + cacheKey.substring(4), "version:" + cacheKey.substring(4)));
        }
    }

    /**
     * 四类列表和详情必须各自授权；同一Access在角色切换后失去上一类别访问权。
     * @throws Exception HTTP失败
     */
    @Test
    void enforcesExactReadPermissionForEveryMasterKind() throws Exception {
        prepareUser(); var resources = new EnumMap<MasterData.Kind, String>(MasterData.Kind.class);
        for (var kind : MasterData.Kind.values()) resources.put(kind, create(kind, accessToken(), UUID.randomUUID().toString()).path("id").asText());
        for (var allowed : MasterData.Kind.values()) {
            grant(Set.of(permission(allowed, "read", Permission.Type.API)));
            for (var actual : MasterData.Kind.values()) {
                int expected = actual == allowed ? 200 : 403;
                assertEquals(expected, erp("GET", path(actual), token, null, null).statusCode());
                assertEquals(expected, erp("GET", path(actual) + "/" + resources.get(actual), token, null, null).statusCode());
            }
            String rejectedKey = UUID.randomUUID().toString();
            assertEquals(403, erp("POST", path(allowed), token, payload(allowed), rejectedKey).statusCode());
            assertEquals(0, receipts(rejectedKey));
        }
    }

    /**
     * 写权限不隐含读取，不能修改其他类别；撤权后旧创建回执和删除重放也必须拒绝。
     * @throws Exception HTTP失败
     */
    @Test
    void protectsWritesAndReplaysBeforeAnyBusinessSideEffect() throws Exception {
        prepareUser();
        for (var allowed : MasterData.Kind.values()) {
            grant(Set.of(permission(allowed, "write", Permission.Type.API)));
            for (var other : MasterData.Kind.values()) {
                if (other == allowed) continue;
                String rejectedKey = UUID.randomUUID().toString(); var input = payload(other);
                assertEquals(403, erp("POST", path(other), token, input, rejectedKey).statusCode());
                assertEquals(403, erp("PATCH", path(other) + "/" + UUID.randomUUID(), token, Map.of("version", 0, "name", "越权"), rejectedKey).statusCode());
                assertEquals(403, erp("DELETE", path(other) + "/" + UUID.randomUUID() + "?version=0", token, null, rejectedKey).statusCode());
                assertEquals(0, receipts(rejectedKey));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_master_data WHERE code=?", Integer.class, input.get("code")));
            }
            String createKey = UUID.randomUUID().toString(); var input = payload(allowed);
            var created = erp("POST", path(allowed), token, input, createKey); assertEquals(201, created.statusCode());
            String id = json.readTree(created.body()).path("data").path("id").asText();
            assertEquals(403, erp("GET", path(allowed) + "/" + id, token, null, null).statusCode());
            var renamed = erp("PATCH", path(allowed) + "/" + id, token, Map.of("version", 0, "name", "已改名"), UUID.randomUUID().toString());
            assertEquals(200, renamed.statusCode());
            long version = json.readTree(renamed.body()).path("data").path("version").asLong();
            int before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id=?", Integer.class, trace);
            grant(Set.of());
            assertEquals(403, erp("POST", path(allowed), token, input, createKey).statusCode());
            assertEquals(403, erp("PATCH", path(allowed) + "/" + id, token, Map.of("version", version, "name", "拒绝修改"), UUID.randomUUID().toString()).statusCode());
            String deleteKey = UUID.randomUUID().toString();
            assertEquals(403, erp("DELETE", path(allowed) + "/" + id + "?version=" + version, token, null, deleteKey).statusCode());
            assertEquals(0, receipts(deleteKey));
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id=?", Integer.class, trace));
            assertEquals("已改名", jdbc.queryForObject("SELECT name FROM erp_master_data WHERE id=?", String.class, id));
            grant(Set.of(permission(allowed, "write", Permission.Type.API)));
            assertEquals(204, erp("DELETE", path(allowed) + "/" + id + "?version=" + version, token, null, deleteKey).statusCode());
            grant(Set.of());
            assertEquals(403, erp("DELETE", path(allowed) + "/" + id + "?version=" + version, token, null, deleteKey).statusCode());
        }
    }

    /**
     * 普通账号默认无权限，全部FEAT也不能放行业务；匿名请求仍按401处理。
     * @throws Exception HTTP失败
     */
    @Test
    void deniesDefaultAndFeatureOnlyAccounts() throws Exception {
        prepareUser();
        for (var kind : MasterData.Kind.values()) assertEquals(403, erp("GET", path(kind), token, null, null).statusCode());
        grant(Set.of(new Permission("erp.material", Permission.Type.FEAT), new Permission("erp.customer", Permission.Type.FEAT),
                new Permission("erp.supplier", Permission.Type.FEAT), new Permission("erp.warehouse", Permission.Type.FEAT)));
        for (var kind : MasterData.Kind.values()) {
            assertEquals(403, erp("GET", path(kind), token, null, null).statusCode());
            assertEquals(403, erp("POST", path(kind), token, payload(kind), UUID.randomUUID().toString()).statusCode());
            assertEquals(401, erp("GET", path(kind), null, null, null).statusCode());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id=?", Integer.class, trace));
    }

    /**
     * 创建仅绑定空角色的普通账号，成员分配之后才签发测试会话。
     * @throws Exception HTTP失败
     */
    private void prepareUser() throws Exception {
        String username = "master-rbac-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); role = createRole("master-rbac-" + UUID.randomUUID(), "主数据权限");
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        token = login(username, password).accessToken();
    }

    /**
     * 通过生产管理HTTP整组配置权限，不重签普通账号Access。
     * @param grants 新权限集合
     * @throws Exception HTTP失败
     */
    private void grant(Set<Permission> grants) throws Exception {
        var result = data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", roleVersion, "name", "主数据权限", "permissions", grants)), 200);
        roleVersion = result.path("version").asLong();
    }

    /**
     * 以公开编码契约构造期望权限，不复用待测的类别映射策略。
     * @param kind 业务类别
     * @param action 读或写动作
     * @param type 权限类型
     * @return 精确测试授权
     */
    private Permission permission(MasterData.Kind kind, String action, Permission.Type type) {
        return new Permission("erp." + kind.name().toLowerCase(Locale.ROOT) + "." + action, type);
    }

    /**
     * 生成四类有效创建输入。
     * @param kind 类别
     * @return 随机编码输入
     */
    private Map<String, String> payload(MasterData.Kind kind) {
        return kind == MasterData.Kind.MATERIAL ? Map.of("code", UUID.randomUUID().toString(), "name", "权限物料", "baseUnit", "件")
                : Map.of("code", UUID.randomUUID().toString(), "name", "权限主数据");
    }

    /**
     * 创建本测试主数据并返回结果。
     * @param kind 类别
     * @param access 已授权Access
     * @param key 随机幂等键
     * @return 创建结果
     * @throws Exception HTTP失败
     */
    private JsonNode create(MasterData.Kind kind, String access, String key) throws Exception {
        var response = erp("POST", path(kind), access, payload(kind), key); assertEquals(201, response.statusCode());
        return json.readTree(response.body()).path("data");
    }

    /**
     * 获取类别路由。
     * @param kind 主数据类别
     * @return HTTP路径
     */
    private String path(MasterData.Kind kind) { return "/api/erp/masters/" + kind; }

    /**
     * 核对仅本测试随机键是否落盘，拒绝操作应为零。
     * @param key 幂等键
     * @return 回执数量
     */
    private int receipts(String key) { return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key=?", Integer.class, key); }

    /**
     * 有界ERP请求，记录自己的清理范围并始终自报管理员头以验证它不能授权。
     * @param method HTTP方法
     * @param path 业务路径
     * @param access 可空Access
     * @param payload 可空JSON
     * @param key 可空幂等键
     * @return 仅供断言的HTTP响应
     * @throws Exception HTTP失败
     */
    private HttpResponse<String> erp(String method, String path, String access, Object payload, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Trace-Id", trace).header("X-User-Id", subjectId());
        if (access != null) builder.header("Authorization", "Bearer " + access);
        if (key != null) { builder.header("Idempotency-Key", key); keys.add(key); }
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow());
            if (response.statusCode() != 204) {
                var body = json.readTree(response.body()); assertEquals(trace, body.path("traceId").asText());
                if (method.equals("POST") && response.statusCode() == 201) ids.add(body.path("data").path("id").asText());
                if (response.statusCode() == 403) assertEquals(40300, body.path("code").asInt());
            }
            return response;
        }
    }
}
