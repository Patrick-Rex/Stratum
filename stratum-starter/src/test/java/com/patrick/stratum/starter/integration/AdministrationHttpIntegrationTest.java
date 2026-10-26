package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 管理HTTP夹具：有界请求、真实Bearer、统一trace验证和精确清理自身账号。 */
abstract class AdministrationHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    protected final JsonMapper json = JsonMapper.builder().build();
    private final List<String> ownedUsernames = new ArrayList<>();
    private final List<String> ownedRoleCodes = new ArrayList<>();

    /** 只按本用例已登记随机用户名清理关系、版本键和账号，父夹具清理登录会话。 */
    private void cleanManagedAccounts() {
        for (String username : ownedUsernames) {
            for (String id : jdbc.queryForList("SELECT id FROM auth_user WHERE username=?", String.class, username)) {
                jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", id);
                jdbc.update("DELETE FROM auth_user WHERE id=?", id);
                redis.delete("auth:user:" + id + ":ver");
            }
        }
    }

    /** 清理本用例角色的权限关系及角色；账号关系已由账号清理方法移除。 */
    @AfterEach
    void cleanManagedRoles() {
        cleanManagedAccounts();
        for (String code : ownedRoleCodes) {
            for (String id : jdbc.queryForList("SELECT id FROM auth_role WHERE code=?", String.class, code)) {
                jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", id);
                jdbc.update("DELETE FROM auth_role WHERE id=?", id);
            }
        }
    }

    /**
     * 经管理HTTP创建普通空权限角色并登记清理范围。
     * @param code 随机测试编码
     * @param name 测试名称
     * @return 角色UUID
     * @throws Exception HTTP失败
     */
    protected UUID createRole(String code, String name) throws Exception {
        ownedRoleCodes.add(code);
        var response = request("POST", "/api/auth/roles", accessToken(), Map.of("code", code, "name", name));
        var result = data(response, 201); UUID id = UUID.fromString(result.path("id").asText());
        assertEquals(0, result.path("version").asLong());
        assertEquals("/api/auth/roles/" + id, response.headers().firstValue("Location").orElseThrow());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store")); return id;
    }

    /**
     * 经真实管理HTTP创建普通账号，记录清理范围并核对Location及无凭据响应。
     * @param username 随机测试用户名
     * @param password 随机测试密码
     * @return 创建的账号UUID
     * @throws Exception HTTP失败
     */
    protected UUID createAccount(String username, String password) throws Exception {
        ownedUsernames.add(username);
        var response = request("POST", "/api/auth/accounts", accessToken(), Map.of("username", username, "password", password));
        var result = data(response, 201); UUID id = UUID.fromString(result.path("id").asText());
        assertEquals(0, result.path("version").asLong()); assertEquals(2, result.size());
        assertEquals("/api/auth/accounts/" + id, response.headers().firstValue("Location").orElseThrow());
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertFalse(response.body().contains(password)); assertFalse(response.body().contains("passwordHash"));
        return id;
    }

    /**
     * 发送API版本1的管理请求，始终附带不能用于授权的伪造身份头。
     * @param method HTTP方法
     * @param path 本地路径
     * @param token 可空Bearer
     * @param payload 可空JSON对象
     * @return 不用于日志输出的响应
     * @throws Exception HTTP失败
     */
    protected HttpResponse<String> request(String method, String path, String token, Object payload) throws Exception {
        return request(method, path, token, payload, "1");
    }

    /**
     * 发送有界真实HTTP并验证统一trace与无Cookie，不输出请求或响应敏感内容。
     * @param method HTTP方法
     * @param path 本地路径
     * @param token 可空Bearer
     * @param payload 可空JSON对象
     * @param version API版本
     * @return 原始响应供安全断言
     * @throws Exception HTTP失败
     */
    protected HttpResponse<String> request(String method, String path, String token, Object payload, String version) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-API-Version", version)
                .header("X-Trace-Id", "administration-http").header("X-User-Id", subjectId());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("administration-http", response.headers().firstValue("X-Trace-Id").orElseThrow());
            assertEquals("administration-http", json.readTree(response.body()).path("traceId").asText());
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty()); return response;
        }
    }

    /**
     * 选择受控的本机HTTP入口，子夹具可切换为独立网关。
     * @param path 固定相对路径
     * @return 本机URI
     */
    protected URI endpoint(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    /**
     * 断言标准成功结果且不将响应原文作为失败信息。
     * @param response 管理响应
     * @param status 预期HTTP状态
     * @return data节点
     */
    protected JsonNode data(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode()); var body = json.readTree(response.body());
        assertEquals(0, body.path("code").asInt()); return body.path("data");
    }
}
