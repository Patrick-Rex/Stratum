package com.patrick.stratum.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.base.lock.DistributedLock;
import com.patrick.stratum.infrastructure.lock.RedisLockProperties;

/** 装配有界缓存重建资源并在容器关闭时取消自身加载任务。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersionedCacheConfiguration.Settings.class)
public class VersionedCacheConfiguration {
    /**
     * 创建共享 single-flight，租期必须覆盖整个加载等待窗口。
     * @param settings 回源容量与超时配置
     * @param lockProperties Redis 锁配置
     * @return 可关闭的合并器
     * @apiNote 由 Spring 调用，关闭上下文时自动 close。
     */
    @Bean(destroyMethod = "close")
    public SingleFlight<Optional<VersionedCache.Value>> cacheSingleFlight(Settings settings,
            RedisLockProperties lockProperties) {
        if (lockProperties.leaseTtl().compareTo(settings.waitTimeout()) <= 0) {
            throw new IllegalArgumentException("缓存重建锁租期必须大于加载等待窗口");
        }
        return new SingleFlight<>(settings.maximumLoads(), settings.waitTimeout());
    }

    /**
     * 装配供 Application / Query 通过 common 契约使用的缓存门面。
     * @param store Redis 与 L1 存储
     * @param lock 分布式锁
     * @param flight 本地合并器
     * @param settings 重建配置
     * @return 缓存门面
     * @apiNote 调用方注入 VersionedCache，使用 get / putIfNewer / invalidate。
     */
    @Bean
    public VersionedCache versionedCache(RedisVersionedCacheStore store, DistributedLock lock,
            SingleFlight<Optional<VersionedCache.Value>> flight, Settings settings) {
        return new VersionedCacheAdapterImpl(store, lock, flight, settings.waitTimeout());
    }

    /**
     * 有界重建配置，等待上限为 5 秒。
     * @param maximumLoads 同时在运行的加载上限
     * @param waitTimeout 调用方最长等待及回源结果有效窗口
     */
    @ConfigurationProperties("stratum.cache.rebuild")
    public record Settings(@DefaultValue("256") int maximumLoads,
            @DefaultValue("5s") Duration waitTimeout) {
        /**
         * 拒绝无界容量或超出规范的等待时间。
         * @param maximumLoads 加载上限，1 到 10000
         * @param waitTimeout 正数且不超过 5 秒
         * @throws IllegalArgumentException 配置越界时抛出
         * @apiNote 示例：new Settings(256, Duration.ofSeconds(5))。
         */
        public Settings {
            if (maximumLoads < 1 || maximumLoads > 10_000 || waitTimeout == null
                    || waitTimeout.isNegative() || waitTimeout.isZero()
                    || waitTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
                throw new IllegalArgumentException("缓存回源容量或等待时间无效");
            }
        }
    }
}
