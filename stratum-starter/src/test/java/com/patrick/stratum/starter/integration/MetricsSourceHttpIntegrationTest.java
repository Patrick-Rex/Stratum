package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.patrick.stratum.interfaceadapter.security.HttpSecurityConfiguration;
import com.patrick.stratum.interfaceadapter.security.MetricsSourcePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证不允许的直连来源无法通过伪造转发头获得指标。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "stratum.management.metrics.allowed-cidrs=192.0.2.0/24" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MetricsSourceHttpIntegrationTest extends MariaDbIntegrationTest {
    @LocalServerPort private int port;

    /**
     * 本机未获授权时拒绝指标，伪造允许来源也不能绕过；健康探测不受影响。
     * @throws Exception HTTP通信失败
     */
    @Test
    void deniesDirectPeerEvenWhenForwardedHeadersSpoofAllowedSource() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var metrics = HttpRequest.newBuilder(uri("/actuator/prometheus")).timeout(Duration.ofSeconds(10))
                    .header("X-Forwarded-For", "192.0.2.4").header("X-Real-IP", "192.0.2.4")
                    .header("Forwarded", "for=192.0.2.4").GET().build();
            var denied = client.send(metrics, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode()); assertFalse(denied.body().contains("jvm_memory_used_bytes"));
            for (String path : List.of("/actuator/prometheus/", "/actuator/prometheus/extra", "/actuator/prometheus;test=1",
                    "/actuator/prometheus%2fextra")) {
                var ambiguous = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                        .header("X-Forwarded-For", "192.0.2.4").GET().build();
                var response = client.send(ambiguous, HttpResponse.BodyHandlers.ofString());
                assertNotEquals(200, response.statusCode()); assertFalse(response.body().contains("jvm_memory_used_bytes"));
            }
            var health = client.send(HttpRequest.newBuilder(uri("/actuator/health")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        }
    }

    /** 容器或框架转发头模式不能与按直连地址授权同时启用。 */
    @Test
    void refusesForwardedHeaderAddressRewriteConfiguration() {
        var configuration = new HttpSecurityConfiguration();
        var properties = new MetricsSourcePolicy.Properties(List.of("127.0.0.0/8"));
        for (String mode : List.of("framework", "native")) {
            var environment = new MockEnvironment().withProperty("server.forward-headers-strategy", mode);
            assertThrows(IllegalStateException.class, () -> configuration.metricsSourcePolicy(properties, environment));
        }
        assertTrue(configuration.metricsSourcePolicy(properties, new MockEnvironment()).allows("127.0.0.1"));
    }

    /**
     * 返回本机随机测试端口的相对入口。
     * @param path 固定相对路径
     * @return 本机URI
     */
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
