package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.patrick.stratum.common.base.cache.VersionedCache.Policy;
import com.patrick.stratum.common.base.cache.VersionedCache.Value;
import com.patrick.stratum.infrastructure.cache.LocalCacheProperties;
import com.patrick.stratum.infrastructure.cache.RedisVersionedCacheStore;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 Redis 的版本比较、失效屏障、空值保护与跨实例 L1 更新可见性。 */
class VersionedCacheStoreIntegrationTest extends MariaDbIntegrationTest {
    private static final Policy POLICY = new Policy(Duration.ofMinutes(5), Duration.ofSeconds(30));
    private final RedisVersionedCacheStore store;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final List<String> keys = new ArrayList<>();

    /**
     * 装配真实存储及断言客户端。
     * @param store 应用缓存存储
     * @param redis Redis 客户端
     * @param json 序列化器
     */
    @Autowired
    VersionedCacheStoreIntegrationTest(RedisVersionedCacheStore store, StringRedisTemplate redis, JsonMapper json) {
        this.store = store;
        this.redis = redis;
        this.json = json;
    }

    /** 正常值具有抖动 TTL，版本记录也必须过期，L1 可复用同一值。 */
    @Test
    void storesValueWithExpiry() {
        String key = key();
        assertTrue(store.putIfNewer(key, new Value(1, "物料数据"), POLICY));
        assertEquals(new Value(1, "物料数据"), store.read(key).value().orElseThrow());
        assertEquals(new Value(1, "物料数据"), store.read(key).value().orElseThrow());
        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl >= 269 && ttl <= 330);
        assertTrue(redis.getExpire("version:" + key.substring(4)) > ttl);
    }

    /** Long 最大值附近也正确比较，不允许 Lua 浮点精度导致旧版本覆盖。 */
    @Test
    void comparesLargeVersionsExactly() {
        String key = key();
        assertTrue(store.putIfNewer(key, new Value(Long.MAX_VALUE - 1, "旧"), POLICY));
        assertTrue(store.putIfNewer(key, new Value(Long.MAX_VALUE, "新"), POLICY));
        assertFalse(store.putIfNewer(key, new Value(Long.MAX_VALUE - 1, "迟到"), POLICY));
        assertEquals("新", store.read(key).value().orElseThrow().payload());
    }

    /** 失效保留最低版本屏障，同时允许同版本数据库重建。 */
    @Test
    void invalidationPreventsStaleRepopulation() {
        String key = key();
        store.putIfNewer(key, new Value(1, "旧"), POLICY);
        assertTrue(store.invalidate(key, 2, POLICY));
        assertFalse(store.read(key).hit());
        assertFalse(store.putIfNewer(key, new Value(1, "迟到旧值"), POLICY));
        assertTrue(store.putIfNewer(key, new Value(2, "最新"), POLICY));
        assertFalse(store.invalidate(key, 1, POLICY));
        assertEquals("最新", store.read(key).value().orElseThrow().payload());
    }

    /** 空值固定 60 秒，回源期间发生的并发新增不能被空值覆盖。 */
    @Test
    void missingValueIsCachedOnlyWhileStampIsUnchanged() {
        String key = key();
        var missing = store.read(key);
        assertFalse(missing.hit());
        assertTrue(store.putAbsent(key, missing.stamp(), POLICY));
        assertTrue(store.read(key).hit());
        assertTrue(store.read(key).value().isEmpty());
        Long ttl = redis.getExpire("null:" + key.substring(4));
        assertNotNull(ttl);
        assertTrue(ttl >= 58 && ttl <= 60);
        var beforeCreation = store.read(key);
        assertTrue(store.putIfNewer(key, new Value(0, "新建"), POLICY));
        assertFalse(store.putAbsent(key, beforeCreation.stamp(), POLICY));
        assertEquals("新建", store.read(key).value().orElseThrow().payload());
    }

    /** 第二客户端不共享 L1；远端更新或同版本失效后第一客户端立即识别标记变化。 */
    @Test
    void remoteMutationInvalidatesOtherClientLocalSnapshot() {
        String key = key();
        var other = new RedisVersionedCacheStore(redis, Caffeine.newBuilder().maximumSize(10).build(), json,
                new LocalCacheProperties(10, Duration.ofSeconds(30)));
        store.putIfNewer(key, new Value(1, "旧"), POLICY);
        assertEquals("旧", store.read(key).value().orElseThrow().payload());
        other.putIfNewer(key, new Value(2, "新"), POLICY);
        assertEquals("新", store.read(key).value().orElseThrow().payload());
        other.invalidate(key, 2, POLICY);
        assertFalse(store.read(key).hit());
    }

    /** 仅删除本测试的随机键及其关联空值、版本记录。 */
    @AfterEach
    void cleanOwnKeys() {
        for (String key : keys) {
            redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
        }
    }

    /**
     * 生成本测试拥有的业务缓存键。
     * @return 随机键
     */
    private String key() {
        String key = "biz:stratum:it:" + UUID.randomUUID();
        keys.add(key);
        return key;
    }
}
