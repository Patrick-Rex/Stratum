package com.patrick.stratum.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.patrick.stratum.gateway.route.GatewayRoutingProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 用两个独立本机HTTP上游验证固定路由、精确灰度和配置回滚，所有上下文与服务在用例结束关闭。 */
class GatewayRouteTest extends GatewayHttpIntegrationTest {
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

}
