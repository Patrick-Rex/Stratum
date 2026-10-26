package com.patrick.stratum.gateway;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter;
import com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式独立Redis上的共享桶验收，未配置环境时直接失败而不跳过测试。 */
@Tag("integration")
class RedisGatewayRateLimiterIntegrationTest {
    private LettuceConnectionFactory firstFactory;
    private LettuceConnectionFactory secondFactory;
    private ReactiveStringRedisTemplate redis;
    private ReactiveStringRedisTemplate other;
    private String namespace;
    private List<String> keys;

    /** 创建两条独立客户端连接及唯一命名空间，不清理其他测试或业务键。 */
    @BeforeEach void openRedis() {
        int port = Integer.parseInt(Objects.requireNonNull(System.getenv("STRATUM_IT_REDIS_PORT"), "必须显式提供独立Redis端口"));
        firstFactory = factory(port); secondFactory = factory(port);
        redis = new ReactiveStringRedisTemplate(firstFactory); other = new ReactiveStringRedisTemplate(secondFactory);
        namespace = "it-" + UUID.randomUUID(); String prefix = "{" + namespace + "}:rate:";
        keys = List.of(prefix + "tokens", prefix + "times", prefix + "expires", prefix + "policy");
        assertTrue(redis.hasKey(keys.getFirst()).block(Duration.ofSeconds(3)) == Boolean.FALSE);
    }

    /** 删除本用例四个确定的键并关闭两条客户端，不执行FLUSHDB或模式扫描。 */
    @AfterEach void closeRedis() {
        try { if (redis != null && keys != null) redis.delete(Flux.fromIterable(keys)).block(Duration.ofSeconds(3)); }
        finally { if (firstFactory != null) firstFactory.destroy(); if (secondFactory != null) secondFactory.destroy(); }
    }

    /** 两个独立客户端并发共享同一IP桶，原子总放行量受时间补充约束，等待建议有界。 */
    @Test void concurrentInstancesShareOneAtomicBucket() {
        var first = limiter(redis, 1, 20, 8); var second = limiter(other, 1, 20, 8); var client = InetAddress.ofLiteral("192.0.2.1");
        assertTrue(first.consume(client).block(Duration.ofSeconds(3)).allowed());
        long started = System.nanoTime();
        var decisions = Flux.range(0, 100).flatMap(index -> (index % 2 == 0 ? first : second).consume(client), 16).collectList().block(Duration.ofSeconds(5));
        long elapsed = (System.nanoTime() - started) / 1000000;
        long allowed = decisions.stream().filter(RedisGatewayRateLimiter.Decision::allowed).count();
        assertTrue(allowed >= 19); assertTrue(allowed <= 19 + elapsed / 1000 + 1);
        assertTrue(decisions.stream().anyMatch(decision -> !decision.allowed() && !decision.capacityExhausted() && decision.retryAfterMillis() > 0));
        assertEquals(1L, redis.opsForZSet().size(keys.get(2)).block(Duration.ofSeconds(3)));
    }

    /** Redis时钟补充余额，IPv4/IPv6身份独立，不同实例策略冲突不允许消耗。 */
    @Test void replenishesFromServerTimeAndRejectsPolicyDrift() {
        var limiter = limiter(redis, 20, 2, 8); var peer = InetAddress.ofLiteral("192.0.2.1");
        assertTrue(limiter.consume(peer).block(Duration.ofSeconds(3)).allowed());
        String identity = java.util.HexFormat.of().formatHex(peer.getAddress());
        redis.opsForHash().put(keys.get(0), identity, "0").block(Duration.ofSeconds(3));
        redis.opsForHash().put(keys.get(1), identity, "0").block(Duration.ofSeconds(3));
        assertTrue(limiter.consume(peer).block(Duration.ofSeconds(3)).allowed());
        assertTrue(limiter.consume(InetAddress.ofLiteral("2001:db8::1")).block(Duration.ofSeconds(3)).allowed());
        assertThrows(IllegalStateException.class, () -> limiter(other, 21, 2, 8).consume(peer).block(Duration.ofSeconds(3)));
        assertEquals(2L, redis.opsForZSet().size(keys.get(2)).block(Duration.ofSeconds(3)));
    }

    /** 新身份满额不驱逐现有桶，固定键有TTL，过期槽位在脚本内回收后才能接纳新身份。 */
    @Test void boundsClientsAndReclaimsExpiredSlotsWithTtl() {
        var limiter = limiter(redis, 1, 2, 2); var first = InetAddress.ofLiteral("192.0.2.1");
        assertTrue(limiter.consume(first).block(Duration.ofSeconds(3)).allowed());
        assertTrue(limiter.consume(InetAddress.ofLiteral("192.0.2.2")).block(Duration.ofSeconds(3)).allowed());
        var denied = limiter.consume(InetAddress.ofLiteral("192.0.2.3")).block(Duration.ofSeconds(3));
        assertFalse(denied.allowed()); assertTrue(denied.capacityExhausted());
        for (String key : keys) {
            Duration ttl = redis.getExpire(key).block(Duration.ofSeconds(3)); assertTrue(ttl.isPositive()); assertTrue(ttl.compareTo(Duration.ofSeconds(60)) <= 0);
        }
        String identity = java.util.HexFormat.of().formatHex(first.getAddress());
        redis.opsForZSet().add(keys.get(2), identity, 0).block(Duration.ofSeconds(3));
        assertTrue(limiter.consume(InetAddress.ofLiteral("192.0.2.3")).block(Duration.ofSeconds(3)).allowed());
        assertFalse(redis.opsForHash().hasKey(keys.get(0), identity).block(Duration.ofSeconds(3)));
        assertFalse(redis.opsForHash().hasKey(keys.get(1), identity).block(Duration.ofSeconds(3)));
        assertEquals(2L, redis.opsForZSet().size(keys.get(2)).block(Duration.ofSeconds(3)));
    }

