package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.trace.TraceIdContext;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立MariaDB及Redis验证Starter真实Prometheus注册表及本机HTTP抓取。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StarterPrometheusRegistryIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private PrometheusMeterRegistry registry;
    @Autowired private VersionedCache cache;
    @Autowired private StringRedisTemplate redis;
    @LocalServerPort private int port;
    private final String key = "biz:erp:prometheus:" + UUID.randomUUID();

    /** 仅清理本用例随机缓存及关联线程上下文。 */
    @AfterEach
    void cleanup() {
        TraceIdContext.clear();
        redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
    }

    /**
     * 真实缓存请求与HTTP观测写入Prometheus注册表，指标不包含业务键或关联ID。
     * @throws Exception HTTP通信失败
     */
    @Test
    void exportsExistingMetricsToLoopbackHttp() throws Exception {
        String trace = "prometheus-" + UUID.randomUUID(); TraceIdContext.setTraceId(trace);
        var policy = new VersionedCache.Policy(Duration.ofMinutes(1), Duration.ZERO);
        cache.get(key, policy, Optional::empty);
        cache.get(key, policy, () -> { fail("应复用空值缓存"); return Optional.empty(); });
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var health = client.send(HttpRequest.newBuilder(uri("/actuator/health")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            var scrape = client.send(HttpRequest.newBuilder(uri("/actuator/prometheus")).timeout(Duration.ofSeconds(10))
                    .header("X-Forwarded-For", "203.0.113.9").header("Forwarded", "for=203.0.113.9").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, scrape.statusCode()); assertTrue(scrape.body().contains("jvm_memory_used_bytes"));
        }
        String metrics = registry.scrape();
        assertTrue(metrics.contains("jvm_memory_used_bytes"));
        assertTrue(metrics.contains("http_server_requests_seconds_count"));
        assertTrue(metrics.contains("stratum_cache_access_total"));
        assertFalse(metrics.contains(key)); assertFalse(metrics.contains(trace));
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("stratum.cache."))
                .forEach(meter -> assertTrue(meter.getId().getTags().stream().allMatch(tag -> tag.getKey().equals("result"))));
    }

    /**
     * 生成本机随机测试端口上的固定入口。
     * @param path 相对路径
     * @return 本机URI
     */
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
