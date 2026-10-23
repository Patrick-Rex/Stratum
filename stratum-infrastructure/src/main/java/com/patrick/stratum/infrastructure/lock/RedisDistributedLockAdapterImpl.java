package com.patrick.stratum.infrastructure.lock;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.patrick.stratum.common.base.lock.DistributedLock;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.infrastructure.lock.LockMetrics.Mode;
import com.patrick.stratum.infrastructure.lock.LockMetrics.Acquisition;
import com.patrick.stratum.infrastructure.lock.LockMetrics.Release;

import lombok.extern.slf4j.Slf4j;

/** 使用 Redis 原子加锁和比较 token 释放；等待由容量受限的调度器驱动，可随容器关闭。 */
@Slf4j
@Component
@EnableConfigurationProperties(RedisLockProperties.class)
public class RedisDistributedLockAdapterImpl implements DistributedLock, AutoCloseable {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final RedisLockProperties properties;
    private final LockMetrics metrics;
    private final Semaphore waitSlots;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Set<WaitRequest> active = ConcurrentHashMap.newKeySet();

    /**
     * 装配 Redis 客户端与有界等待资源。
     * @param redis 字符串客户端
     * @param properties 锁配置
     * @param metrics 锁指标与链路日志
     * @apiNote 由 Spring 创建并在关闭时回收调度线程。
     */
    public RedisDistributedLockAdapterImpl(StringRedisTemplate redis, RedisLockProperties properties, LockMetrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
        this.waitSlots = new Semaphore(properties.maximumWaiters());
        this.scheduler = new ScheduledThreadPoolExecutor(2,
                Thread.ofPlatform().daemon(true).name("stratum-lock-wait-", 0).factory());
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Lease> tryAcquire(String key) {
        return observedAcquire(key, Mode.TRY, () -> tryAcquireLease(key, TraceIdContext.getTraceId().orElse("-")));
    }

    /**
     * 执行一次原子尝试，供有界等待复用；内部轮询不重复计为调用者加锁请求。
     * @param key 锁键
     * @param traceId 原调用者链路标识
     * @return 当前获得的租约或竞争失败
     * @apiNote 子类故障测试可延迟此步骤，生产调用使用 tryAcquire / acquire。
     */
    protected Optional<Lease> tryAcquireLease(String key, String traceId) {
        if (key == null || !key.startsWith("lock:") || !key.matches("[A-Za-z0-9:_.-]{1,256}")) {
            throw new IllegalArgumentException("锁键必须以 lock: 开头且仅包含安全字符，最长 256 字符");
        }
        if (scheduler.isShutdown()) { throw new IllegalStateException("分布式锁组件已关闭"); }
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, properties.leaseTtl()))) {
            return Optional.empty();
        }
        return Optional.of(new RedisLease(key, token, traceId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Lease> acquire(String key) {
        return observedAcquire(key, Mode.WAIT, () -> awaitLease(key));
    }

    /**
     * 统一记录外部获取调用的结果和等待耗时，保留原异常。
     * @param key 锁键
     * @param mode 请求方式
     * @param action 获取动作
     * @return 获取结果
     */
    private Optional<Lease> observedAcquire(String key, Mode mode, Supplier<Optional<Lease>> action) {
        long start = System.nanoTime();
        String traceId = TraceIdContext.getTraceId().orElse("-");
        Acquisition outcome = Acquisition.ERROR;
        try {
            var lease = action.get();
            outcome = lease.isPresent() ? Acquisition.ACQUIRED
                    : mode == Mode.TRY ? Acquisition.CONTENDED : Acquisition.UNAVAILABLE;
            return lease;
        } catch (CancellationException cancelled) {
            outcome = Acquisition.CANCELLED;
            throw cancelled;
        } finally { metrics.acquire(key, mode, outcome, System.nanoTime() - start, traceId); }
    }

    /**
     * 有界等待租约，超时或容量耗尽时返回空结果。
     * @param key 锁键
     * @return 租约或空结果
     */
    private Optional<Lease> awaitLease(String key) {
        long deadline = System.nanoTime() + properties.acquireWaitTimeout().toNanos();
        String traceId = TraceIdContext.getTraceId().orElse("-");
        if (!waitSlots.tryAcquire()) {
            return Optional.empty();
        }
        var request = new WaitRequest(key, deadline, traceId);
        active.add(request);
        request.result.whenComplete((lease, failure) -> {
            var scheduled = request.scheduled.get();
            if (scheduled != null && scheduled.task != null) { scheduled.task.cancel(false); }
            active.remove(request);
            waitSlots.release();
        });
        request.schedule(0);
        try {
            return request.result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            request.result.complete(Optional.empty());
            request.result.thenAccept(lease -> lease.ifPresent(Lease::close));
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            request.result.complete(Optional.empty());
            request.result.thenAccept(lease -> lease.ifPresent(Lease::close));
            Thread.currentThread().interrupt();
            throw new CancellationException("锁等待被中断");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) { throw runtime; }
            if (failure.getCause() instanceof Error error) { throw error; }
            throw new IllegalStateException("锁等待失败", failure.getCause());
        }
    }

    /**
     * 取消待处理请求并关闭自身调度器，不关闭共享 Redis 客户端。
     * @apiNote Spring 销毁组件时自动调用；独立实例使用 try-with-resources。
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        active.forEach(request -> request.result.completeExceptionally(new CancellationException("锁组件关闭")));
    }

    /** 保存私有随机 token 的短租约，不把凭据暴露给调用者。 */
    private final class RedisLease implements Lease {
        private final String key;
        private final String token;
        private final String traceId;
        private final long acquiredAt = System.nanoTime();
        private final AtomicBoolean terminalMeasured = new AtomicBoolean();

        /**
         * 记录当前持有者。
         * @param key 锁键
         * @param token 唯一持有者凭据
         * @param traceId 原调用者链路标识
         */
        private RedisLease(String key, String token, String traceId) {
            this.key = key;
            this.token = token;
            this.traceId = traceId;
        }

        /** {@inheritDoc} */
        @Override
        public boolean release() {
            Release outcome = Release.ERROR;
            boolean firstTerminal = false;
            try {
                boolean released = Long.valueOf(1).equals(redis.execute(RELEASE, List.of(key), token));
                outcome = released ? Release.RELEASED : Release.STALE;
                firstTerminal = terminalMeasured.compareAndSet(false, true);
                return released;
            } finally {
                long held = Math.min(System.nanoTime() - acquiredAt, properties.leaseTtl().toNanos());
                metrics.release(key, outcome, held, firstTerminal, traceId);
            }
        }
    }

    /** 调度前登记的任务槽，避免零延迟首次尝试覆盖后继任务的取消句柄。 */
    private static final class PendingAttempt {
        private volatile ScheduledFuture<?> task;
    }

    /** 单个等待者，最多保留一个已调度任务，完成后立即回收容量。 */
    private final class WaitRequest {
        private final String key;
        private final long deadline;
        private final String traceId;
        private final CompletableFuture<Optional<Lease>> result = new CompletableFuture<>();
        private final AtomicReference<PendingAttempt> scheduled = new AtomicReference<>();

        /**
         * 记录请求的单调截止时间与日志上下文。
         * @param key 锁键
         * @param deadline 纳秒截止时间
         * @param traceId 调用者链路标识
         */
        private WaitRequest(String key, long deadline, String traceId) {
            this.key = key;
            this.deadline = deadline;
            this.traceId = traceId;
        }

        /**
         * 安排一次尝试；请求结束或组件关闭时不留下定时任务。
         * @param delayNanos 首次为零，后续为配置的轮询间隔
         */
        private void schedule(long delayNanos) {
            if (result.isDone()) { return; }
            try {
                var attempt = new PendingAttempt();
                scheduled.set(attempt);
                var task = scheduler.schedule(this::poll, delayNanos, TimeUnit.NANOSECONDS);
                attempt.task = task;
                if (result.isDone()) { task.cancel(false); }
            } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        }

        /** 在截止时间内尝试获取；超时后迟到的成功必须释放，避免泄漏租约。 */
        private void poll() {
            if (result.isDone()) { return; }
            if (System.nanoTime() >= deadline) {
                result.complete(Optional.empty());
                return;
            }
            try {
                var lease = tryAcquireLease(key, traceId);
                if (lease.isPresent()) {
                    if (System.nanoTime() >= deadline || !result.complete(lease)) {
                        lease.get().close();
                        result.complete(Optional.empty());
                    }
                } else { schedule(properties.pollInterval().toNanos()); }
            } catch (RuntimeException | Error failure) {
                if (!result.completeExceptionally(failure)) {
                    log.warn("锁迟到请求清理失败 lockKey={} traceId={}", key, traceId, failure);
                }
            }
        }
    }
}
