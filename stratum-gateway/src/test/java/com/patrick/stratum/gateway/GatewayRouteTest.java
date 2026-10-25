package com.patrick.stratum.gateway;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import com.patrick.stratum.gateway.route.GatewayRoutingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 用两个独立本机HTTP上游验证固定路由、精确灰度和配置回滚，所有上下文与服务在用例结束关闭。 */
class GatewayRouteTest {
    /**
     * 本机上游夹具，不接受外部目标，使用单个默认HTTP执行线程。
     * @param server 独立HTTP服务
     * @param address 随机端口根地址
     * @param requests 实际收到的请求数
     */
    private record Upstream(HttpServer server, URI address, AtomicInteger requests) implements AutoCloseable {
        /** 停止仅本夹具创建的HTTP服务。 */
        @Override public void close() { server.stop(0); }
    }

    /**
     * 路径、查询与请求体保持原样，客户端目标参数/Header不改变固定上游，未知路径不转发。
     * @throws Exception HTTP或启动失败
     */
    @Test
    void routesOnlyFixedPathsAndIgnoresClientSuppliedUpstream() throws Exception {
        try (var stable = upstream("stable"); var gray = upstream("gray"); var gateway = gateway(stable, gray, true, false, "")) {
            String path = "/api/erp/materials?code=a%2Fb&upstream=" + gray.address();
            var response = request(gateway, path, Map.of("X-Upstream", gray.address().toString()), "中文请求");
            assertEquals(200, response.statusCode()); var body = JsonMapper.builder().build().readTree(response.body());
            assertEquals("stable", body.path("source").asText()); assertEquals(path, body.path("path").asText());
            assertEquals("POST", body.path("method").asText()); assertEquals("中文请求", body.path("body").asText());
            assertEquals(200, request(gateway, "/auth/login", Map.of(), null).statusCode());
            assertEquals(200, request(gateway, "/v3/api-docs", Map.of(), null).statusCode());
            assertEquals(404, request(gateway, "/unmapped", Map.of(), null).statusCode());
            assertEquals(3, stable.requests().get()); assertEquals(0, gray.requests().get());
        }
    }

    /**
     * 灰度Header按字面量精确匹配；关闭灰度并重启后同Header回到稳定上游。
     * @throws Exception HTTP或启动失败
     */
    @Test
    void selectsExactGrayHeaderAndRollsBackToStableRoute() throws Exception {
        try (var stable = upstream("stable"); var gray = upstream("gray")) {
            try (var gateway = gateway(stable, gray, true, true, "")) {
                assertSource("stable", request(gateway, "/api/test", Map.of(), null));
                assertSource("gray", request(gateway, "/api/test", Map.of("X-Stratum-Release", "v2.0"), null));
                assertSource("stable", request(gateway, "/api/test", Map.of("X-Stratum-Release", "v2X0"), null));
                assertSource("stable", request(gateway, "/api/test", Map.of("X-Stratum-Release", "V2.0"), null));
            }
            try (var rollback = gateway(stable, gray, true, false, "")) {
                assertSource("stable", request(rollback, "/api/test", Map.of("X-Stratum-Release", "v2.0"), null));
            }
            assertEquals(4, stable.requests().get()); assertEquals(1, gray.requests().get());
        }
    }

    /**
     * 默认关闭转发或精确域名不符时不接触上游，健康仍由网关自身提供。
     * @throws Exception HTTP或启动失败
     */
    @Test
    void disablesForwardingAndEnforcesOptionalHost() throws Exception {
        try (var stable = upstream("stable"); var gray = upstream("gray")) {
            try (var disabled = gateway(stable, gray, false, false, "")) {
                assertEquals(404, request(disabled, "/api/test", Map.of(), null).statusCode());
                assertEquals(200, request(disabled, "/actuator/health", Map.of(), null).statusCode());
            }
            try (var hostRestricted = gateway(stable, gray, true, false, "example.invalid")) {
                assertEquals(404, request(hostRestricted, "/api/test", Map.of(), null).statusCode());
            }
            assertEquals(0, stable.requests().get()); assertEquals(0, gray.requests().get());
        }
    }

