package com.patrick.stratum.gateway;

import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.gateway.filter.GatewayProtectionProperties;
import com.patrick.stratum.gateway.filter.GatewayRequestProtectionFilter;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 验证请求容量、真实分块流和取消释放，所有本机资源限定于各用例。 */
class GatewayProtectionTest extends GatewayHttpIntegrationTest {
    /**
     * 固定长度和未知长度的超限体都在上游调用前拒绝，合法边界保持原始字节。
     * @throws Exception HTTP初始化或请求失败
     */
    @Test void fixedAndChunkedBodiesAreCheckedBeforeForwarding() throws Exception {
        try (var upstream = upstream("protected"); var gateway = gateway(upstream, upstream, true, false, "",
                "--stratum.gateway.protection.maximum-body-bytes=1024")) {
            String accepted = "a".repeat(1024);
            var response = request(gateway, "/api/body", Map.of(), accepted);
            assertSource("protected", response);
            assertEquals(accepted, JsonMapper.builder().build().readTree(response.body()).path("body").asText());
            assertRejected(request(gateway, "/api/body", Map.of("X-Trace-Id", "fixed-limit"), "x".repeat(1025)), 413);
            byte[] bytes = "x".repeat(1025).getBytes(StandardCharsets.UTF_8);
            var chunked = HttpRequest.newBuilder(gatewayUri(gateway, "/api/body")).timeout(Duration.ofSeconds(5))
                    .header("X-Trace-Id", "chunk-limit").POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).build();
            assertRejected(send(chunked), 413);
            var validChunked = HttpRequest.newBuilder(gatewayUri(gateway, "/api/body")).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(accepted.getBytes(StandardCharsets.UTF_8)))).build();
            assertSource("protected", send(validChunked));
            assertEquals(2, upstream.requests().get());
        }
    }

    /**
     * 合法但超过应用预算的头返回431，自报来源头不能传递到上游。
     * @throws Exception HTTP初始化或请求失败
     */
    @Test void headerBudgetsAndUntrustedProxyHeadersAreEnforced() throws Exception {
        try (var upstream = upstream(exchange -> {
            for (String header : exchange.getRequestHeaders().keySet()) {
                if (header.equalsIgnoreCase("Forwarded") || header.equalsIgnoreCase("X-Real-IP") || header.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-")) {
                    assertFalse(exchange.getRequestHeaders().getFirst(header).contains("203.0.113.77"));
                    assertFalse(exchange.getRequestHeaders().getFirst(header).contains("evil.example"));
                }
            }
            exchange.sendResponseHeaders(204, -1);
        }); var gateway = gateway(upstream, upstream, true, false, "", "--stratum.gateway.protection.maximum-header-bytes=1024")) {
            assertRejected(request(gateway, "/api/headers", Map.of("X-Large", "a".repeat(1200)), null), 431);
            var response = request(gateway, "/api/headers", Map.of("Forwarded", "for=203.0.113.77;host=evil.example",
                    "X-Forwarded-For", "203.0.113.77", "X-Forwarded-Host", "evil.example", "X-Real-IP", "203.0.113.77"), null);
            assertEquals(204, response.statusCode()); assertEquals(1, upstream.requests().get());
        }
    }

    /** 缓冲上限拒绝新请求，取消和超时均释放池化缓冲及本地容量，后续请求仍可执行。 */
    @Test void cancellationAndTimeoutReleaseBuffersAndCapacity() {
        var filter = new GatewayRequestProtectionFilter(properties(1024, 8, 1, Duration.ofMillis(100)));
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        var buffer = factory.allocateBuffer(512); buffer.write(new byte[512]);
        var waiting = MockServerWebExchange.from(MockServerHttpRequest.post("/api/wait").body(Flux.concat(Mono.just(buffer), Mono.never())));
        var subscription = filter.filter(waiting, exchange -> Mono.empty()).subscribe();
        var next = MockServerWebExchange.from(MockServerHttpRequest.get("/api/next"));
        var rejected = assertThrows(ResponseStatusException.class, () -> filter.filter(next, exchange -> Mono.empty()).block(Duration.ofSeconds(2)));
        assertEquals(503, rejected.getStatusCode().value()); subscription.dispose();
        assertEquals(0, buffer.getNativeBuffer().refCnt());
        var called = new AtomicInteger();
        filter.filter(next, exchange -> { called.incrementAndGet(); return Mono.empty(); }).block(Duration.ofSeconds(2));
        assertEquals(1, called.get());
        var timeout = assertThrows(ResponseStatusException.class, () -> filter.filter(
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/timeout").body(Flux.never())), exchange -> Mono.empty()).block(Duration.ofSeconds(2)));
        assertEquals(408, timeout.getStatusCode().value());
        filter.filter(next, exchange -> Mono.empty()).block(Duration.ofSeconds(2));
    }

    /** 聚合超限释放所有池化片段，非法名称、控制字符和字段数量拒绝且不执行下游。 */
    @Test void invalidHeadersAndOversizeFragmentsDoNotReachChain() {
        var filter = new GatewayRequestProtectionFilter(properties(1024, 8, 1, Duration.ofSeconds(1)));
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        var first = factory.allocateBuffer(512); first.write(new byte[512]);
        var second = factory.allocateBuffer(600); second.write(new byte[600]);
        var oversized = MockServerWebExchange.from(MockServerHttpRequest.post("/api/body").body(Flux.just(first, second)));
        var called = new AtomicInteger();
        var failure = assertThrows(ResponseStatusException.class, () -> filter.filter(oversized, exchange -> { called.incrementAndGet(); return Mono.empty(); }).block(Duration.ofSeconds(2)));
        assertEquals(413, failure.getStatusCode().value()); assertEquals(0, first.getNativeBuffer().refCnt()); assertEquals(0, second.getNativeBuffer().refCnt());
        for (var request : java.util.List.of(MockServerHttpRequest.get("/api/header").header("invalid name", "value").build(),
                MockServerHttpRequest.get("/api/header").header("X-Test", "bad\u0001value").build(),
                MockServerHttpRequest.get("/api/header").header("X-Many", "1", "2", "3", "4", "5", "6", "7", "8", "9").build())) {
            var rejected = assertThrows(ResponseStatusException.class, () -> filter.filter(MockServerWebExchange.from(request), exchange -> { called.incrementAndGet(); return Mono.empty(); }).block(Duration.ofSeconds(2)));
            assertTrue(rejected.getStatusCode().value() == 400 || rejected.getStatusCode().value() == 431);
        }
        assertEquals(0, called.get());
    }

    /** 请求预算和总缓冲量不能配置为无界或超过固定安全上界。 */
    @Test void resourceConfigurationRejectsUnboundedValues() {
        assertThrows(IllegalArgumentException.class, () -> properties(0, 8, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> properties(8388609, 8, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> properties(8388608, 8, 128, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> properties(1024, 257, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> properties(1024, 8, 1, Duration.ZERO));
    }

    /**
     * 构建可重复的最小预算测试配置。
     * @param bytes 请求体上界
     * @param headers 头数量上界
     * @param requests 缓冲并发
     * @param timeout 读取时限
     * @return 已校验配置
     */
    private GatewayProtectionProperties properties(int bytes, int headers, int requests, Duration timeout) {
        return new GatewayProtectionProperties(bytes, 1024, headers, requests, timeout);
    }

    /**
     * 验证应用防护返回标准失败外壳和安全关联头。
     * @param response HTTP响应
     * @param status 预期状态
     */
    private void assertRejected(java.net.http.HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals(5, body.size()); assertEquals(40000, body.path("code").asInt()); assertTrue(body.path("data").isNull());
        assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(), body.path("traceId").asText());
    }
}
