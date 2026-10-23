package com.patrick.stratum.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import com.patrick.stratum.common.base.cache.VersionedCache.UnavailableException;
import com.patrick.stratum.common.trace.TraceIdContext;

/**
 * 在进程内合并同键回源；实际未结束的加载始终占用容量，调用方超时不会绕过并发限制。
 * @param <T> 单一缓存实例共享的返回值类型
 */
public final class SingleFlight<T> implements AutoCloseable {
    private final ConcurrentHashMap<String, CompletableFuture<T>> flights = new ConcurrentHashMap<>();
    private final Semaphore loadSlots;
    private final Duration waitTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder merged = new LongAdder();
    private final LongAdder loads = new LongAdder();

    /**
     * 创建容量与等待时间明确的请求合并器。
     * @param maximumLoads 最大实际回源数量
     * @param waitTimeout 每位调用者的最长等待，最大 1 分钟
     * @throws IllegalArgumentException 容量或等待时间非法时抛出
     * @apiNote 示例：new SingleFlight<Optional<Value>>(256, Duration.ofSeconds(5))。
     */
    public SingleFlight(int maximumLoads, Duration waitTimeout) {
        Objects.requireNonNull(waitTimeout, "waitTimeout");
        if (maximumLoads < 1 || maximumLoads > 10_000 || waitTimeout.isNegative() || waitTimeout.isZero()
                || waitTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("回源容量或等待时间无效");
        }
        this.loadSlots = new Semaphore(maximumLoads);
        this.waitTimeout = waitTimeout;
    }

    /**
     * 同键调用复用一个加载结果；回源错误原样传播，等待超时独立于加载生命周期。
     * @param key 合并键
     * @param loader 有界回源行为，不能返回 null
     * @return 共享结果
     * @throws UnavailableException 等待超时、容量耗尽、关闭或取消时抛出
     * @apiNote 示例：flight.execute(key, () -> loadFromDatabase(id))。
     */
    public T execute(String key, Supplier<T> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        if (closed.get()) { throw new UnavailableException("回源组件已关闭", null); }
        var candidate = new CompletableFuture<T>();
        var existing = flights.putIfAbsent(key, candidate);
        CompletableFuture<T> result = existing == null ? candidate : existing;
        if (existing == null) { start(key, loader, candidate); }
        else { merged.increment(); }
        try {
            return result.get(waitTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new UnavailableException("缓存重建等待超时", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("缓存重建等待被中断", interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) { throw runtime; }
            if (failure.getCause() instanceof Error error) { throw error; }
            throw new UnavailableException("缓存重建失败", failure.getCause());
        }
    }

    /**
     * 读取累计合并次数供观测绑定使用。
     * @return 合并次数
     * @apiNote 示例：flight.mergedCount()。
     */
    public long mergedCount() { return merged.sum(); }

    /**
     * 读取累计实际回源次数供观测绑定使用。
     * @return 实际开始的回源次数
     * @apiNote 示例：flight.loadCount()。
     */
    public long loadCount() { return loads.sum(); }

    /**
     * 读取尚未结束的合并键数量。
     * @return 进行中的回源数量
     * @apiNote 示例：flight.inFlightCount()。
     */
    public int inFlightCount() { return flights.size(); }

    /**
     * 停止接收请求，中断自身回源任务并唤醒等待者；回源函数必须支持取消或 I/O 超时。
     * @apiNote 独立实例使用 try-with-resources，Spring 配置 bean 时注册销毁方法。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            flights.values().forEach(result -> result.completeExceptionally(new UnavailableException("回源组件已关闭", null)));
            executor.shutdownNow();
        }
    }

    /**
     * 为新键申请容量并启动回源；拒绝请求时不遗留合并键或许可。
     * @param key 合并键
     * @param loader 回源行为
     * @param result 共享结果
     */
    private void start(String key, Supplier<T> loader, CompletableFuture<T> result) {
        if (closed.get()) {
            flights.remove(key, result);
            result.completeExceptionally(new UnavailableException("回源组件已关闭", null));
            return;
        }
        if (!loadSlots.tryAcquire()) {
            flights.remove(key, result);
            result.completeExceptionally(new UnavailableException("缓存重建容量耗尽", null));
            return;
        }
        String traceId = TraceIdContext.getTraceId().orElse(null);
        try {
            executor.execute(() -> load(key, loader, result, traceId));
        } catch (RuntimeException failure) {
            flights.remove(key, result);
            loadSlots.release();
            result.completeExceptionally(new UnavailableException("无法调度缓存重建", failure));
        }
    }

    /**
     * 执行真实回源；仅在加载实际结束后释放容量，再向等待者发布结果。
     * @param key 合并键
     * @param loader 回源行为
     * @param result 共享结果
     * @param traceId 调用者链路标识，可为空
     */
    private void load(String key, Supplier<T> loader, CompletableFuture<T> result, String traceId) {
        T value = null;
        Throwable failure = null;
        try {
            if (closed.get()) { throw new UnavailableException("回源组件已关闭", null); }
            if (traceId != null) { TraceIdContext.setTraceId(traceId); }
            loads.increment();
            value = Objects.requireNonNull(loader.get(), "回源结果不能为 null");
        } catch (RuntimeException | Error error) { failure = error; }
        finally {
            TraceIdContext.clear();
            flights.remove(key, result);
            loadSlots.release();
        }
        if (failure == null) { result.complete(value); }
        else { result.completeExceptionally(failure); }
    }
}
