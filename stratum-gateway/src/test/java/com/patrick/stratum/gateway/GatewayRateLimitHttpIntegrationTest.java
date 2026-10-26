package com.patrick.stratum.gateway;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 经真实本机HTTP和独立Redis验证路由限流，所有上下文/键/上游均由用例关闭或删除。 */
@Tag("integration")
class GatewayRateLimitHttpIntegrationTest extends GatewayHttpIntegrationTest {
    /**
     * 两网关的稳定与灰度共享同一IP额度，拒绝写请求无上游效果，健康及未知路径不扣令牌。
     * @throws Exception 本机请求或初始化失败
     */
    @Test void twoGatewaysShareStableAndGrayQuotaAndRejectWrites() throws Exception {
        String namespace = namespace();
        try (var stable = upstream("stable"); var gray = upstream("gray");
             var first = realRateGateway(stable, gray, configuration(namespace, redisPort(), 1, 2, 8));
             var second = realRateGateway(stable, gray, configuration(namespace, redisPort(), 1, 2, 8))) {
            var redis = first.getBean(ReactiveStringRedisTemplate.class);
            try {
                second.getBean(ReactiveStringRedisTemplate.class).hasKey(keys(namespace).getFirst()).block(Duration.ofSeconds(3));
                assertEquals(200, request(first, "/actuator/health", Map.of(), null).statusCode());
                assertEquals(404, request(first, "/unknown", Map.of(), null).statusCode());
                assertFalse(redis.hasKey(keys(namespace).getFirst()).block(Duration.ofSeconds(3)));
                assertSource("stable", request(first, "/api/write", Map.of(), "first"));
                assertSource("gray", request(second, "/api/write", Map.of("X-Stratum-Release", "v2.0"), "second"));
                assertEquals(1L, redis.opsForZSet().size(keys(namespace).get(2)).block(Duration.ofSeconds(3)));
                // 固定空桶与未来时戳隔离测试运行速度，不依赖机器在一秒内完成多次HTTP。
                redis.opsForHash().put(keys(namespace).get(0), "7f000001", "0").block(Duration.ofSeconds(3));
                redis.opsForHash().put(keys(namespace).get(1), "7f000001", Long.toString(Long.MAX_VALUE / 2)).block(Duration.ofSeconds(3));
                var denied = request(second, "/api/write", Map.of("X-Trace-Id", "shared-rate", "X-Forwarded-For", "203.0.113.1",
                        "Forwarded", "for=203.0.113.2", "X-Real-IP", "203.0.113.3", "X-Stratum-Release", "v2.0", "Idempotency-Key", "uncommitted-write"), "third");
                assertError(denied, 429, 40002); assertEquals("1", denied.headers().firstValue("Retry-After").orElseThrow());
                assertEquals(1, stable.requests().get()); assertEquals(1, gray.requests().get());
                var before = redis.opsForHash().entries(keys(namespace).get(1)).collectMap(Map.Entry::getKey, Map.Entry::getValue).block(Duration.ofSeconds(3));
                assertEquals(200, request(second, "/actuator/health", Map.of(), null).statusCode());
                assertEquals(404, request(second, "/unknown", Map.of(), null).statusCode());
                var after = redis.opsForHash().entries(keys(namespace).get(1)).collectMap(Map.Entry::getKey, Map.Entry::getValue).block(Duration.ofSeconds(3));
                assertEquals(before, after);
            } finally { cleanup(redis, namespace); }
        }
    }

    /**
     * 客户端槽位耗尽返回503而非另一个IP的429，不接触业务上游。
     * @throws Exception 本机服务或HTTP失败
     */
    @Test void exhaustedIdentityCapacityRejectsBeforeUpstream() throws Exception {
        String namespace = namespace();
        try (var upstream = upstream("unused"); var gateway = realRateGateway(upstream, upstream, configuration(namespace, redisPort(), 20, 40, 1))) {
            var redis = gateway.getBean(ReactiveStringRedisTemplate.class);
            try {
                redis.hasKey(keys(namespace).getFirst()).block(Duration.ofSeconds(3));
                assertTrue(gateway.getBean(RedisGatewayRateLimiter.class).consume(InetAddress.ofLiteral("192.0.2.1")).block(Duration.ofSeconds(3)).allowed());
                assertError(request(gateway, "/api/write", Map.of(), "must-not-run"), 503, 50000);
                assertEquals(0, upstream.requests().get());
            } finally { cleanup(redis, namespace); }
        }
    }

