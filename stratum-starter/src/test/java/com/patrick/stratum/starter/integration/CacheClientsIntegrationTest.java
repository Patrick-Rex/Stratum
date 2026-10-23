package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.github.benmanes.caffeine.cache.Cache;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 Redis 客户端及 Spring 配置装配后的有界 Caffeine，不宣称 Cache Aside 已完成。 */
@TestPropertySource(properties = {"stratum.cache.local.maximum-size=2", "stratum.cache.local.ttl=30s"})
class CacheClientsIntegrationTest extends MariaDbIntegrationTest {
    private final StringRedisTemplate redis;
    private final Cache<String, String> local;

    /**
     * 注入配置后的缓存客户端。
     * @param redis Redis 字符串客户端
     * @param local 本地缓存客户端
     */
    @Autowired
    CacheClientsIntegrationTest(StringRedisTemplate redis, Cache<String, String> local) {
        this.redis = redis;
        this.local = local;
    }

    /** 真实 Redis 可读写中文并设置 TTL；测试仅清理自己的随机键。 */
    @Test
    void redisRoundTripAlwaysHasExpiry() {
        String key = "stratum:it:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "缓存值", Duration.ofMinutes(2));
            assertEquals("缓存值", redis.opsForValue().get(key));
            Long remaining = redis.getExpire(key);
            assertNotNull(remaining);
            assertTrue(remaining > 0 && remaining <= 120);
        } finally {
            redis.delete(key);
        }
        assertFalse(redis.hasKey(key));
    }

    /** 超出配置容量会回收，读取计入统计，写入过期策略由绑定属性生效。 */
    @Test
    void caffeineIsBoundedAndRecordsHits() {
        String prefix = "stratum:it:" + UUID.randomUUID() + ":";
        try {
            local.put(prefix + "1", "值一");
            assertEquals("值一", local.getIfPresent(prefix + "1"));
            local.put(prefix + "2", "值二");
            local.put(prefix + "3", "值三");
            local.cleanUp();
            assertTrue(local.estimatedSize() <= 2);
            assertTrue(local.stats().hitCount() > 0);
            assertEquals(Duration.ofSeconds(30), local.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
        } finally {
            for (int index = 1; index <= 3; index++) { local.invalidate(prefix + index); }
        }
    }
}
