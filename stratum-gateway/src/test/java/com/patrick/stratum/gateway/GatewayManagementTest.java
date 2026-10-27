package com.patrick.stratum.gateway;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.gateway.filter.GatewayManagementProperties;
import com.patrick.stratum.gateway.filter.GatewayManagementSourceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 验证管理入口来源与路径边界，不将来源允许误认为ERP业务授权。 */
class GatewayManagementTest extends GatewayHttpIntegrationTest {
    /**
     * 默认本机来源可到达上游，替换CIDR后管理及编码/矩阵子路径在上游前统一403。
     * @throws Exception 本机HTTP失败
     */
    @Test void managementPathsRejectUnlistedPeersBeforeUpstream() throws Exception {
        try (var upstream = upstream(exchange -> exchange.sendResponseHeaders(401, -1))) {
            try (var gateway = gateway(upstream, upstream, true, false, "")) {
                assertEquals(401, request(gateway, "/api/auth/accounts", Map.of(), null).statusCode());
                assertEquals(1, upstream.requests().get());
            }
            try (var gateway = gateway(upstream, upstream, true, false, "",
                    "--stratum.gateway.management.allowed-cidrs=192.0.2.0/24")) {
                for (String path : List.of("/api/auth/accounts", "/api/auth/roles/role/warehouse-scope", "/api/auth/permissions/",
                        "/api/auth/accounts;v=1", "/api/auth/%61ccounts/id", "/api/;v=1/auth/roles", "/api/auth//permissions")) {
                    var response = request(gateway, path, Map.of("X-Trace-Id", "management-denied", "X-Forwarded-For", "192.0.2.42",
                            "Forwarded", "for=192.0.2.42", "X-Real-IP", "192.0.2.42"), null);
                    assertEquals(403, response.statusCode(), path + response.body());
                    var body = JsonMapper.builder().build().readTree(response.body());
                    assertEquals(40300, body.path("code").asInt()); assertTrue(body.path("data").isNull());
                    assertEquals("management-denied", body.path("traceId").asText());
                }
                assertEquals(1, upstream.requests().get());
                assertEquals(401, request(gateway, "/api/auth/accounts-export", Map.of(), null).statusCode());
                assertEquals(401, request(gateway, "/auth/login", Map.of(), null).statusCode());
                assertEquals(3, upstream.requests().get());
            }
        }
    }

    /**
     * Outbox管理路径及编码子路径沿用真实直连来源控制，不信任伪造代理头。
     * @throws Exception 本机HTTP失败
     */
    @Test void outboxManagementUsesSameTrustedPeerPolicy() throws Exception {
        var deny = filter();
        for (String path : List.of("/api/management/outbox", "/api/management/outbox/events/abc/recoveries",
                "/api/management/%6futbox/events", "/api/management/outbox;v=1/events", "/api//management/outbox/events")) {
            assertDenied(deny, request(path, "192.0.2.1"), 403);
        }
        assertAllowed(filter("127.0.0.0/8"), request("/api/management/outbox/events/id", "127.0.0.1"));
        assertAllowed(filter("::1/128"), request("/api/management/outbox/events/id", "::1"));
        assertAllowed(deny, request("/api/management/outbox-export", "192.0.2.1"));
        assertDenied(deny, request("/api/management/outbox%2fevents", "127.0.0.1"), 400);
        try (var upstream = upstream(exchange -> exchange.sendResponseHeaders(401, -1));
             var gateway = gateway(upstream, upstream, true, false, "",
                     "--stratum.gateway.management.allowed-cidrs=192.0.2.0/24")) {
            for (String path : List.of("/api/management/outbox", "/api/management/%6futbox/events/id/recoveries")) {
                var response = request(gateway, path, Map.of("Forwarded", "for=192.0.2.42", "X-Forwarded-For", "192.0.2.42"), null);
                assertEquals(403, response.statusCode());
            }
            assertEquals(0, upstream.requests().get());
            assertEquals(401, request(gateway, "/api/management/outbox-export", Map.of(), null).statusCode());
            assertEquals(1, upstream.requests().get());
        }
    }

