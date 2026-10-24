package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 实际Servlet容器验证安全代理已注册，未配置JWT也不能匿名进入业务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "stratum.auth.jwt.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityChainHttpIntegrationTest extends MariaDbIntegrationTest {
    @LocalServerPort private int port;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 真实HTTP业务入口失败关闭且返回标准错误，无默认登录或Session。
     * @throws Exception HTTP调用失败
     */
    @Test
    void unconfiguredAuthenticationStillProtectsBusiness() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (String path : new String[]{"/api/erp/masters/MATERIAL", "/api/erp/stock-balances", "/login"}) {
                var response = get(client, path, false);
                assertEquals(401, response.statusCode());
                var body = json.readTree(response.body());
                assertEquals(40100, body.path("code").asInt());
                assertEquals("security-chain-http", body.path("traceId").asText());
                assertEquals("security-chain-http", response.headers().firstValue("X-Trace-Id").orElseThrow());
                assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
                assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
            }
            assertEquals(401, get(client, "/api/erp/stock-balances", true).statusCode());
        }
    }

    /**
     * 健康与既有接口文档读取显式公开，HTTP安全默认头仍生效。
     * @throws Exception HTTP调用失败
     */
    @Test
    void publicHealthAndDocumentationRemainReadable() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var health = get(client, "/actuator/health", false);
            assertEquals(200, health.statusCode()); assertEquals("UP", json.readTree(health.body()).path("status").asText());
            assertEquals(200, get(client, "/v3/api-docs", false).statusCode());
            assertEquals(200, get(client, "/doc.html", false).statusCode());
            assertEquals("nosniff", health.headers().firstValue("X-Content-Type-Options").orElseThrow());
        }
    }

    /**
     * 发起有界本机请求，不自动跟随登录跳转。
     * @param client 自动关闭的HTTP客户端
     * @param path 路径
     * @param bearer 是否添加无效Bearer
     * @return 响应
     * @throws Exception HTTP调用失败
     */
    private HttpResponse<String> get(HttpClient client, String path, boolean bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("X-Trace-Id", "security-chain-http");
        if (bearer) { request.header("Authorization", "Bearer a.b.c"); }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
