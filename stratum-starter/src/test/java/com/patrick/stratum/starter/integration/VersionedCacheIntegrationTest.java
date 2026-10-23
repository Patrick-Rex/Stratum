package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.base.cache.VersionedCache.Policy;
import com.patrick.stratum.common.base.cache.VersionedCache.Value;
import com.patrick.stratum.common.base.cache.VersionedCache.UnavailableException;
import com.patrick.stratum.common.base.lock.DistributedLock;
import com.patrick.stratum.infrastructure.cache.LocalCacheProperties;
import com.patrick.stratum.infrastructure.cache.RedisVersionedCacheStore;
import com.patrick.stratum.infrastructure.cache.SingleFlight;
import com.patrick.stratum.infrastructure.cache.VersionedCacheAdapterImpl;
import com.patrick.stratum.infrastructure.cache.CacheMetrics;
import com.patrick.stratum.common.trace.TraceIdContext;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 通过独立 L1 与合并器模拟多个应用实例，验证 Redis 互斥与回源竞争边界。 */
@ExtendWith(OutputCaptureExtension.class)
class VersionedCacheIntegrationTest extends MariaDbIntegrationTest {
    private static final Policy POLICY = new Policy(Duration.ofMinutes(5), Duration.ofSeconds(30));
    private final VersionedCache cache;
    private final RedisVersionedCacheStore store;
    private final DistributedLock lock;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CacheMetrics metrics;
    private final MeterRegistry registry;
    private final List<String> keys = new ArrayList<>();

    /**
     * 装配完整门面与用于竞争断言的底层客户端。
     * @param cache 缓存门面
     * @param store 缓存存储
     * @param lock Redis 锁
     * @param redis Redis 客户端
     * @param json 序列化器
     * @param metrics 缓存观测组件
     * @param registry 指标注册表
     */
    @Autowired
    VersionedCacheIntegrationTest(VersionedCache cache, RedisVersionedCacheStore store, DistributedLock lock,
            StringRedisTemplate redis, JsonMapper json, CacheMetrics metrics, MeterRegistry registry) {
        this.cache = cache;
        this.store = store;
        this.lock = lock;
        this.redis = redis;
        this.json = json;
        this.metrics = metrics;
        this.registry = registry;
    }

