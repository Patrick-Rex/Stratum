package com.patrick.stratum.infrastructure.lock;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Redis 短租约与有界等待配置。
 * @param leaseTtl 租期，默认 30 秒
 * @param acquireWaitTimeout 最长等待，默认 5 秒
 * @param pollInterval Redis 再尝试间隔，默认 50 毫秒
 * @param maximumWaiters 最多等待者数量，默认 256
 */
@ConfigurationProperties("stratum.lock")
public record RedisLockProperties(@DefaultValue("30s") Duration leaseTtl,
        @DefaultValue("5s") Duration acquireWaitTimeout, @DefaultValue("50ms") Duration pollInterval,
        @DefaultValue("256") int maximumWaiters) {

    /**
     * 校验等待与租期配置，避免无界资源占用或忙循环。
     * @param leaseTtl 正数租期
     * @param acquireWaitTimeout 正数最长等待
     * @param pollInterval 至少 10 毫秒的重试间隔
     * @param maximumWaiters 1 至 10000 个等待者
     * @throws IllegalArgumentException 配置无效时抛出
     * @apiNote 示例：new RedisLockProperties(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(50), 256)。
     */
    public RedisLockProperties {
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        Objects.requireNonNull(acquireWaitTimeout, "acquireWaitTimeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (leaseTtl.isNegative() || leaseTtl.isZero() || acquireWaitTimeout.isNegative()
                || acquireWaitTimeout.isZero() || pollInterval.compareTo(Duration.ofMillis(10)) < 0
                || maximumWaiters < 1 || maximumWaiters > 10_000) {
            throw new IllegalArgumentException("锁租期、等待时间及容量配置无效");
        }
    }
}
