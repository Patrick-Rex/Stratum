package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.infrastructure.messaging.DeadLetterBacklogMetrics;
import com.patrick.stratum.infrastructure.messaging.RedisDeadLetterBacklogStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实Prometheus端点的共享死信可见数及不可用表示。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "stratum.observability.deadletter.enabled=true" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadLetterBacklogMetricsIntegrationTest extends MariaDbIntegrationTest {
    private static final String NAMESPACE = "it-" + UUID.randomUUID();
    @Autowired private DeadLetterBacklogStore store;
    @Autowired private StringRedisTemplate redis;
    @LocalServerPort private int port;

    /**
     * 为真实Redis指标分配隔离命名空间。
     * @param registry 动态配置注册器
     */
    @DynamicPropertySource static void namespace(DynamicPropertyRegistry registry) {
        registry.add("stratum.observability.deadletter.namespace", () -> NAMESPACE);
    }

    /** 清理本用例的三个固定Redis键。 */
    @AfterEach void cleanup() {
        String prefix = NAMESPACE + ":{deadletter-backlog}:";
        redis.delete(List.of(prefix + "generation", prefix + "erp_audit", prefix + "cache_invalidation"));
    }

    /**
     * 未采样、显式失败、有效零值与Redis过期通过实际本机HTTP导出。
     * @throws Exception HTTP或客户端失败
     */
    @Test void exportsAvailableAndUnavailableSnapshots() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            String initial = scrape(client);
            assertTrue(initial.contains("stratum_messaging_deadletter_visible{group=\"erp_audit\"} NaN"));
            var generation = store.begin();
            assertTrue(store.record(generation, Group.ERP_AUDIT, OptionalLong.of(0), Duration.ofSeconds(3)));
            assertTrue(store.record(generation, Group.CACHE_INVALIDATION, OptionalLong.empty(), Duration.ofSeconds(3)));
            String current = scrape(client);
            assertTrue(current.contains("stratum_messaging_deadletter_visible{group=\"erp_audit\"} 0.0"));
            assertTrue(current.contains("stratum_messaging_deadletter_visible{group=\"cache_invalidation\"} NaN"));
            await().atMost(Duration.ofSeconds(8)).until(() -> scrape(client).contains("stratum_messaging_deadletter_visible{group=\"erp_audit\"} NaN"));
            assertFalse(current.contains(generation.toString())); assertFalse(current.contains(NAMESPACE));
        }
    }

    /** 无法读取共享状态时Gauge返回NaN，不影响其他指标。 */
    @Test void returnsNanWhenStoreThrows() {
        var unavailable = mock(DeadLetterBacklogStore.class);
        when(unavailable.read(any())).thenThrow(new IllegalStateException("测试故障"));
        var registry = new SimpleMeterRegistry();
        try {
            var holder = new DeadLetterBacklogMetrics(registry, unavailable);
            assertNotNull(holder);
            assertTrue(Double.isNaN(registry.get("stratum.messaging.deadletter.visible").tag("group", "erp_audit").gauge().value()));
            assertTrue(Double.isNaN(registry.get("stratum.messaging.deadletter.visible").tag("group", "cache_invalidation").gauge().value()));
        } finally { registry.close(); }
    }

    /**
     * 实际Redis连接失败时读取端口与Gauge都保持不可用。
     * @throws Exception 临时本机端口申请失败
     */
    @Test void treatsUnavailableRedisAsNan() throws Exception {
        int unavailablePort;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { unavailablePort = socket.getLocalPort(); }
        var clientConfig = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", unavailablePort), clientConfig);
        factory.afterPropertiesSet(); factory.start();
        try {
            var isolated = new StringRedisTemplate(factory); isolated.afterPropertiesSet();
            var unavailable = new RedisDeadLetterBacklogStore(isolated, NAMESPACE);
            assertTrue(unavailable.read(Group.ERP_AUDIT).isEmpty());
            var registry = new SimpleMeterRegistry();
            try {
                var holder = new DeadLetterBacklogMetrics(registry, unavailable); assertNotNull(holder);
                assertTrue(Double.isNaN(registry.get("stratum.messaging.deadletter.visible").tag("group", "erp_audit").gauge().value()));
            } finally { registry.close(); }
        } finally { factory.destroy(); }
    }

    /**
     * 通过本机受保护入口抓取实际文本。
     * @param client 本机HTTP客户端
     * @return Prometheus文本
     * @throws Exception HTTP失败
     */
    private String scrape(HttpClient client) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + "/actuator/prometheus");
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); return response.body();
    }
}