    /** 多实例独立合并器竞争同一个键时，只有获得锁并二次检查未命中的实例回源。 */
    @Test
    void twoInstancesShareOneOriginLoad() throws Exception {
        String key = key();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loads = new AtomicInteger();
        try (var otherFlight = new SingleFlight<Optional<Value>>(8, Duration.ofSeconds(5));
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var other = new VersionedCacheAdapterImpl(otherStore(), lock, otherFlight, Duration.ofSeconds(5), metrics);
            java.util.function.Supplier<Optional<Value>> loader = () -> {
                loads.incrementAndGet();
                entered.countDown();
                await(release);
                return Optional.of(new Value(1, "共享结果"));
            };
            var first = callers.submit(() -> cache.get(key, POLICY, loader));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var requests = new ArrayList<java.util.concurrent.Future<Optional<Value>>>();
            for (int i = 0; i < 16; i++) {
                VersionedCache target = i % 2 == 0 ? other : cache;
                requests.add(callers.submit(() -> target.get(key, POLICY, loader)));
            }
            try {
                awaitFlight(otherFlight);
            } finally { release.countDown(); }
            assertEquals("共享结果", first.get(5, TimeUnit.SECONDS).orElseThrow().payload());
            for (var request : requests) {
                assertEquals(first.get(), request.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
        } finally { release.countDown(); }
    }

    /** 已确认不存在的对象复用空值缓存，不再次访问数据库。 */
    @Test
    void missingValueIsReused() {
        String key = key();
        var loads = new AtomicInteger();
        assertTrue(cache.get(key, POLICY, () -> { loads.incrementAndGet(); return Optional.empty(); }).isEmpty());
        assertTrue(cache.get(key, POLICY, () -> { fail("空值缓存应命中"); return Optional.empty(); }).isEmpty());
        assertEquals(1, loads.get());
        assertTrue(redis.getExpire("null:" + key.substring(4)) > 0);
    }

    /** 最长合法缓存键仍能生成合法重建锁；超长键在所有缓存操作入口一致拒绝。 */
    @Test
    void longestKeyCanRebuild() {
        String prefix = "biz:stratum:it:" + UUID.randomUUID();
        String key = prefix + "x".repeat(241 - prefix.length());
        keys.add(key);
        assertEquals(new Value(1, "边界"), cache.get(key, POLICY,
                () -> Optional.of(new Value(1, "边界"))).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> cache.get(key + "x", POLICY, Optional::empty));
        assertThrows(IllegalArgumentException.class,
                () -> cache.putIfNewer(key + "x", new Value(1, "非法"), POLICY));
    }

    /** 调用超时抛出明确异常，迟到的数据库结果不能回填缓存。 */
    @Test
    void lateOriginResultIsDiscarded() throws Exception {
        String key = key();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var flight = new SingleFlight<Optional<Value>>(1, Duration.ofMillis(200))) {
            var shortWait = new VersionedCacheAdapterImpl(otherStore(), lock, flight, Duration.ofMillis(200), metrics);
            try {
                assertThrows(UnavailableException.class, () -> shortWait.get(key, POLICY, () -> {
                    entered.countDown();
                    await(release);
                    return Optional.of(new Value(1, "迟到"));
                }));
                assertEquals(0, entered.getCount());
                assertEquals(1, flight.inFlightCount());
            } finally { release.countDown(); }
            awaitIdle(flight);
            assertFalse(store.read(key).hit());
        }
    }

    /** 并发提交的新值优先于仍在执行的旧回源，调用方拿到新缓存。 */
    @Test
    void concurrentUpdateWinsOverOrigin() throws Exception {
        String key = key();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = callers.submit(() -> cache.get(key, POLICY, () -> {
                entered.countDown();
                await(release);
                return Optional.of(new Value(1, "旧"));
            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                otherStore().putIfNewer(key, new Value(2, "新"), POLICY);
            } finally { release.countDown(); }
            assertEquals(new Value(2, "新"), result.get(5, TimeUnit.SECONDS).orElseThrow());
        }
    }

    /** 同版本失效也禁止旧加载复活缓存；后续请求可重新查询权威数据。 */
    @Test
    void concurrentInvalidationRejectsEvenSameVersionOrigin() throws Exception {
        String key = key();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = callers.submit(() -> assertThrows(UnavailableException.class,
                    () -> cache.get(key, POLICY, () -> {
                        entered.countDown();
                        await(release);
                        return Optional.of(new Value(1, "应被失效"));
                    })));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                otherStore().invalidate(key, 1, POLICY);
            } finally { release.countDown(); }
            result.get(5, TimeUnit.SECONDS);
            assertFalse(store.read(key).hit());
        }
    }

    /** 锁竞争等待超时不能报告业务不存在，也不能绕过锁直接回源。 */
    @Test
    void lockContentionCannotBecomeMissingData() {
        String key = key();
        try (var flight = new SingleFlight<Optional<Value>>(1, Duration.ofMillis(200));
                var lease = lock.tryAcquire("lock:cache-rebuild:" + key.substring(4)).orElseThrow()) {
            var shortWait = new VersionedCacheAdapterImpl(otherStore(), lock, flight, Duration.ofMillis(200), metrics);
            assertThrows(UnavailableException.class, () -> shortWait.get(key, POLICY, () -> {
                fail("不能绕过未获取的锁回源");
                return Optional.empty();
            }));
        }
        assertFalse(store.read(key).hit());
    }

