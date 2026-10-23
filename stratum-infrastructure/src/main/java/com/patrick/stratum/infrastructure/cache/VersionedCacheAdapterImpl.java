package com.patrick.stratum.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.base.lock.DistributedLock;

import lombok.RequiredArgsConstructor;

/** 组合版本存储、本地请求合并与跨实例互斥的 Cache Aside 适配器。 */
@RequiredArgsConstructor
public class VersionedCacheAdapterImpl implements VersionedCache {
    private final RedisVersionedCacheStore store;
    private final DistributedLock lock;
    private final SingleFlight<Optional<Value>> singleFlight;
    private final Duration rebuildTimeout;
    private final CacheMetrics metrics;

    /** {@inheritDoc} */
    @Override
    public Optional<Value> get(String key, Policy policy, Supplier<Optional<Value>> loader) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(loader, "loader");
        try {
            var cached = store.read(key);
            metrics.access(key, cached.hit());
            if (cached.hit()) { return cached.value(); }
            long deadline = System.nanoTime() + rebuildTimeout.toNanos();
            return singleFlight.execute(key, () -> rebuild(key, policy, loader, deadline));
        } catch (RuntimeException | Error failure) {
            metrics.failed();
            throw failure;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean putIfNewer(String key, Value value, Policy policy) {
        return store.putIfNewer(key, value, policy);
    }

    /** {@inheritDoc} */
    @Override
    public void invalidate(String key, long minimumVersion, Policy policy) {
        store.invalidate(key, minimumVersion, policy);
    }

    /**
     * 持锁后再次检查，回源仅在时间预算内且远端标记未变时回填。
     * @param key 缓存键
     * @param policy TTL 策略
     * @param loader 有界回源函数
     * @param deadline 本轮重建单调截止时间
     * @return 有效结果或已缓存空值
     * @throws UnavailableException 竞争超时、结果过期或已被并发失效时抛出
     */
    private Optional<Value> rebuild(String key, Policy policy, Supplier<Optional<Value>> loader, long deadline) {
        checkDeadline(deadline);
        var cached = store.read(key);
        if (cached.hit()) { return cached.value(); }
        var lease = lock.acquire("lock:cache-rebuild:" + key.substring(4));
        if (lease.isEmpty()) {
            var latest = store.read(key);
            if (latest.hit()) { return latest.value(); }
            throw new UnavailableException("缓存重建锁等待超时或容量耗尽", null);
        }
        try (var ignored = lease.orElseThrow()) {
            checkDeadline(deadline);
            var beforeLoad = store.read(key);
            if (beforeLoad.hit()) { return beforeLoad.value(); }
            checkDeadline(deadline);
            var loaded = metrics.load(key, loader);
            checkDeadline(deadline);
            boolean accepted = loaded.isPresent()
                    ? store.putIfUnchanged(key, loaded.orElseThrow(), policy, beforeLoad.stamp())
                    : store.putAbsent(key, beforeLoad.stamp(), policy);
            if (accepted) { return loaded; }
            var latest = store.read(key);
            if (latest.hit()) { return latest.value(); }
            throw new UnavailableException("缓存已并发失效，请重新查询", null);
        }
    }

    /**
     * 拒绝迟到及中断后的回源结果，禁止把超时当作不存在或继续写缓存。
     * @param deadline 单调截止时间
     * @throws UnavailableException 时间预算耗尽或线程被中断时抛出
     */
    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0) {
            throw new UnavailableException("缓存重建结果已超过等待窗口", null);
        }
    }
}
