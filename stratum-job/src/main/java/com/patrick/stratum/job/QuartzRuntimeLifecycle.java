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
    private final boolean startRelay;
    private final ReentrantLock completion = new ReentrantLock();
    private final Condition changed = completion.newCondition();
    private volatile boolean running;

    /**
     * 绑定本实例全部调度器及共享执行器，不在构造中启动线程。
     * @param schedulers 普通调度器及可选的Relay调度器
     * @param executor 私有共享工作执行器
     * @param startRelay 是否在目标核验后启动第二个Relay调度器
     * @throws IllegalArgumentException 调度器数量与启动要求不一致
     * @throws NullPointerException 调度器列表或执行器为空
     * @apiNote 示例：由Spring注入普通调度器和可选Relay调度器。
     */
    public QuartzRuntimeLifecycle(List<Scheduler> schedulers, JobTaskExecutor executor, boolean startRelay) {
        this.schedulers = List.copyOf(schedulers);
        if (this.schedulers.isEmpty() || this.schedulers.size() > 2 || (startRelay && this.schedulers.size() != 2)) {
            throw new IllegalArgumentException("调度器数量与Relay启动要求不一致");
        }
        this.executor = java.util.Objects.requireNonNull(executor); this.startRelay = startRelay;
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

    /** 在固定目标核验完成后启动已配置Relay调度器，不改变共享暂停状态。 */
    @Override
    public void start() {
        if (running) return;
        running = true;
        try { if (startRelay) schedulers.getLast().start(); }
        catch (SchedulerException failure) { running = false; throw new IllegalStateException("Relay调度器启动失败", failure); }
    }

    /**
     * 两组Scheduler先进入standby并中断借用线程，再关闭共享执行器并有限等待代理完成。
     * @apiNote 示例：由Spring生命周期关闭调用，早于Bean destroy。
     */
    @Override
    public void stop() {
        if (!running) return;
        running = false;
        boolean interrupted = Thread.interrupted();
        try {
            for (var scheduler : schedulers) {
                try { scheduler.standby(); }
                catch (SchedulerException failure) { logFailure(failure); }
            }
            for (var scheduler : schedulers) {
                try {
                    for (var context : scheduler.getCurrentlyExecutingJobs()) {
                        if (context.getJobDetail().getJobClass() == QuartzDispatchJob.class) scheduler.interrupt(context.getFireInstanceId());
                    }
                } catch (SchedulerException failure) { logFailure(failure); }
            }
            executor.close();
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
        } catch (SchedulerException failure) {
            executor.close(); logFailure(failure);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * 安全记录单个调度器停止失败，不阻断另一组停止和共享执行器清理。
     * @param failure Quartz状态异常
     */
    private void logFailure(SchedulerException failure) {
        log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("停止固定调度失败");
    }

    /**
     * 核对本实例实际Quartz运行列表，监听信号本身不冒充代理已经退出。
     * @return 仍有本项目驱动在执行时为true
     * @throws SchedulerException 调度器状态不可读
     */
    private boolean hasRunningDispatch() throws SchedulerException {
        for (var scheduler : schedulers) {
            if (scheduler.getCurrentlyExecutingJobs().stream().anyMatch(c -> c.getJobDetail().getJobClass() == QuartzDispatchJob.class)) return true;
        }
        return false;
    }
}
