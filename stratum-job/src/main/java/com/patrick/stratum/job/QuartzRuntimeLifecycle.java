package com.patrick.stratum.job;

import java.time.Duration;
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
    private final Scheduler scheduler;
    private final JobTaskExecutor executor;
    private final ReentrantLock completion = new ReentrantLock();
    private final Condition changed = completion.newCondition();
    private volatile boolean running;

    /**
     * 绑定所属调度器与本实例实际执行器，不在构造中启动线程。
     * @param scheduler 当前上下文的Quartz实例
     * @param executor 私有工作执行器
     * @apiNote 示例：由Spring通过构造器注入同实例组件。
     */
    public QuartzRuntimeLifecycle(Scheduler scheduler, JobTaskExecutor executor) {
        this.scheduler = scheduler; this.executor = executor;
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

    /** {@inheritDoc} */
    @Override
    public void start() { running = true; }

    /**
     * Scheduler先进入standby，本生命周期随后中断借用线程和实际处理，有限等待代理完成。
     * @apiNote 示例：由Spring生命周期关闭调用，早于Bean destroy。
     */
    @Override
    public void stop() {
        if (!running) return;
        running = false;
        boolean interrupted = Thread.interrupted();
        try {
            scheduler.standby();
            for (var context : scheduler.getCurrentlyExecutingJobs()) {
                if (context.getJobDetail().getJobClass() == QuartzDispatchJob.class) scheduler.interrupt(context.getFireInstanceId());
            }
            executor.close();
            long remaining = Duration.ofSeconds(10).toNanos();
            completion.lock();
            try {
                while (hasRunningDispatch() && remaining > 0) {
                    // Quartz监听回调先于运行列表移除；最多每100ms重新核对，所有等待共享截止。
                    long start = System.nanoTime();
                    try { changed.awaitNanos(Math.min(remaining, Duration.ofMillis(100).toNanos())); }
                    catch (InterruptedException signal) { interrupted = true; }
                    remaining -= System.nanoTime() - start;
                }
                if (hasRunningDispatch()) log.warn("固定调度收尾超过关闭预算");
            } finally { completion.unlock(); }
        } catch (SchedulerException failure) {
            executor.close();
            log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("停止固定调度失败");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * 核对本实例实际Quartz运行列表，监听信号本身不冒充代理已经退出。
     * @return 仍有本项目驱动在执行时为true
     * @throws SchedulerException 调度器状态不可读
     */
    private boolean hasRunningDispatch() throws SchedulerException {
        return scheduler.getCurrentlyExecutingJobs().stream().anyMatch(c -> c.getJobDetail().getJobClass() == QuartzDispatchJob.class);
    }
}