    /** 只清理本测试创建的键，保留共享测试服务。 */
    @AfterEach
    void cleanOwnKeys() {
        TraceIdContext.clear();
        for (String key : keys) {
            redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4),
                    "lock:cache-rebuild:" + key.substring(4)));
        }
    }

    /**
     * 指标区分命中、回源空值与失败，且不以随机键或 traceId 造成无界标签。
     * @param output 当前默认日志格式的实际输出
     */
    @Test
    void measuresActualLoadsAndFailures(CapturedOutput output) {
        var hits = registry.counter("stratum.cache.access", "result", "hit");
        var misses = registry.counter("stratum.cache.access", "result", "miss");
        var loads = registry.counter("stratum.cache.loads");
        var failures = registry.counter("stratum.cache.requests.failed");
        var errors = registry.timer("stratum.cache.load.duration", "result", "error");
        double previousHits = hits.count(), previousMisses = misses.count(), previousLoads = loads.count();
        double previousFailures = failures.count();
        long previousErrors = errors.count();
        String key = key();
        TraceIdContext.setTraceId("cache-metrics-test");
        cache.get(key, POLICY, () -> {
            assertEquals("cache-metrics-test", TraceIdContext.getTraceId().orElseThrow());
            return Optional.empty();
        });
        cache.get(key, POLICY, () -> { fail("应复用空值"); return Optional.empty(); });
        assertThrows(IllegalStateException.class,
                () -> cache.get(key(), POLICY, () -> { throw new IllegalStateException("回源失败"); }));
        assertEquals(previousHits + 1, hits.count());
        assertEquals(previousMisses + 2, misses.count());
        assertEquals(previousLoads + 2, loads.count());
        assertEquals(previousFailures + 1, failures.count());
        assertEquals(previousErrors + 1, errors.count());
        assertNotNull(registry.find("stratum.cache.singleflight.merged").functionCounter());
        assertNotNull(registry.find("stratum.cache.singleflight.inflight").gauge());
        assertTrue(output.getOut().contains("traceId=cache-metrics-test"));
        assertTrue(output.getOut().contains("cacheKey=" + key));
        assertTrue(output.getOut().contains("loadDurationMs="));
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("stratum.cache."))
                .forEach(meter -> assertTrue(meter.getId().getTags().stream().allMatch(tag -> tag.getKey().equals("result"))));
    }

    /**
     * 创建不共享本地缓存的第二实例存储。
     * @return 独立 L1 的存储
     */
    private RedisVersionedCacheStore otherStore() {
        return new RedisVersionedCacheStore(redis, Caffeine.newBuilder().maximumSize(32).build(), json,
                new LocalCacheProperties(32, Duration.ofSeconds(30)));
    }

    /**
     * 生成归本测试所有的缓存键。
     * @return 随机业务键
     */
    private String key() {
        String key = "biz:stratum:it:" + UUID.randomUUID();
        keys.add(key);
        return key;
    }

    /**
     * 用有界闩锁同步回源步骤，不以睡眠猜测并发时序。
     * @param latch 等待信号
     */
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("测试回源已中断", interrupted);
        }
    }

    /**
     * 等待第二实例进入重建，确保测试实际发生跨实例竞争。
     * @param flight 第二实例合并器
     * @throws Exception 等待失败时抛出
     */
    private static void awaitFlight(SingleFlight<?> flight) throws Exception {
        awaitCondition(() -> flight.inFlightCount() > 0);
    }

    /**
     * 等待迟到加载真正释放容量，再检查缓存没有被污染。
     * @param flight 合并器
     * @throws Exception 等待失败时抛出
     */
    private static void awaitIdle(SingleFlight<?> flight) throws Exception {
        awaitCondition(() -> flight.inFlightCount() == 0);
    }

    /**
     * 临时调度器观察有界测试条件，并在退出时关闭。
     * @param condition 完成条件
     * @throws Exception 等待超过三秒或线程失败时抛出
     */
    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var watcher = scheduler.scheduleAtFixedRate(() -> {
                if (condition.getAsBoolean()) { completed.complete(null); }
            }, 0, 5, TimeUnit.MILLISECONDS);
            try { completed.get(3, TimeUnit.SECONDS); }
            finally { watcher.cancel(false); }
        }
    }
}
