package com.patrick.stratum.common.base.cache;

import java.time.Duration;

/**
 * 缓存补偿载荷，仅携带失效信息，不复制业务数据；重复失效不会覆盖更新版本。
 * @param key 业务缓存键
 * @param minimumVersion 最低可接受版本
 * @param ttlMillis 基础 TTL 毫秒数
 * @param jitterMillis TTL 抖动毫秒数
 */
public record CacheInvalidation(String key, long minimumVersion, long ttlMillis, long jitterMillis) {
    public static final String EVENT_TYPE = "stratum.cache.invalidate";

    /**
     * 验证补偿参数，确保失败发生在业务事务提交之前。
     * @param key 业务缓存键
     * @param minimumVersion 最低非负版本
     * @param ttlMillis 基础 TTL
     * @param jitterMillis 抖动范围
     * @throws IllegalArgumentException 字段非法时抛出
     * @apiNote 示例：new CacheInvalidation(key, 2, 300000, 30000)。
     */
    public CacheInvalidation {
        VersionedCache.validateKey(key);
        if (minimumVersion < 0) { throw new IllegalArgumentException("补偿版本不能为负数"); }
        new VersionedCache.Policy(Duration.ofMillis(ttlMillis), Duration.ofMillis(jitterMillis));
    }

    /**
     * 恢复已校验的过期策略，供补偿消费者复用。
     * @return 缓存策略
     * @apiNote 示例：cache.invalidate(event.key(), event.minimumVersion(), event.policy())。
     */
    public VersionedCache.Policy policy() {
        return new VersionedCache.Policy(Duration.ofMillis(ttlMillis), Duration.ofMillis(jitterMillis));
    }
}
