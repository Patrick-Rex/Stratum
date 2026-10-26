package com.patrick.stratum.job;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;

/**
 * 只协调同进程单次执行的截止/中断与收尾信号，不提供分布式互斥。
 * 局部锁中没有业务或数据库操作；实际跨实例资格由ShedLock及数据库令牌决定。
 */
final class JobRunControl {
    final String code;
    final UUID executionId;
    final CompletableFuture<Optional<Start>> started = new CompletableFuture<>();
    final CompletableFuture<JobExecutionResult> completed = new CompletableFuture<>();
    private final ReentrantLock phaseLock = new ReentrantLock();
    private volatile Thread thread;
    private Phase phase = Phase.STARTING;
    private boolean stopRequested;
    private boolean timedOut;
    private boolean restoreInterrupt;
    private boolean recorded;
    private JobExecutionResult result = JobExecutionResult.SKIPPED;

    /**
     * 保留有限执行身份，不在此创建线程或访问外部设施。
     * @param code 已登记任务代码
     * @param executionId 原执行UUID
     */
    JobRunControl(String code, UUID executionId) {
        this.code = Objects.requireNonNull(code);
        this.executionId = Objects.requireNonNull(executionId);
    }

    /**
     * 在线程启动前绑定唯一虚拟工作线程。
     * @param target 本次执行私有虚拟线程
     * @throws IllegalStateException 重复绑定
     * @throws IllegalArgumentException 不是虚拟线程
     */
    void bind(Thread target) {
        Objects.requireNonNull(target);
        if (!target.isVirtual()) throw new IllegalArgumentException("任务工作线程必须为虚拟线程");
        if (thread != null) throw new IllegalStateException("任务线程不能重复绑定");
        thread = target;
    }

    /**
     * 取得本次执行线程供有界关闭等待，不交给其他任务复用。
     * @return 唯一虚拟线程
     */
    Thread thread() { return Objects.requireNonNull(thread, "任务线程尚未绑定"); }

    /**
     * 查看是否应在领取前跳过执行；只是本实例停止信号。
     * @return 已请求中断时为true
     */
    boolean stopping() {
        phaseLock.lock();
        try { return stopRequested; }
        finally { phaseLock.unlock(); }
    }

    /**
     * 工作线程在领取提交后交接实际执行预算，截止使用单调时钟。
     * @param lease 已提交领取证据
     * @return 未在启动前请求停止时为true，否则必须跳过处理器并记录中断
     * @throws IllegalStateException 非本工作线程或重复交接
     */
    boolean begin(Lease lease) {
        requireOwner(); Objects.requireNonNull(lease);
        phaseLock.lock();
        try {
            if (phase != Phase.STARTING) throw new IllegalStateException("任务领取只能交接一次");
            phase = Phase.RUNNING;
            long deadline = System.nanoTime() + lease.submission().definition().timeoutSec() * 1_000_000_000L;
            started.complete(Optional.of(new Start(lease, deadline)));
            return !stopRequested;
        } finally { phaseLock.unlock(); }
    }

    /**
     * 截止信号只在处理器运行阶段生效；投递中断和阶段判断在同一局部临界区。
     * @return 本次确实向运行线程投递超时信号时为true
     */
    boolean requestTimeout() {
        phaseLock.lock();
        try {
            if (phase != Phase.RUNNING) return false;
            timedOut = true; stopRequested = true;
            thread().interrupt();
            return true;
        } finally { phaseLock.unlock(); }
    }

    /**
     * 关闭或调用方中断可停止启动/运行阶段，不能迟到中断结果提交或代理解锁。
     * @return 本次可投递中断时为true
     */
    boolean requestInterrupt() {
        phaseLock.lock();
        try {
            if (phase != Phase.STARTING && phase != Phase.RUNNING) return false;
            stopRequested = true;
            thread().interrupt();
            return true;
        } finally { phaseLock.unlock(); }
    }

    /**
     * 工作线程进入收尾，清除本次中断供状态写回与代理解锁，并保留之后恢复的责任。
     * @param actual 处理器实际固定结果
     * @return 超时优先、其次中断、否则原结果
     * @throws IllegalStateException 非持有线程或不处于运行阶段
     */
    Outcome finishing(Outcome actual) {
        requireOwner(); Objects.requireNonNull(actual);
        phaseLock.lock();
        try {
            if (phase != Phase.RUNNING) throw new IllegalStateException("任务不处于可收尾阶段");
            phase = Phase.FINISHING;
            boolean interrupted = Thread.interrupted();
            restoreInterrupt = interrupted || stopRequested || actual == Outcome.INTERRUPTED;
            if (timedOut) return Outcome.TIMED_OUT;
            if (restoreInterrupt) return Outcome.INTERRUPTED;
            return actual;
        } finally { phaseLock.unlock(); }
    }

    /**
     * 未取得领取时也封闭截止阶段并清理中断，防止启动等待超时污染代理解锁。
     * @throws IllegalStateException 非持有线程或已经交接领取
     */
    void cleanupWithoutLease() {
        requireOwner();
        phaseLock.lock();
        try {
            if (phase != Phase.STARTING) throw new IllegalStateException("未领取清理只能发生于启动阶段");
            phase = Phase.FINISHING;
            restoreInterrupt = Thread.interrupted() || stopRequested;
            started.complete(Optional.empty());
        } finally { phaseLock.unlock(); }
    }

    /**
     * 保存实际持久化结果，尚不向等待者宣告代理解锁已结束。
     * @param value 已写回或固定不确定结果
     */
    void record(JobExecutionResult value) {
        requireOwner(); result = Objects.requireNonNull(value); recorded = true;
    }

    /** 未得到可核实结果时保守登记不确定，保留已成功持久化的实际分类。 */
    void failIfUnrecorded() {
        requireOwner();
        if (!recorded) result = JobExecutionResult.STATE_UNCERTAIN;
    }

    /** 必须在ShedLock代理解锁返回后由工作线程调用，避免中断污染数据库清理。 */
    void restoreThreadInterrupt() {
        requireOwner();
        if (restoreInterrupt) Thread.currentThread().interrupt();
    }

    /**
     * 实际处理和代理清理结束后完成信号；没有领取的跳过也必须唤醒启动等待者。
     * @throws IllegalStateException 不是本工作线程
     */
    void terminated() {
        requireOwner();
        phaseLock.lock();
        try {
            phase = Phase.DONE;
            started.complete(Optional.empty());
            completed.complete(result);
        } finally { phaseLock.unlock(); }
    }

    /**
     * 限制完成、阶段交接和中断恢复只能由唯一工作线程处理。
     * @throws IllegalStateException 调用者不是私有工作线程
     */
    private void requireOwner() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("任务阶段只能由本次工作线程推进");
    }

    /**
     * 值快照：已提交领取和本次实际处理的单调截止，不以本机时间判断数据库租约。
     * @param lease 持久化领取证据
     * @param deadlineNanos 本次处理截止，供有界等待计算剩余预算
     */
    record Start(Lease lease, long deadlineNanos) { }

    /** 仅用于本进程一次执行的生命周期阶段。 */
    private enum Phase { STARTING, RUNNING, FINISHING, DONE }
}
