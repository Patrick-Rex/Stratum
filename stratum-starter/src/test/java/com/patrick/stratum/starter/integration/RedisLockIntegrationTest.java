package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.patrick.stratum.common.base.lock.DistributedLock;
import com.patrick.stratum.infrastructure.lock.RedisDistributedLockAdapterImpl;
import com.patrick.stratum.infrastructure.lock.RedisLockProperties;
import com.patrick.stratum.infrastructure.lock.LockMetrics;
import com.patrick.stratum.common.trace.TraceIdContext;
import io.micrometer.core.instrument.MeterRegistry;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实 Redis 验证租约唯一性、到期接管、token 安全释放及有界等待。 */
@TestPropertySource(properties = {"stratum.lock.acquire-wait-timeout=200ms", "stratum.lock.poll-interval=10ms"})
@ExtendWith(OutputCaptureExtension.class)
class RedisLockIntegrationTest extends MariaDbIntegrationTest {
    private final DistributedLock lock;
    private final StringRedisTemplate redis;
    private final LockMetrics metrics;
    private final MeterRegistry registry;

    /**
     * 注入应用实际分布式锁与断言客户端。
     * @param lock 锁适配器
     * @param redis Redis 客户端
     * @param metrics 锁观测组件
     * @param registry 指标注册表
     */
    @Autowired
    RedisLockIntegrationTest(DistributedLock lock, StringRedisTemplate redis, LockMetrics metrics, MeterRegistry registry) {
        this.lock = lock;
        this.redis = redis;
        this.metrics = metrics;
        this.registry = registry;
    }

    /** 默认租期为 30 秒，其他持有者无法获得同一键，重复释放不会误成功。 */
    @Test
    void leaseIsExclusiveAndHasTtl() {
        String key = key();
        try (var lease = lock.tryAcquire(key).orElseThrow()) {
            Long ttl = redis.getExpire(key);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 30);
            assertTrue(lock.tryAcquire(key).isEmpty());
            assertTrue(lease.release());
            assertFalse(lease.release());
        } finally { redis.delete(key); }
    }

    /** 旧租约到期被新持有者接管后，旧 token 无法删除新锁。 */
    @Test
    void expiredOwnerCannotReleaseSuccessor() {
        String key = key();
        try (var old = lock.tryAcquire(key).orElseThrow()) {
            assertTrue(redis.expire(key, Duration.ofMillis(20)));
            try (var successor = lock.acquire(key).orElseThrow()) {
                assertFalse(old.release());
                assertTrue(redis.hasKey(key));
                assertTrue(lock.tryAcquire(key).isEmpty());
                assertTrue(successor.release());
            }
        } finally { redis.delete(key); }
    }

    /** 等待受上限约束，失败不会把别人的锁删除。 */
    @Test
    void contentionTimesOutWithoutStealingLock() {
        String key = key();
        try (var lease = lock.tryAcquire(key).orElseThrow()) {
            long started = System.nanoTime();
            assertTrue(lock.acquire(key).isEmpty());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs >= 150 && elapsedMs < 2000);
            assertTrue(redis.hasKey(key));
            assertTrue(lease.release());
        } finally { redis.delete(key); }
    }

    /** 同时竞争的两个调用方只能一个成为持有者。
     * @throws Exception 并发任务执行失败或超时时抛出
     */
    @Test
    void concurrentAcquisitionHasOneOwner() throws Exception {
        String key = key();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { assertTrue(start.await(2, TimeUnit.SECONDS)); return lock.tryAcquire(key); });
            var second = executor.submit(() -> { assertTrue(start.await(2, TimeUnit.SECONDS)); return lock.tryAcquire(key); });
            start.countDown();
            var firstLease = first.get(2, TimeUnit.SECONDS);
            var secondLease = second.get(2, TimeUnit.SECONDS);
            try { assertNotEquals(firstLease.isPresent(), secondLease.isPresent()); }
            finally { firstLease.ifPresent(DistributedLock.Lease::close); secondLease.ifPresent(DistributedLock.Lease::close); }
        } finally { redis.delete(key); }
    }

    /** 首次 Redis 操作迟到时调用方仍按期返回，迟到的租约随后自动释放。
     * @throws Exception 并发验证失败或超时时抛出
     */
    @Test
    void lateAcquisitionIsReleasedAfterCallerTimesOut() throws Exception {
        String key = key();
        var started = new CountDownLatch(1);
        var permitRedis = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var properties = new RedisLockProperties(Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofMillis(10), 2);
        try (var delayed = new RedisDistributedLockAdapterImpl(redis, properties, metrics) {
            /** {@inheritDoc} */
            @Override
            protected Optional<DistributedLock.Lease> tryAcquireLease(String lockKey, String traceId) {
                started.countDown();
                assertDoesNotThrow(() -> assertTrue(permitRedis.await(3, TimeUnit.SECONDS)));
                return super.tryAcquireLease(lockKey, traceId).map(lease -> () -> {
                    try { return lease.release(); } finally { released.countDown(); }
                });
            }
        }; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> delayed.acquire(key));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(pending.get(1, TimeUnit.SECONDS).isEmpty());
            permitRedis.countDown();
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertFalse(redis.hasKey(key));
        } finally {
            permitRedis.countDown();
            redis.delete(key);
        }
    }

    /**
     * 指标仅计一次外部等待请求，释放保留原 trace，重复释放不重复计量持有时长。
     * @param output 默认控制台日志
     */
    @Test
    void recordsBoundedMetricsAndOriginalTrace(CapturedOutput output) {
        String key = key();
        var acquired = registry.counter("stratum.lock.acquisitions", "mode", "try", "result", "acquired");
        var unavailable = registry.counter("stratum.lock.acquisitions", "mode", "wait", "result", "unavailable");
        var hold = registry.timer("stratum.lock.hold");
        double beforeAcquired = acquired.count(), beforeUnavailable = unavailable.count();
        long beforeHold = hold.count();
        TraceIdContext.setTraceId("lock-metrics-test");
        try (var lease = lock.tryAcquire(key).orElseThrow()) {
            assertTrue(lock.acquire(key).isEmpty());
            TraceIdContext.clear();
            assertTrue(lease.release());
            assertFalse(lease.release());
            assertEquals(beforeAcquired + 1, acquired.count());
            assertEquals(beforeUnavailable + 1, unavailable.count());
            assertEquals(beforeHold + 1, hold.count());
            assertTrue(output.getOut().contains("lockKey=" + key));
            assertTrue(output.getOut().contains("acquireResult=unavailable"));
            assertTrue(output.getOut().contains("holdMs="));
            assertTrue(output.getOut().contains("traceId=lock-metrics-test"));
            registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("stratum.lock."))
                    .forEach(meter -> assertTrue(meter.getId().getTags().stream()
                            .allMatch(tag -> tag.getKey().equals("mode") || tag.getKey().equals("result"))));
        } finally { TraceIdContext.clear(); redis.delete(key); }
    }

    /**
     * 生成本测试拥有的安全锁键。
     * @return 随机键
     */
    private static String key() { return "lock:cache-rebuild:stratum:it:" + UUID.randomUUID(); }
}
