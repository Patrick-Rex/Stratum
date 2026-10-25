package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/** 本人功能权限通过真实HTTP验证身份绑定、实时授权、撤销和公开文档。 */
class CurrentAccountHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @LocalServerPort private int port;

    /**
     * 初始管理员获得目录功能，普通账号为空；相同Access即时看到角色功能变更，不混入API权限。
     * @throws Exception HTTP失败
     */
    @Test
    void returnsOnlyCurrentAccountFeaturesAndObservesLiveRoleChanges() throws Exception {
        var administrator = me(accessToken());
        assertEquals(subjectId(), administrator.path("userId").asText());
        assertEquals(PermissionCatalog.permissions().stream().filter(permission -> permission.type() == Permission.Type.FEAT)
                .map(Permission::code).collect(Collectors.toSet()), features(administrator));
        String username = "me-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); var ordinary = login(username, password);
        assertTrue(features(me(ordinary.accessToken())).isEmpty());
        var forged = data(request("GET", "/auth/me?userId=" + subjectId(), ordinary.accessToken(), null), 200);
        assertEquals(user.toString(), forged.path("userId").asText());
        UUID role = createRole("me-" + UUID.randomUUID(), "本人功能");
        configure(role, 0, Set.of(PermissionCatalog.MATERIAL_FEATURE.permission(), PermissionCatalog.INVENTORY_FEATURE.permission(), PermissionCatalog.INVENTORY_READ.permission()));
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        assertEquals(401, request("GET", "/auth/me", ordinary.accessToken(), null).statusCode());
        var session = login(username, password);
        var granted = me(session.accessToken());
        assertEquals(user.toString(), granted.path("userId").asText());
        assertEquals(List.of("erp.inventory", "erp.material"), granted.path("featurePermissions").valueStream().map(JsonNode::asText).toList());
        configure(role, 1, Set.of(PermissionCatalog.INVENTORY_READ.permission()));
        assertTrue(features(me(session.accessToken())).isEmpty());
    }

    /**
     * 匿名、Refresh、撤销与停用账号均拒绝，重新启用不复活旧令牌，版本错误保留标准语义。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInvalidIdentityAndUnsupportedVersion() throws Exception {
        assertEquals(401, request("GET", "/auth/me", null, null).statusCode());
        assertEquals(401, request("GET", "/auth/me", refreshToken(), null).statusCode());
        var unsupported = request("GET", "/auth/me", accessToken(), null, "2");
        assertEquals(400, unsupported.statusCode()); assertEquals(40001, json.readTree(unsupported.body()).path("code").asInt());
        String username = "disabled-me-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); var session = login(username, password);
        data(request("PATCH", "/api/auth/accounts/" + user + "/status", accessToken(), Map.of("version", 0, "enabled", false)), 200);
        assertEquals(401, request("GET", "/auth/me", session.accessToken(), null).statusCode());
        data(request("PATCH", "/api/auth/accounts/" + user + "/status", accessToken(), Map.of("version", 1, "enabled", true)), 200);
        assertEquals(401, request("GET", "/auth/me", session.accessToken(), null).statusCode());
        var active = login(username, password); assertEquals(user.toString(), me(active.accessToken()).path("userId").asText());
        data(request("POST", "/auth/logout", active.accessToken(), null), 200);
        assertEquals(401, request("GET", "/auth/me", active.accessToken(), null).statusCode());
    }

    /**
     * 公开文档只描述本人展示模型与Bearer，不暴露密码、API授权或会话字段。
     * @throws Exception HTTP失败
     */
    @Test
    void documentsCurrentAccountShapeAndCacheProtection() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
            var document = json.readTree(response.body()); var operation = document.path("paths").path("/auth/me").path("get");
            assertTrue(operation.path("security").get(0).has("bearerAuth"));
            var success = operation.path("responses").path("200");
            assertEquals("no-store", success.path("headers").path("Cache-Control").path("schema").path("example").asText());
            var envelope = resolve(document, success.path("content").path("application/json").path("schema"));
            var schema = resolve(document, envelope.path("properties").path("data"));
            assertEquals(Set.of("userId", "featurePermissions"), schema.path("properties").properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
            assertEquals(1024, schema.path("properties").path("featurePermissions").path("maxItems").asInt());
            assertEquals("string", schema.path("properties").path("featurePermissions").path("items").path("type").asText());
        }
    }

    /**
     * 请求本人数据并核对缓存、trace及精确字段。
     * @param token 当前Access
     * @return 本人展示数据
     * @throws Exception HTTP失败
     */
    private JsonNode me(String token) throws Exception {
        var response = request("GET", "/auth/me", token, null); var result = data(response, 200);
        assertEquals(Set.of("userId", "featurePermissions"), result.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow()); return result;
    }

    /**
     * 读取去重功能集合便于精确比较。
     * @param result 本人响应数据
     * @return 功能编码集合
     */
    private Set<String> features(JsonNode result) { return result.path("featurePermissions").valueStream().map(JsonNode::asText).collect(Collectors.toSet()); }

    /**
     * 通过管理员HTTP替换测试角色权限。
     * @param role 测试角色
     * @param version 观察版本
     * @param grants 全部权限
     * @throws Exception HTTP失败
     */
    private void configure(UUID role, long version, Set<Permission> grants) throws Exception {
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", version, "name", "本人功能", "permissions", grants)), 200);
    }

    /**
     * 解析有限本地文档引用。
     * @param document 完整OpenAPI
     * @param schema 输入模型
     * @return 已解析Schema
     */
    private JsonNode resolve(JsonNode document, JsonNode schema) {
        for (int depth = 0; schema.has("$ref") && depth < 10; depth++) {
            String reference = schema.path("$ref").asText(); assertTrue(reference.startsWith("#/components/schemas/"));
            schema = document.path("components").path("schemas").path(reference.substring("#/components/schemas/".length()));
        }
        assertFalse(schema.has("$ref")); return schema;
    }
}
