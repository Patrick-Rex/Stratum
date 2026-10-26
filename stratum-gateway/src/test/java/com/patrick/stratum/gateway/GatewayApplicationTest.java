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
class GatewayApplicationTest extends GatewayHttpIntegrationTest {
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
     * 仅暴露配置的健康与受保护指标入口，不意外装配ERP端点或敏感管理信息。
     * @throws Exception HTTP失败
     */
    @Test
    void exposesOnlyConfiguredManagementEndpoints() throws Exception {
        for (String path : java.util.List.of("/actuator/env", "/actuator/beans", "/api/erp/orders/PURCHASE", "/auth/login")) {
            assertEquals(404, get(path).statusCode());
        }
    }

    /**
     * 真正抓取Prometheus文本，验证成功、429、503、404与自定义方法的有界标签及直方图。
     * @throws Exception 本机HTTP失败
     */
    @Test void exposesBoundedRequestAndJvmMetrics() throws Exception {
        try (var upstream = upstream(exchange -> exchange.sendResponseHeaders(204, -1));
             var gateway = gateway(upstream, upstream, true, false, "")) {
            var registry = gateway.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            assertEquals(204, request(gateway, "/api/private?token=metric-secret", java.util.Map.of("X-Trace-Id", "metric-trace-secret"), "body-secret").statusCode());
            var limiter = gateway.getBean(com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.class);
            org.mockito.Mockito.when(limiter.consume(org.mockito.ArgumentMatchers.any(java.net.InetAddress.class)))
                    .thenReturn(reactor.core.publisher.Mono.just(new com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.Decision(false, 0, 1000, false)));
            assertEquals(429, request(gateway, "/api/rate", java.util.Map.of(), null).statusCode());
            org.mockito.Mockito.when(limiter.consume(org.mockito.ArgumentMatchers.any(java.net.InetAddress.class)))
                    .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("private-store-detail")));
            assertEquals(503, request(gateway, "/api/failure", java.util.Map.of(), null).statusCode());
            org.mockito.Mockito.when(limiter.consume(org.mockito.ArgumentMatchers.any(java.net.InetAddress.class)))
                    .thenReturn(reactor.core.publisher.Mono.just(new com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.Decision(true, 40, 0, false)));
            for (int index = 0; index < 6; index++) {
                var custom = HttpRequest.newBuilder(gatewayUri(gateway, "/api/private-" + index)).timeout(Duration.ofSeconds(5))
                        .method("CUSTOM" + index, HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(204, send(custom).statusCode());
                assertEquals(404, request(gateway, "/private-object-" + index, java.util.Map.of(), null).statusCode());
            }
            var scrape = request(gateway, "/actuator/prometheus", java.util.Map.of(), null);
            assertEquals(200, scrape.statusCode()); String text = scrape.body();
            assertTrue(text.contains("jvm_memory_used_bytes")); assertTrue(text.contains("stratum_gateway_requests_seconds_count"));
            assertTrue(text.contains("stratum_gateway_requests_seconds_bucket")); assertTrue(text.contains("status=\"429\"")); assertTrue(text.contains("status=\"503\""));
            String leaked = text.lines().filter(line -> line.contains("metric-secret") || line.contains("metric-trace-secret")
                    || line.contains("CUSTOM0") || line.contains("private-object-") || line.contains("private-0")).collect(java.util.stream.Collectors.joining("\n"));
            assertEquals("", leaked);
            assertEquals(1, registry.find("stratum.gateway.requests").tag("status", "429").timer().count());
            assertEquals(1, registry.find("stratum.gateway.requests").tag("status", "503").timer().count());
            assertEquals(6, registry.find("stratum.gateway.requests").tag("method", "OTHER").timer().count());
            assertTrue(registry.find("http.server.requests").meters().stream().anyMatch(meter -> "OTHER".equals(meter.getId().getTag("method"))));
            for (var meter : registry.find("stratum.gateway.requests").meters()) {
                assertEquals(java.util.Set.of("method", "route", "status"), meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).collect(java.util.stream.Collectors.toSet()));
            }
            for (String name : java.util.List.of("http.server.requests", "http.client.requests", "http.client.requests.active")) {
                assertFalse(registry.find(name).meters().isEmpty());
                for (var meter : registry.find(name).meters()) for (var tag : meter.getId().getTags()) {
                    assertFalse(tag.getValue().contains("private-")); assertFalse(tag.getValue().contains("private-object-")); assertFalse(tag.getValue().startsWith("CUSTOM"));
                    assertFalse(tag.getValue().contains("127.0.0.1")); assertFalse(tag.getValue().contains("metric-trace"));
                }
            }
        }
    }

    /**
     * 指标完整路径及编码形式沿用管理CIDR，伪造转发来源无效，健康仍可探测。
     * @throws Exception 本机HTTP失败
     */
    @Test void metricsUseManagementCidrsWithoutRestrictingHealth() throws Exception {
        try (var upstream = upstream("unused"); var gateway = gateway(upstream, upstream, false, false, "",
                "--stratum.gateway.management.allowed-cidrs=192.0.2.0/24")) {
            for (String path : java.util.List.of("/actuator/prometheus", "/actuator/prometheus;v=1", "/actuator/%70rometheus")) {
                assertEquals(403, request(gateway, path, java.util.Map.of("Forwarded", "for=192.0.2.1", "X-Forwarded-For", "192.0.2.1"), null).statusCode());
            }
            assertEquals(200, request(gateway, "/actuator/health", java.util.Map.of(), null).statusCode());
            assertEquals(0, upstream.requests().get());
        }
    }

    /** 取消499与稍后提交竞争只记录一个计时样本，直接测试不注册全局静态Registry。 */
    @Test void cancellationMetricsAreRecordedOnce() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var filter = new com.patrick.stratum.gateway.filter.GatewayMetricsFilter(registry);
            var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/cancel"));
            var pending = filter.filter(exchange, ignored -> reactor.core.publisher.Mono.never()).subscribe(); pending.dispose();
            exchange.getResponse().setComplete().block(Duration.ofSeconds(2));
            assertEquals(1, registry.find("stratum.gateway.requests").tag("status", "499").timer().count());
            assertNull(registry.find("stratum.gateway.requests").tag("status", "200").timer());
        } finally { registry.close(); }
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
