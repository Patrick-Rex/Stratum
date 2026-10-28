package com.patrick.stratum.job;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.listeners.JobListenerSupport;
import org.springframework.context.SmartLifecycle;

/**
 * 在Bean销毁前停止调度和实际执行，并有界等待Quartz代理收尾；不提供分布式资格。
 * <p>示例：由固定触发器配置装配，关闭顺序先于锁提供者和数据源。</p>
 */
@Slf4j
public final class QuartzRuntimeLifecycle extends JobListenerSupport implements SmartLifecycle {
    private final List<Scheduler> schedulers;
    private final JobTaskExecutor executor;
    private final List<Scheduler> toStart;
    private final ReentrantLock completion = new ReentrantLock();
    private final Condition changed = completion.newCondition();
    private volatile boolean running;
    private boolean closed;

    /**
     * 固化本实例管理的调度器和需手动启动的子集，构造时不启动线程。
     * @param schedulers 普通与专用调度器，最多三组
     * @param executor 私有共享工作执行器
     * @param toStart 目标核验后由本组件启动的专用调度器
     * @throws IllegalArgumentException 数量、重复项或待启动集合不合法
     * @throws NullPointerException 列表、成员或执行器为空
     * @apiNote 示例：普通组自动启动时传入{@code List.of(relay)}作为待启动列表。
     */
    public QuartzRuntimeLifecycle(List<Scheduler> schedulers, JobTaskExecutor executor, List<Scheduler> toStart) {
        this.schedulers = List.copyOf(schedulers); this.toStart = List.copyOf(toStart);
        if (this.schedulers.isEmpty() || this.schedulers.size() > 3 || this.toStart.size() > 2) {
            throw new IllegalArgumentException("调度器数量不合法");
        }
        for (int i = 0; i < this.schedulers.size(); i++) {
            for (int j = i + 1; j < this.schedulers.size(); j++) {
                if (this.schedulers.get(i) == this.schedulers.get(j)) throw new IllegalArgumentException("管理的调度器重复");
            }
        }
        for (int i = 0; i < this.toStart.size(); i++) {
            Scheduler candidate = this.toStart.get(i);
            if (this.schedulers.stream().noneMatch(managed -> managed == candidate)) throw new IllegalArgumentException("待启动调度器未受管理");
            for (int j = i + 1; j < this.toStart.size(); j++) {
                if (candidate == this.toStart.get(j)) throw new IllegalArgumentException("待启动调度器重复");
            }
        }
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    /**
     * 兼容原有普通组及可选Relay组的构造方式。
     * @param schedulers 普通调度器及可选Relay调度器
     * @param executor 共享工作执行器
     * @param startRelay 是否启动第二个调度器
     * @throws IllegalArgumentException 原有调度器数量与启动要求不一致
     * @apiNote 示例：{@code new QuartzRuntimeLifecycle(List.of(primary, relay), executor, true)}。
     */
    public QuartzRuntimeLifecycle(List<Scheduler> schedulers, JobTaskExecutor executor, boolean startRelay) {
        this(schedulers, executor, legacyTargets(schedulers, startRelay));
    }

    /**
     * 核验旧构造方式并转换为明确的待启动集合。
     * @param schedulers 原有调度器列表
     * @param startRelay 是否启动第二组
     * @return 固定待启动集合
     * @throws IllegalArgumentException 数量与旧约定不一致
     */
    private static List<Scheduler> legacyTargets(List<Scheduler> schedulers, boolean startRelay) {
        java.util.Objects.requireNonNull(schedulers);
        if (schedulers.isEmpty() || schedulers.size() > 2 || (startRelay && schedulers.size() != 2)) {
            throw new IllegalArgumentException("调度器数量与Relay启动要求不一致");
        }
        return startRelay ? List.of(schedulers.getLast()) : List.of();
    }

    /** {@inheritDoc} */
    @Override
    public String getName() { return "stratum-fixed-job-lifecycle"; }

    /** {@inheritDoc} */
    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException failure) {
        completion.lock();
        try { changed.signalAll(); }
        finally { completion.unlock(); }
    }

    /** {@inheritDoc} */
    @Override
    public int getPhase() { return Integer.MAX_VALUE - 1; }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() { return running; }

    /**
     * 固定目标核验后启动专用调度器，失败时回滚所有组并关闭执行器。
     * @throws IllegalStateException 关闭后再次启动或专用组启动失败
     */
    @Override
    public synchronized void start() {
        if (running) return;
        if (closed) throw new IllegalStateException("已关闭的调度生命周期不能重新启动");
        running = true;
        try { for (var scheduler : toStart) scheduler.start(); }
        catch (SchedulerException | RuntimeException failure) {
            try { stop(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("专用调度器启动失败", failure);
        }
    }

    /**
     * 各组Scheduler先进入standby并中断借用线程，再关闭共享执行器并有限等待代理完成。
     * @apiNote 示例：由Spring生命周期关闭调用，早于Bean destroy。
     */
    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false; closed = true;
        boolean interrupted = Thread.interrupted(); boolean executorClosed = false;
        try {
            for (var scheduler : schedulers) {
                try { scheduler.standby(); }
                catch (SchedulerException | RuntimeException failure) { logFailure(failure); }
            }
            for (var scheduler : schedulers) {
                try {
                    for (var context : scheduler.getCurrentlyExecutingJobs()) {
                        if (context.getJobDetail().getJobClass() == QuartzDispatchJob.class) scheduler.interrupt(context.getFireInstanceId());
                    }
                } catch (SchedulerException | RuntimeException failure) { logFailure(failure); }
            }
            executor.close(); executorClosed = true;
            long remaining = Duration.ofSeconds(10).toNanos();
            completion.lock();
            try {
                while (hasRunningDispatch() && remaining > 0) {
                    // Quartz监听回调先于运行列表移除；所有调度器共用同一等待预算。
                    long start = System.nanoTime();
                    try { changed.awaitNanos(Math.min(remaining, Duration.ofMillis(100).toNanos())); }
                    catch (InterruptedException signal) { interrupted = true; }
                    remaining -= System.nanoTime() - start;
                }
                if (hasRunningDispatch()) log.warn("固定调度收尾超过关闭预算");
            } finally { completion.unlock(); }
        } finally {
            try { if (!executorClosed) executor.close(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /**
     * 安全记录单个调度器停止失败，不阻断另一组停止和共享执行器清理。
     * @param failure Quartz状态异常
     */
    private void logFailure(Exception failure) {
        log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("停止固定调度失败");
    }

    /**
     * 逐组核对实际运行列表；不可读的组按未确认处理，直至共同预算耗尽。
     * @return 有运行中驱动或无法确认时为true
     */
    private boolean hasRunningDispatch() {
        boolean pending = false;
        for (var scheduler : schedulers) {
            try {
                if (scheduler.getCurrentlyExecutingJobs().stream().anyMatch(c -> c.getJobDetail().getJobClass() == QuartzDispatchJob.class)) pending = true;
            } catch (SchedulerException | RuntimeException unavailable) { pending = true; }
        }
        return pending;
    }
}