    /** IPv4与IPv6前缀按真实对端字节匹配，缺失及未解析来源拒绝，不使用任何转发头。 */
    @Test void literalIpv4AndIpv6CidrsMatchOnlyRealPeers() {
        var filter = filter("192.0.2.128/25", "2001:db8:1234::/48", "::1/128", "127.0.0.0/8");
        for (String peer : List.of("192.0.2.128", "192.0.2.255", "2001:db8:1234::1", "::1", "127.0.0.42")) {
            assertAllowed(filter, request("/api/auth/roles/id", peer));
        }
        for (String peer : List.of("192.0.2.127", "192.0.3.128", "2001:db8:1235::1", "::2", "126.255.255.255")) {
            assertDenied(filter, request("/api/auth/permissions", peer), 403);
        }
        assertDenied(filter, MockServerHttpRequest.get("/api/auth/accounts").header("X-Forwarded-For", "127.0.0.1").build(), 403);
        assertDenied(filter, MockServerHttpRequest.get("/api/auth/accounts").remoteAddress(InetSocketAddress.createUnresolved("localhost", 1234)).build(), 403);
        assertDenied(filter(), request("/api/auth/accounts", "127.0.0.1"), 403);
        assertAllowed(filter("0.0.0.0/0"), request("/api/auth/accounts", "192.0.2.1"));
        assertDenied(filter("0.0.0.0/0"), request("/api/auth/accounts", "::1"), 403);
    }

    /** 路径段解码、矩阵参数和空段均不能绕过管理匹配，模糊分隔符/点段400失败关闭。 */
    @Test void ambiguousAndMatrixPathsCannotBypassSourceChecks() {
        var deny = filter();
        for (String path : List.of("/api/auth/accounts/", "/api/auth/accounts;v=1/detail", "/api/auth/%61ccounts", "/%61pi/auth/roles",
                "/api/auth/permissions%3bv=1", "/api/;p=1/auth/roles", "/api/auth//roles")) {
            assertDenied(deny, request(path, "192.0.2.1"), 403);
        }
        for (String path : List.of("/api/auth%2froles", "/api/auth/%5croles", "/api/auth/%252froles", "/api/../auth/roles", "/api/%2e%2e%3bx/auth/roles")) {
            assertDenied(deny, request(path, "127.0.0.1"), 400);
        }
        for (String path : List.of("/api/auth/accounts-export", "/api/auth/roles2", "/api/erp/orders/PURCHASE", "/auth/me", "/actuator/health")) {
            assertAllowed(deny, request(path, "192.0.2.1"));
        }
    }

    /** 配置不可变且有界，拒绝主机名、区域ID、错误前缀及模糊空白，不执行DNS。 */
    @Test void invalidCidrsAreRejectedAtConstruction() {
        for (String cidr : List.of("localhost/32", "127.0.0.1", "127.0.0.1/33", "::1/129", "127.0.0.1/-1", "127.0.0.1/1/2",
                "127.0.0.1/+1", "[::1]/128", "fe80::1%1/64", " 127.0.0.1/32", "127.0.0.1/32 ")) {
            assertThrows(IllegalArgumentException.class, () -> filter(cidr), cidr);
        }
        assertThrows(IllegalArgumentException.class, () -> new GatewayManagementProperties(java.util.Collections.nCopies(65, "127.0.0.1/32")));
        var source = new java.util.ArrayList<>(List.of("127.0.0.1/32")); var properties = new GatewayManagementProperties(source); source.clear();
        assertEquals(1, properties.allowedCidrs().size()); assertThrows(UnsupportedOperationException.class, () -> properties.allowedCidrs().clear());
    }

    /**
     * 构造无外部来源的固定CIDR过滤器。
     * @param cidrs 允许网段
     * @return 过滤器
     */
    private GatewayManagementSourceFilter filter(String... cidrs) { return new GatewayManagementSourceFilter(new GatewayManagementProperties(List.of(cidrs))); }

    /**
     * 构造原始URI与IP字面量对端，避免测试构造器二次转义路径。
     * @param path 原始路径
     * @param peer 固定IP字面量
     * @return 测试请求
     */
    private MockServerHttpRequest request(String path, String peer) {
        return MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost" + path))
                .remoteAddress(new InetSocketAddress(InetAddress.ofLiteral(peer), 1234)).build();
    }

    /**
     * 校验允许仅表示继续过滤链，不创建业务身份。
     * @param filter 待测过滤器
     * @param request 固定请求
     */
    private void assertAllowed(GatewayManagementSourceFilter filter, MockServerHttpRequest request) {
        var calls = new AtomicInteger();
        filter.filter(MockServerWebExchange.from(request), exchange -> { calls.incrementAndGet(); return Mono.empty(); }).block(Duration.ofSeconds(2));
        assertEquals(1, calls.get());
    }

    /**
     * 校验拒绝状态且后续处理没有执行。
     * @param filter 待测过滤器
     * @param request 固定请求
     * @param status 预期状态
     */
    private void assertDenied(GatewayManagementSourceFilter filter, MockServerHttpRequest request, int status) {
        var calls = new AtomicInteger();
        var failure = assertThrows(ResponseStatusException.class, () -> filter.filter(MockServerWebExchange.from(request), exchange -> {
            calls.incrementAndGet(); return Mono.empty(); }).block(Duration.ofSeconds(2)));
        assertEquals(status, failure.getStatusCode().value()); assertEquals(0, calls.get());
    }
}
