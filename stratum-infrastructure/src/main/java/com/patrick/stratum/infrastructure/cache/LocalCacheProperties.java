package com.patrick.stratum.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 本地缓存容量与失效时间配置，作为后续 Cache Aside 的有界 L1 客户端。
 * @param maximumSize 最大条目数
 * @param ttl 写入后最长存活时间
 */
@ConfigurationProperties("stratum.cache.local")
public record LocalCacheProperties(@DefaultValue("10000") long maximumSize, @DefaultValue("30s") Duration ttl) {

    /**
     * 拒绝无界或不具备有效过期时间的配置。
     * @param maximumSize 最大条目数，必须为正数
     * @param ttl 正数存活时间
     * @throws IllegalArgumentException 容量或存活时间不合法时抛出
     * @apiNote 示例：new LocalCacheProperties(10000, Duration.ofSeconds(30))。
     */
    public LocalCacheProperties {
        Objects.requireNonNull(ttl, "ttl");
        if (maximumSize <= 0 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("缓存容量与 TTL 必须为正数");
        }
    }
}