    /**
     * 两网关策略不一致时错误实例503，原策略仍可继续，不以更大桶覆盖共享设置。
     * @throws Exception 本机HTTP失败
     */
    @Test void inconsistentGatewayPolicyFailsClosed() throws Exception {
        String namespace = namespace();
        try (var upstream = upstream("original");
             var original = realRateGateway(upstream, upstream, configuration(namespace, redisPort(), 20, 40, 8));
             var changed = realRateGateway(upstream, upstream, configuration(namespace, redisPort(), 21, 40, 8))) {
            var redis = original.getBean(ReactiveStringRedisTemplate.class);
            try {
                changed.getBean(ReactiveStringRedisTemplate.class).hasKey(keys(namespace).getFirst()).block(Duration.ofSeconds(3));
                redis.hasKey(keys(namespace).getFirst()).block(Duration.ofSeconds(3));
                assertSource("original", request(original, "/api/write", Map.of(), "one"));
                assertError(request(changed, "/api/write", Map.of(), "must-not-run"), 503, 50000);
                assertEquals(1, upstream.requests().get());
                assertSource("original", request(original, "/api/write", Map.of(), "two"));
            } finally { cleanup(redis, namespace); }
        }
    }

    /**
     * 真实Redis连接拒绝产生503且零业务副作用，未匹配路由仍404。
     * @throws Exception 端口或本机HTTP失败
     */
    @Test void unavailableRedisNeverFallsBackToUnrestrictedForwarding() throws Exception {
        try (var reserved = new Socket(); var upstream = upstream("unused")) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            try (var gateway = realRateGateway(upstream, upstream, configuration(namespace(), reserved.getLocalPort(), 20, 40, 8))) {
                assertError(request(gateway, "/api/write", Map.of(), "must-not-run"), 503, 50000);
                assertEquals(404, request(gateway, "/unknown", Map.of(), null).statusCode());
                assertEquals(0, upstream.requests().get());
            }
        }
    }

    /**
     * 构造完整固定策略参数，不允许重复同名CLI键隐式合并。
     * @param namespace 唯一测试空间
     * @param port 明确Redis端口
     * @param rate 补充速率
     * @param burst 突发额度
     * @param maximum 客户端上界
     * @return CLI参数
     */
    private String[] configuration(String namespace, int port, int rate, int burst, int maximum) {
        return new String[] {"--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=" + port,
                "--spring.data.redis.password=" + Objects.toString(System.getenv("STRATUM_IT_REDIS_PASSWORD"), ""),
                "--stratum.gateway.rate-limit.namespace=" + namespace, "--stratum.gateway.rate-limit.replenish-rate=" + rate,
                "--stratum.gateway.rate-limit.burst-capacity=" + burst, "--stratum.gateway.rate-limit.maximum-clients=" + maximum};
    }

    /**
     * 取得必须明确提供的独立测试端口。
     * @return Redis端口
     */
    private int redisPort() { return Integer.parseInt(Objects.requireNonNull(System.getenv("STRATUM_IT_REDIS_PORT"), "必须提供独立Redis端口")); }

    /**
     * 生成符合受信启动配置规则的独立用例命名空间。
     * @return 唯一空间
     */
    private String namespace() { return "it-" + UUID.randomUUID(); }

    /**
     * 只列出本用例固定四键，避免扫描或删除其他命名空间。
     * @param namespace 本用例空间
     * @return 四个键
     */
    private List<String> keys(String namespace) {
        String prefix = "{" + namespace + "}:rate:"; return List.of(prefix + "tokens", prefix + "times", prefix + "expires", prefix + "policy");
    }

    /**
     * 删除本用例四键，连接由网关上下文关闭。
     * @param redis 本用例客户端
     * @param namespace 本用例空间
     */
    private void cleanup(ReactiveStringRedisTemplate redis, String namespace) { redis.delete(Flux.fromIterable(keys(namespace))).block(Duration.ofSeconds(3)); }

    /**
     * 核对真实HTTP失败协议、关联一致及故障脱敏。
     * @param response 原始响应
     * @param status HTTP状态
     * @param code 标准错误码
     */
    private void assertError(java.net.http.HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body()); var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals(5, body.size()); assertEquals(code, body.path("code").asInt()); assertTrue(body.path("data").isNull());
        assertEquals(0, body.path("details").size()); assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(), body.path("traceId").asText());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains("redis")); assertFalse(response.body().contains("127.0.0.1")); assertFalse(response.body().contains("Exception"));
    }
}
