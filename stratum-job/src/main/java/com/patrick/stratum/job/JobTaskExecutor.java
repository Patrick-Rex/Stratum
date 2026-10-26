package com.patrick.stratum.job;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;

/**
 * 有界私有虚拟线程执行器，调用方超时后仍把实际未结束的工作计入容量。
 * <p>示例：executor.execute("outbox.relay", executionId)；必须随所属Spring上下文关闭。</p>
 */
@Slf4j
public final class JobTaskExecutor implements AutoCloseable {
    private static final long START_WAIT_SECONDS = 10;
    private static final long FINISH_WAIT_SECONDS = 5;
    private final ProtectedJobWorker worker;
    private final JobHandlerRegistry registry;
    private final int capacity;
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final Set<JobRunControl> active = new HashSet<>();
    private boolean closed;

    /**
     * 创建无等待队列的固定容量执行器，本构造不启动线程。
     * @param worker 必须经过ShedLock的Spring代理工作器
     * @param registry 固定处理器注册表
     * @param capacity 本实例最多1至8个实际在途工作
     * @throws IllegalArgumentException 容量不合法
     * @apiNote 示例：由显式JobRuntimeConfiguration装配，不绕过Spring工作器代理。
     */
    public JobTaskExecutor(ProtectedJobWorker worker, JobHandlerRegistry registry, int capacity) {
        this.worker = Objects.requireNonNull(worker); this.registry = Objects.requireNonNull(registry);
        if (capacity < 1 || capacity > 8) throw new IllegalArgumentException("任务在途容量不合法");
        this.capacity = capacity;
    }

    /**
     * 有界等待领取及处理；超时只请求中断，未退出的实际线程继续占用名额与其数据库锁。
     * @param code 已登记固定代码
     * @param executionId 原执行UUID
     * @return 实际分类或仍运行/不确定/拒绝；不得替代数据库任务状态
     * @throws IllegalArgumentException 处理器未登记
     * @apiNote 示例：executor.execute("outbox.relay", executionId)。
     */
    public JobExecutionResult execute(String code, UUID executionId) {
        registry.handler(code); Objects.requireNonNull(executionId);
        if (Thread.currentThread().isInterrupted()) return JobExecutionResult.INTERRUPTED;
        JobRunControl control;
        lifecycle.lock();
        try {
            reapTerminated();
            if (closed) return JobExecutionResult.CLOSED;
            if (active.size() >= capacity) return JobExecutionResult.CAPACITY_REJECTED;
            control = new JobRunControl(code, executionId);
            var thread = Thread.ofVirtual().inheritInheritableThreadLocals(false)
                    .name("stratum-job-worker-" + UUID.randomUUID()).unstarted(() -> run(control));
            control.bind(thread); active.add(control);
            try { thread.start(); }
            catch (RuntimeException | Error failure) { active.remove(control); throw failure; }
        } finally { lifecycle.unlock(); }
        try {
            var start = control.started.get(START_WAIT_SECONDS, TimeUnit.SECONDS);
            if (start.isPresent()) {
                long remaining = start.orElseThrow().deadlineNanos() - System.nanoTime();
                if (remaining > 0) {
                    try { return control.completed.get(remaining, TimeUnit.NANOSECONDS); }
                    catch (TimeoutException deadline) { /* 统一在下面投递一次受阶段保护的超时信号。 */ }
                }
                control.requestTimeout();
            }
            return awaitCompletion(control);
        } catch (TimeoutException starting) {
            control.requestInterrupt();
            return awaitAfterStartupTimeout(control);
        } catch (InterruptedException interrupted) {
            control.requestInterrupt(); Thread.currentThread().interrupt();
            return JobExecutionResult.INTERRUPTED;
        } catch (ExecutionException failure) {
            control.requestInterrupt();
            return JobExecutionResult.STATE_UNCERTAIN;
        }
    }

    /**
     * 暴露真实在途工作数供本地观测，不用它判定分布式所有权。
     * @return 0至配置容量
     * @apiNote 示例：executor.activeCount()。
     */
    public int activeCount() {
        lifecycle.lock();
        try { reapTerminated(); return active.size(); }
        finally { lifecycle.unlock(); }
    }

    /** 在生命周期局部锁内移除已经真正退出的线程，Future完成本身不释放容量。 */
    private void reapTerminated() {
        active.removeIf(control -> !control.thread().isAlive());
    }

    /**
     * 实际私有线程调用代理，代理解锁之后才恢复中断与完成信号，名额另以线程实际退出核验。
     * @param control 唯一线程控制器
     */
    private void run(JobRunControl control) {
        try {
            worker.execute(control.code, control.executionId, control);
        } catch (Throwable failure) {
            control.failIfUnrecorded();
            log.atWarn().addKeyValue("jobCode", control.code).addKeyValue("executionId", control.executionId)
                    .addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("任务工作器未正常返回 jobCode={} executionId={} errorType={}",
                            control.code, control.executionId, failure.getClass().getSimpleName());
        } finally {
            control.restoreThreadInterrupt();
            control.terminated();
        }
    }

    /**
     * 在有界收尾预算中等待实际处理及代理结束，不取消代表真实线程的信号。
     * @param control 本次控制器
     * @return 实际结果或仍在运行
     * @throws InterruptedException 调用方被中断
     * @throws ExecutionException 内部完成信号异常
     */
    private JobExecutionResult awaitCompletion(JobRunControl control) throws InterruptedException, ExecutionException {
        try { return control.completed.get(FINISH_WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (TimeoutException stillRunning) { return JobExecutionResult.STILL_RUNNING; }
    }

    /**
     * 启动超时后仍只在有限预算等待清理，保留调用线程的中断。
     * @param control 本次控制器
     * @return 实际结果、仍运行或不确定
     */
    private JobExecutionResult awaitAfterStartupTimeout(JobRunControl control) {
        try { return awaitCompletion(control); }
        catch (InterruptedException interrupted) {
            control.requestInterrupt(); Thread.currentThread().interrupt(); return JobExecutionResult.INTERRUPTED;
        } catch (ExecutionException failure) { return JobExecutionResult.STATE_UNCERTAIN; }
    }

    /**
     * 先关闭接纳并请求实际线程中断，共享十秒预算有界等待，绝不强制停止Java线程。
     * 中断调用者在等待结束后恢复；仍未退出的工作继续保留真实容量记录。
     * @apiNote 示例：Spring在关闭锁提供者和数据源之前调用close()。
     */
    @Override
    public void close() {
        List<JobRunControl> snapshot;
        lifecycle.lock();
        try { closed = true; reapTerminated(); snapshot = List.copyOf(active); }
        finally { lifecycle.unlock(); }
        boolean interrupted = Thread.interrupted();
        try {
            snapshot.forEach(JobRunControl::requestInterrupt);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            for (var control : snapshot) {
                while (control.thread().isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try { control.thread().join(Duration.ofNanos(remaining)); }
                    catch (InterruptedException signal) { interrupted = true; }
                }
            }
            int remaining = activeCount();
            if (remaining > 0) log.atWarn().addKeyValue("activeTasks", remaining).log("任务执行器关闭预算结束 activeTasks={}", remaining);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
