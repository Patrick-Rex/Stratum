package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.patrick.stratum.domain.auth.TokenCodec;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实网络验证登录夹具、刷新旋转及权威状态失效，断言不输出敏感令牌。 */
class AuthNetworkHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    private final TokenCodec tokens;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> successorKeys = new HashSet<>();

    /**
     * 注入独立令牌断言与测试会话状态客户端。
     * @param tokens 真实RSA编解码器
     * @param redis 独立测试Redis
     */
    @Autowired
    AuthNetworkHttpIntegrationTest(TokenCodec tokens, StringRedisTemplate redis) {
        this.tokens = tokens; this.redis = redis;
    }

    /**
     * 刷新只有一个后继，旧Refresh失效且Access不能代替Refresh。
     * @throws Exception HTTP失败
     */
    @Test
    void rotatesRefreshAndRejectsReplayOverNetwork() throws Exception {
        assertEquals(200, send("GET", "/api/erp/masters/MATERIAL", accessToken(), null).statusCode());
        var response = send("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken()));
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        var data = json.readTree(response.body()).path("data");
        var successor = tokens.verify(data.path("refreshToken").asText(), Purpose.REFRESH);
        successorKeys.add("auth:rt:" + successor.tokenId());
        assertEquals(subjectId(), successor.subject());
        assertEquals(900, data.path("expiresIn").asInt());
        assertEquals(200, send("GET", "/api/erp/masters/MATERIAL", data.path("accessToken").asText(), null).statusCode());
        assertUnauthorized(send("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken())));
        assertUnauthorized(send("POST", "/auth/refresh", null, Map.of("refreshToken", accessToken())));
    }

    /**
     * 匿名、畸形和错误用途被拒绝，修改权威版本后既有Access与Refresh立即失效。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsUntrustedAndRevokedTokensOverNetwork() throws Exception {
        String path = "/api/erp/stock-balances";
        assertUnauthorized(send("GET", path, null, null));
        assertUnauthorized(send("GET", path, "invalid.token.value", null));
        assertUnauthorized(send("GET", path, refreshToken(), null));
        assertEquals(200, send("GET", path, accessToken(), null).statusCode());
        redis.opsForValue().increment("auth:user:" + subjectId() + ":ver");
        assertUnauthorized(send("GET", path, accessToken(), null));
        assertUnauthorized(send("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken())));
    }

    /** 仅清理本用例刷新产生的后继白名单，父夹具清理原始会话。 */
    @AfterEach
    void cleanSuccessors() { if (!successorKeys.isEmpty()) { redis.delete(successorKeys); } }

    /**
     * 验证统一401、关联头、无Cookie及不返回令牌。
     * @param response 拒绝响应
     */
    private void assertUnauthorized(HttpResponse<String> response) {
        assertEquals(401, response.statusCode());
        if (response.uri().getPath().startsWith("/api/")) {
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        var body = json.readTree(response.body());
        assertEquals(40100, body.path("code").asInt());
        assertEquals("auth-network-test", body.path("traceId").asText());
        assertFalse(response.body().contains(accessToken()));
        assertFalse(response.body().contains(refreshToken()));
    }

    /**
     * 通过真实端口发送请求，所有响应均检查无HTTP会话Cookie及trace传播。
     * @param method HTTP方法
     * @param path 路径
     * @param token 可空Access头
     * @param payload 可空JSON请求
     * @return 不用于日志输出的响应
     * @throws Exception HTTP失败
     */
    private HttpResponse<String> send(String method, String path, String token, Map<String, String> payload) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("X-Trace-Id", "auth-network-test")
                .header("Content-Type", "application/json");
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
            assertEquals("auth-network-test", response.headers().firstValue("X-Trace-Id").orElseThrow());
            return response;
        }
    }
}