    /**
     * 错误存储类型与真实不可连接Redis都产生失败，不返回允许结果。
     * @throws Exception 本机保留端口失败
     */
    @Test void storageCorruptionAndConnectionFailureRemainFailures() throws Exception {
        redis.opsForValue().set(keys.get(0), "not-a-hash").block(Duration.ofSeconds(3));
        assertThrows(RuntimeException.class, () -> limiter(redis, 20, 40, 8).consume(InetAddress.ofLiteral("192.0.2.1")).block(Duration.ofSeconds(3)));
        try (var reserved = new Socket()) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0)); var unavailable = factory(reserved.getLocalPort());
            try {
                var limiter = new RedisGatewayRateLimiter(new ReactiveStringRedisTemplate(unavailable), new Properties(20, 40, 8, namespace, Duration.ofMillis(200)));
                assertThrows(RuntimeException.class, () -> limiter.consume(InetAddress.ofLiteral("192.0.2.1")).block(Duration.ofSeconds(2)));
            } finally { unavailable.destroy(); }
        }
    }

    /** 任一共享状态键丢失时失败关闭，不能重新初始化旧桶或积累无索引字段。 */
    @Test void missingSharedStateCannotReinitializeExistingBuckets() {
        var peer = InetAddress.ofLiteral("192.0.2.1");
        for (String missing : keys) {
            redis.delete(Flux.fromIterable(keys)).block(Duration.ofSeconds(3));
            assertTrue(limiter(redis, 20, 40, 8).consume(peer).block(Duration.ofSeconds(3)).allowed());
            redis.delete(missing).block(Duration.ofSeconds(3));
            assertThrows(IllegalStateException.class, () -> limiter(other, 20, 40, 8).consume(peer).block(Duration.ofSeconds(3)));
        }
    }

    /** 边界配置、异常脚本结果及永不完成的客户端都不能放行，命令等待有时限。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test void validatesConfigurationAndTimesOutUnfinishedCommands() {
        assertThrows(IllegalArgumentException.class, () -> new Properties(0, 40, 8, namespace, Duration.ofMillis(500)));
        assertThrows(IllegalArgumentException.class, () -> new Properties(20, 20001, 8, namespace, Duration.ofMillis(500)));
        assertThrows(IllegalArgumentException.class, () -> new Properties(20, 40, 65537, namespace, Duration.ofMillis(500)));
        assertThrows(IllegalArgumentException.class, () -> new Properties(20, 40, 8, "{client-controlled}", Duration.ofMillis(500)));
        assertThrows(IllegalArgumentException.class, () -> new Properties(20, 40, 8, namespace, Duration.ZERO));
        var builder = LettuceClientConfiguration.builder(); RedisGatewayRateLimiter.gatewayRedisClientBounds().customize(builder);
        var options = builder.build().getClientOptions().orElseThrow();
        assertEquals(256, options.getRequestQueueSize());
        assertEquals(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, options.getDisconnectedBehavior());
        var mockRedis = mock(ReactiveStringRedisTemplate.class);
        when(mockRedis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(List.of(1L, -1L, 0L, 0L)));
        var limiter = new RedisGatewayRateLimiter(mockRedis, new Properties(20, 40, 8, namespace, Duration.ofMillis(50)));
        assertThrows(IllegalStateException.class, () -> limiter.consume(InetAddress.ofLiteral("192.0.2.1")).block(Duration.ofSeconds(2)));
        when(mockRedis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.never());
        var failure = assertThrows(RuntimeException.class, () -> limiter.consume(InetAddress.ofLiteral("192.0.2.1")).block(Duration.ofSeconds(2)));
        assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
    }

    /**
     * 以共享命名空间创建限定策略的另一个客户端实例。
     * @param template 独立响应式连接
     * @param rate 补充速率
     * @param burst 突发量
     * @param maximum 活跃IP上界
     * @return 限流器
     */
    private RedisGatewayRateLimiter limiter(ReactiveStringRedisTemplate template, int rate, int burst, int maximum) {
        return new RedisGatewayRateLimiter(template, new Properties(rate, burst, maximum, namespace, Duration.ofMillis(500)));
    }

    /**
     * 连接显式本机端口，不输出认证信息。
     * @param port 独立Redis端口
     * @return 由测试负责销毁的连接工厂
     */
    private LettuceConnectionFactory factory(int port) {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", port);
        String password = System.getenv("STRATUM_IT_REDIS_PASSWORD"); if (password != null && !password.isBlank()) configuration.setPassword(RedisPassword.of(password));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).shutdownTimeout(Duration.ZERO).build();
        var factory = new LettuceConnectionFactory(configuration, client); factory.afterPropertiesSet(); factory.start(); return factory;
    }
}
