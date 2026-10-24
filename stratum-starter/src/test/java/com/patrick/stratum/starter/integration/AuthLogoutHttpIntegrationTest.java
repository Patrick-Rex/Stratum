package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实网络验证本人注销、全部注销、协议边界与伪造身份无效。 */
class AuthLogoutHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 单会话注销使用Access身份，自报目标不会转移注销对象，其他会话仍可用。
     * @throws Exception HTTP失败
     */
    @Test
    void logoutRevokesCurrentIdentityOnly() throws Exception {
        var other = additionalSession();
        var response = post("/auth/logout", accessToken(), Map.of("userId", UUID.randomUUID().toString(),
                "sessionId", UUID.randomUUID().toString(), "version", "999"), "1");
        assertSuccess(response);
        assertEquals(401, query(accessToken()).statusCode());
        assertEquals(401, post("/auth/refresh", null, Map.of("refreshToken", refreshToken()), "1").statusCode());
        assertEquals(200, query(other.accessToken()).statusCode());
        assertEquals(401, post("/auth/logout", accessToken(), null, "1").statusCode());
    }

    /**
     * 全部注销使同账号两会话立即失效，重新用凭据登录获得新版本会话。
     * @throws Exception HTTP失败
     */
    @Test
    void logoutAllRevokesOldSessionsAndAllowsFreshLogin() throws Exception {
        var other = additionalSession();
        assertSuccess(post("/auth/logout-all", accessToken(), null, "1"));
        for (String token : new String[]{accessToken(), other.accessToken()}) assertEquals(401, query(token).statusCode());
        for (String token : new String[]{refreshToken(), other.refreshToken()}) {
            assertEquals(401, post("/auth/refresh", null, Map.of("refreshToken", token), "1").statusCode());
        }
        var fresh = additionalSession();
        assertEquals(subjectId(), fresh.subjectId());
        assertEquals(200, query(fresh.accessToken()).statusCode());
    }

    /**
     * 注销要求Access与支持的版本，匿名、Refresh及错误版本不触发撤销。
     * @throws Exception HTTP失败
     */
    @Test
    void requiresTrustedAccessAndSupportedApiVersion() throws Exception {
        for (String path : new String[]{"/auth/logout", "/auth/logout-all"}) {
            assertEquals(401, post(path, null, null, "1").statusCode());
            assertEquals(401, post(path, refreshToken(), null, "1").statusCode());
            var invalid = post(path, accessToken(), null, "2");
            assertEquals(400, invalid.statusCode());
            assertEquals(40001, json.readTree(invalid.body()).path("code").asInt());
        }
        assertEquals(200, query(accessToken()).statusCode());
    }

    /**
     * 验证注销标准结果、缓存保护及不回显令牌。
     * @param response 注销响应
     */
    private void assertSuccess(HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        var body = json.readTree(response.body());
        assertEquals(0, body.path("code").asInt()); assertTrue(body.path("data").isNull());
        assertFalse(response.body().contains(accessToken())); assertFalse(response.body().contains(refreshToken()));
    }

    /**
     * 发送JSON或空注销请求，不将客户端自报信息用于认证。
     * @param path 路径
     * @param token 可空Bearer
     * @param payload 可空JSON
     * @param version API版本
     * @return 敏感响应，仅作断言
     * @throws Exception HTTP失败
     */
    private HttpResponse<String> post(String path, String token, Map<String, String> payload, String version) throws Exception {
        var builder = request(path, token).header("Content-Type", "application/json").header("X-API-Version", version)
                .header("X-User-Id", UUID.randomUUID().toString());
        builder.POST(payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        return send(builder.build());
    }

    /**
     * 读取受保护业务路径以核对Access有效性。
     * @param token 待验证Access
     * @return HTTP响应
     * @throws Exception HTTP失败
     */
    private HttpResponse<String> query(String token) throws Exception {
        return send(request("/api/erp/stock-balances", token).GET().build());
    }

    /**
     * 构造有界本地请求。
     * @param path 路径
     * @param token 可空令牌
     * @return 尚未发送的请求构建器
     */
    private HttpRequest.Builder request(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("X-Trace-Id", "logout-http-test");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    /**
     * 执行真实HTTP并统一核对trace和无Cookie。
     * @param request 已构造请求
     * @return 不用于日志输出的响应
     * @throws Exception HTTP失败
     */
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals("logout-http-test", response.headers().firstValue("X-Trace-Id").orElseThrow());
            assertEquals("logout-http-test", json.readTree(response.body()).path("traceId").asText());
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
            return response;
        }
    }
}