    /** 上游只接受HTTP(S)无凭据根地址，模式/容量/灰度/Header/域名边界均在启动配置校验。 */
    @Test
    void validatesAndFreezesBoundedRoutingConfiguration() {
        URI stable = URI.create("http://127.0.0.1:8080"); var paths = new ArrayList<>(List.of("/api/**"));
        var configured = new GatewayRoutingProperties(true, stable, paths, false, null, "X-Stratum-Release", "gray", "");
        paths.clear(); assertEquals(List.of("/api/**"), configured.paths());
        assertThrows(UnsupportedOperationException.class, () -> configured.paths().add("/**"));
        assertDoesNotThrow(() -> new GatewayRoutingProperties(true, stable, List.of("/doc?.html", "/**/tail"), false, null, "X-Release", "gray", ""));
        for (String invalid : List.of("file:///tmp/erp", "lb://erp", "/relative", "http://localhost:0", "http://localhost:65536",
                "http://user:password@localhost", "http://localhost/api", "http://localhost/?target=erp", "http://localhost/#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> new GatewayRoutingProperties(true, URI.create(invalid), List.of("/api/**"), false, null, "X-Release", "gray", ""));
        }
        for (var invalid : List.of(List.<String>of(), Collections.nCopies(65, "/api/**"), List.of("relative"), List.of("/{broken"), List.of("/bad path"))) {
            assertThrows(IllegalArgumentException.class, () -> new GatewayRoutingProperties(true, stable, invalid, false, null, "X-Release", "gray", ""), invalid.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> new GatewayRoutingProperties(true, stable, List.of("/api/**"), true, null, "X-Release", "gray", ""));
        assertThrows(IllegalArgumentException.class, () -> new GatewayRoutingProperties(true, stable, List.of("/api/**"), false, null, "Bad Header", "gray", ""));
        assertThrows(IllegalArgumentException.class, () -> new GatewayRoutingProperties(true, stable, List.of("/api/**"), false, null, "X-Release", ".*", ""));
        assertThrows(IllegalArgumentException.class, () -> new GatewayRoutingProperties(true, stable, List.of("/api/**"), false, null, "X-Release", "gray", "example.invalid:80"));
    }

    /**
     * 创建独立回显服务，输入读取上限4KiB；未指定执行器时使用JDK单线程默认执行器。
     * @param name 固定上游标识
     * @return 可关闭上游
     * @throws Exception 端口或HTTP初始化失败
     */
    private Upstream upstream(String name) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                byte[] input = exchange.getRequestBody().readNBytes(4097);
                if (input.length > 4096) { exchange.sendResponseHeaders(413, -1); return; }
                byte[] output = JsonMapper.builder().build().writeValueAsBytes(Map.of("source", name,
                        "path", exchange.getRequestURI().toASCIIString(), "method", exchange.getRequestMethod(), "body", new String(input, StandardCharsets.UTF_8)));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, output.length);
                exchange.getResponseBody().write(output);
            } finally { exchange.close(); }
        });
        server.start(); return new Upstream(server, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), requests);
    }

    /**
     * 使用CLI配置启动独立随机端口网关，配置变化通过新上下文启动验证。
     * @param stable 稳定上游
     * @param gray 灰度上游
     * @param enabled 是否转发
     * @param grayEnabled 是否灰度
     * @param host 可选精确域名
     * @return 必须由调用方关闭的上下文
     */
    private ConfigurableApplicationContext gateway(Upstream stable, Upstream gray, boolean enabled, boolean grayEnabled, String host) {
        return SpringApplication.run(GatewayApplication.class, "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--stratum.gateway.routing.enabled=" + enabled,
                "--stratum.gateway.routing.upstream=" + stable.address(), "--stratum.gateway.routing.gray-upstream=" + gray.address(),
                "--stratum.gateway.routing.gray-enabled=" + grayEnabled, "--stratum.gateway.routing.gray-value=v2.0", "--stratum.gateway.routing.host=" + host);
    }

    /**
     * 向已启动网关发送有限时长请求，调用后关闭客户端。
     * @param gateway 随机端口上下文
     * @param path 固定请求路径
     * @param headers 测试头
     * @param body 可空POST体
     * @return 原始HTTP响应
     * @throws Exception 请求失败
     */
    private HttpResponse<String> request(ConfigurableApplicationContext gateway, String path, Map<String, String> headers, String body) throws Exception {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        if (body == null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 检查回显来自预期固定上游。
     * @param expected 标识
     * @param response HTTP响应
     */
    private void assertSource(String expected, HttpResponse<String> response) {
        assertEquals(200, response.statusCode()); assertEquals(expected, JsonMapper.builder().build().readTree(response.body()).path("source").asText());
    }
}
