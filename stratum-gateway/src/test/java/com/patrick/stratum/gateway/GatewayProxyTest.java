package com.patrick.stratum.gateway;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.gateway.filter.GatewayErrorHandler;
import com.patrick.stratum.gateway.route.GatewayProxyConfiguration.ProxyProperties;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实本机网络验证代理失败、业务透传及资源生命周期，不依赖外部服务。 */
class GatewayProxyTest extends GatewayHttpIntegrationTest {
    /**
     * 未监听的占用端口返回502，未知路由404；外壳不暴露地址、路径或查询参数。
     * @throws Exception 本机网络失败
     */
    @Test void connectionFailureAndUnknownRouteHaveSafeEnvelope() throws Exception {
        try (var reserved = new Socket()) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            URI target = URI.create("http://127.0.0.1:" + reserved.getLocalPort());
            try (var gateway = gateway(target, target, true, false, "")) {
                var failed = request(gateway, "/api/private-secret?password=hidden", Map.of("X-Trace-Id", "proxy-connect"), "write");
                assertFailure(failed, 502, 50000, "proxy-connect");
                assertFalse(failed.body().contains("127.0.0.1")); assertFalse(failed.body().contains("private-secret"));
                assertFalse(failed.body().contains("hidden")); assertFalse(failed.body().contains("Exception"));
                assertFailure(request(gateway, "/unknown-private", Map.of("X-Trace-Id", "proxy-missing"), null), 404, 40400, "proxy-missing");
            }
        }
    }

    /**
     * 上游停滞在响应头之前产生504，连接提前关闭产生502；POST不额外重试。
     * @throws Exception 本机服务或请求失败
     */
    @Test void stalledAndAbortedWritesAreNotRetried() throws Exception {
        var release = new CountDownLatch(1);
        try (var slow = upstream(exchange -> {
            exchange.getRequestBody().readNBytes(4096);
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        })) {
            try (var gateway = gateway(slow, slow, true, false, "", "--stratum.gateway.proxy.response-timeout=200ms")) {
                assertFailure(request(gateway, "/api/write", Map.of("X-Trace-Id", "proxy-slow", "Idempotency-Key", "one-write"), "payload"), 504, 50000, "proxy-slow");
                assertEquals(1, slow.requests().get());
            } finally { release.countDown(); }
        }
        try (var aborted = upstream(exchange -> exchange.getRequestBody().readNBytes(4096));
             var gateway = gateway(aborted, aborted, true, false, "")) {
            assertFailure(request(gateway, "/api/write", Map.of("X-Trace-Id", "proxy-abort"), "payload"), 502, 50000, "proxy-abort");
            assertEquals(1, aborted.requests().get());
        }
    }

    /**
     * 业务401和409保持原始状态、正文和认证头，不二次包装。
     * @throws Exception 本机HTTP失败
     */
    @Test void businessErrorsPassThroughUnchanged() throws Exception {
        for (int status : List.of(401, 409)) {
            String body = "{\"code\":" + status + "01,\"message\":\"业务拒绝\",\"data\":null,\"details\":[],\"traceId\":\"app-trace\"}";
            try (var upstream = upstream(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }); var gateway = gateway(upstream, upstream, true, false, "")) {
                var response = request(gateway, "/api/business", Map.of(), null);
                assertEquals(status, response.statusCode()); assertEquals(body, response.body());
                assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
                assertEquals(1, upstream.requests().get());
            }
        }
    }

    /**
     * 校验容量边界及实际装配的禁重试、读取超时和连接池关闭。
     * @throws Exception 本机服务初始化失败
     */
    @Test void boundedClientConfigurationIsAppliedAndDisposed() throws Exception {
        var defaults = new ProxyProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 128, 256, Duration.ofSeconds(1));
        assertEquals(128, defaults.maximumConnections());
        assertThrows(IllegalArgumentException.class, () -> new ProxyProperties(Duration.ZERO, Duration.ofSeconds(5), 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProxyProperties(Duration.ofSeconds(2), Duration.ofSeconds(Long.MAX_VALUE), 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProxyProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProxyProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, 4097, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProxyProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, 1, Duration.ofSeconds(11)));
        ConnectionProvider provider;
        try (var upstream = upstream("bounded"); var gateway = gateway(upstream, upstream, true, false, "",
                "--stratum.gateway.proxy.maximum-connections=2", "--stratum.gateway.proxy.maximum-pending=3",
                "--stratum.gateway.proxy.response-timeout=700ms")) {
            provider = gateway.getBean("gatewayConnectionProvider", ConnectionProvider.class);
            assertEquals(2, provider.maxConnections());
            var configuration = gateway.getBean(HttpClient.class).configuration();
            assertTrue(configuration.isRetryDisabled()); assertNull(configuration.followRedirectPredicate());
            assertEquals(Duration.ofMillis(700), configuration.responseTimeout());
            assertSource("bounded", request(gateway, "/api/ok", Map.of(), null));
        }
        assertTrue(provider.isDisposed());
    }

    /** 检查连接超时的类型映射、残留响应头清理及已提交流不被改写。 */
    @Test void timeoutMappingAndCommittedStreamsAreSafe() {
        var handler = new GatewayErrorHandler(JsonMapper.builder().build());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/sensitive"));
        exchange.getResponse().getHeaders().set("Content-Encoding", "gzip");
        exchange.getResponse().getHeaders().set("Set-Cookie", "secret=value");
        handler.handle(exchange, new ConnectTimeoutException("sensitive-upstream")).block(Duration.ofSeconds(2));
        assertEquals(504, exchange.getResponse().getStatusCode().value());
        assertNull(exchange.getResponse().getHeaders().getFirst("Set-Cookie"));
        assertNull(exchange.getResponse().getHeaders().getFirst("Content-Encoding"));
        String body = exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(2));
        assertFalse(body.contains("sensitive"));
        var committed = MockServerWebExchange.from(MockServerHttpRequest.get("/api/stream"));
        committed.getResponse().setComplete().block(Duration.ofSeconds(2));
        var failure = new IllegalStateException("original-failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> handler.handle(committed, failure).block(Duration.ofSeconds(2))));
    }

    /**
     * 验证网关自身错误使用精确五字段、空业务数据和一致关联标识。
     * @param response 原始响应
     * @param status HTTP状态
     * @param code 标准错误码
     * @param traceId 请求关联标识
     */
    private void assertFailure(HttpResponse<String> response, int status, int code, String traceId) {
        assertEquals(status, response.statusCode(), response.body());
        var tree = JsonMapper.builder().build().readTree(response.body());
        assertEquals(5, tree.size()); assertEquals(code, tree.path("code").asInt());
        assertTrue(tree.path("data").isNull()); assertEquals(0, tree.path("details").size());
        assertEquals(traceId, tree.path("traceId").asText());
        assertEquals(traceId, response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
    }
}
