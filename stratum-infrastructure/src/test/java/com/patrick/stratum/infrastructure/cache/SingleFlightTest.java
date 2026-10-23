package com.patrick.stratum.infrastructure.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.patrick.stratum.common.base.cache.VersionedCache.UnavailableException;
import com.patrick.stratum.common.trace.TraceIdContext;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实线程并发下的合并、失败恢复、超时容量保持及资源关闭。 */
class SingleFlightTest {

    /** 八个同键请求共用一次加载，并保留调用者 traceId。
     * @throws Exception 并发任务失败或超时时抛出
     */
    @Test
    void concurrentRequestsShareOneLoad() throws Exception {
        var release = new CountDownLatch(1);
        try (var flight = new SingleFlight<String>(2, Duration.ofSeconds(3));
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(callers.submit(() -> {
                    TraceIdContext.setTraceId("single-flight-trace");
                    try {
                        return flight.execute("key", () -> {
                            assertEquals("single-flight-trace", TraceIdContext.getRequiredTraceId());
                            assertDoesNotThrow(() -> assertTrue(release.await(2, TimeUnit.SECONDS)));
                            return "shared";
                        });
                    } finally { TraceIdContext.clear(); }
                }));
            }
            awaitCondition(() -> flight.mergedCount() == 7);
            release.countDown();
            for (var result : results) { assertEquals("shared", result.get(2, TimeUnit.SECONDS)); }
            assertEquals(1, flight.loadCount());
            assertEquals(0, flight.inFlightCount());
        } finally { release.countDown(); }
    }

    /** 失败移除合并键并释放容量，后续重试可以成功。 */
    @Test
    void failedLoadCanBeRetried() {
        try (var flight = new SingleFlight<String>(1, Duration.ofSeconds(1))) {
            var failure = new IllegalStateException("回源失败");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> flight.execute("key", () -> { throw failure; })));
            assertEquals("recovered", flight.execute("key", () -> "recovered"));
            assertEquals(2, flight.loadCount());
        }
    }

    /** 调用方超时不释放仍在运行的加载容量，避免慢 I/O 形成无界线程。
     * @throws Exception 条件等待超时时抛出
     */
    @Test
    void timeoutDoesNotReleaseRunningLoadCapacity() throws Exception {
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var flight = new SingleFlight<String>(1, Duration.ofMillis(50))) {
            assertThrows(UnavailableException.class, () -> flight.execute("slow", () -> {
                calls.incrementAndGet();
                assertDoesNotThrow(() -> assertTrue(release.await(2, TimeUnit.SECONDS)));
                return "late";
            }));
            assertThrows(UnavailableException.class,
                    () -> flight.execute("other", () -> { calls.incrementAndGet(); return "unexpected"; }));
            assertEquals(1, calls.get());
            assertEquals(1, flight.inFlightCount());
            release.countDown();
            awaitCondition(() -> flight.inFlightCount() == 0);
            assertEquals("next", flight.execute("other", () -> "next"));
        } finally { release.countDown(); }
    }

    /** 关闭会唤醒等待者并拒绝新请求，不把取消当作空缓存。
     * @throws Exception 并发验证失败或超时时抛出
     */
    @Test
    void closingCancelsWaitersAndRejectsNewWork() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var flight = new SingleFlight<String>(1, Duration.ofSeconds(3));
                var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = caller.submit(() -> assertThrows(UnavailableException.class, () -> flight.execute("key", () -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return "cancelled";
            })));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            flight.close();
            result.get(1, TimeUnit.SECONDS);
            assertThrows(UnavailableException.class, () -> flight.execute("new", () -> "unexpected"));
        } finally { release.countDown(); }
    }

    /**
     * 通过可关闭调度器等待可观测并发条件，不使用固定休眠猜测执行顺序。
     * @param condition 要等待的条件
     * @throws Exception 条件未在两秒内满足时抛出
     */
    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        var done = new CompletableFuture<Void>();
        var watcher = Executors.newSingleThreadScheduledExecutor();
        try {
            watcher.scheduleAtFixedRate(() -> { if (condition.getAsBoolean()) { done.complete(null); } },
                    0, 1, TimeUnit.MILLISECONDS);
            done.get(2, TimeUnit.SECONDS);
        } finally { watcher.shutdownNow(); }
    }
}
