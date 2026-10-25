package com.patrick.stratum.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 独立随机端口验证真实Netty健康入口及运行时隔离，类结束时关闭上下文和HTTP资源。 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayApplicationTest {
    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;

    /**
     * 无数据库凭据或ERP模块即可独立启动，健康端点只返回必要状态。
     * @throws Exception HTTP失败
     */
    @Test
    void startsIndependentHealthyGatewayWithoutBusinessDatabase() throws Exception {
        var response = get("/actuator/health"); assertEquals(200, response.statusCode());
        var body = JsonMapper.builder().build().readTree(response.body()); assertEquals("UP", body.path("status").asText());
        assertFalse(body.has("components")); assertEquals("stratum-gateway", context.getEnvironment().getProperty("spring.application.name"));
        assertFalse(context.containsBean("dataSource")); assertFalse(context.containsBean("stratumApplication"));
    }

    /**
     * 仅暴露健康入口，不意外装配ERP端点或敏感管理信息。
     * @throws Exception HTTP失败
     */
    @Test
    void exposesOnlyConfiguredHealthEndpoint() throws Exception {
        for (String path : java.util.List.of("/actuator/env", "/actuator/beans", "/api/erp/orders/PURCHASE", "/auth/login")) {
            assertEquals(404, get(path).statusCode());
        }
    }

    /**
     * 使用连接与请求超时执行真实本机HTTP请求，调用完成关闭客户端。
     * @param path 固定验证路径
     * @return 响应
     * @throws Exception 连接或读取失败
     */
    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET().build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
