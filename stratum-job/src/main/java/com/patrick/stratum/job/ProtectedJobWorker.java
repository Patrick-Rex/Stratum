package com.patrick.stratum.job;

import java.time.Instant;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 在实际工作线程持有可续期ShedLock，事务外调用已登记处理器，独立工作单元保存固定结果。
 * <p>示例：由JobTaskExecutor通过Spring代理调用execute，不直接构造或绕过代理执行任务。</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ProtectedJobWorker {
    private final JobExecutionApplicationService jobs;
    private final JobHandlerRegistry registry;
    private final OutboxRelayControlApplicationService relayControl;

    /**
     * 按固定代码互斥，实际处理和代理解锁结束前始终保留锁与线程。
     * @param code 已登记任务代码
     * @param executionId 原执行UUID
     * @param control 本次私有线程截止协调器
     * @apiNote 示例：由执行器在私有虚拟线程中调用受Spring代理的本方法。
     */
    @SchedulerLock(name = "#{'lock:job:' + #p0}", lockAtMostFor = "PT1M")
    public void execute(String code, UUID executionId, JobRunControl control) {
        LockAssert.assertLocked();
        boolean handedOff = false;
        try {
            if (control.stopping()) return;
            var handler = registry.handler(code);
            if (JobHandlerRegistry.RELAY_JOB_CODE.equals(code) && !relayControl.tryAdmit().allowed()) return;
            if (control.stopping()) return;
            var claim = jobs.claim(executionId);
            if (claim.isEmpty()) return;
            var lease = claim.orElseThrow();
            boolean run = control.begin(lease);
            handedOff = true;
            runAttempt(code, executionId, handler, lease, control, run);
        } finally {
            if (!handedOff) control.cleanupWithoutLease();
        }
    }

    /**
     * 执行与持久化分离，中断先清理供收尾使用，再由外部线程包装器在代理解锁后恢复。
     * @param code 预期任务代码
     * @param executionId 预期执行UUID
     * @param handler 唯一已登记处理器
     * @param lease 已提交领取
     * @param control 本次截止协调
     * @param run 是否允许调用处理器
     */
    private void runAttempt(String code, UUID executionId, JobHandler handler, Lease lease, JobRunControl control, boolean run) {
        var previous = TraceIdContext.getTraceId();
        TraceIdContext.setTraceId(lease.submission().traceId());
        long start = System.nanoTime();
        Outcome actual = Outcome.FAILED;
        String failureType = "";
        try {
            if (!code.equals(lease.submission().definition().jobCode()) || !executionId.equals(lease.submission().executionId())) {
                throw new IllegalStateException("任务领取目标不匹配");
            }
            if (run) {
                handler.execute(lease.submission());
                actual = Outcome.SUCCEEDED;
            } else actual = Outcome.INTERRUPTED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            actual = Outcome.INTERRUPTED;
            failureType = "InterruptedException";
        } catch (RuntimeException | Error failure) {
            actual = Outcome.FAILED;
            failureType = failure.getClass().getSimpleName();
            if (failure instanceof Error error) throw error;
        } finally {
            try {
                Outcome outcome = control.finishing(actual);
                boolean updated = jobs.finish(lease, outcome);
                control.record(updated ? result(outcome) : JobExecutionResult.STATE_UNCERTAIN);
                logResult(code, lease, outcome, updated, start, failureType);
            } finally {
                if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow());
                else TraceIdContext.clear();
            }
        }
    }

    /**
     * 将已持久化固定结果映射为调用结果，不将原生租约过期当作处理器结果。
     * @param outcome 已写回结果
     * @return 对应技术调用分类
     */
    private JobExecutionResult result(Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> JobExecutionResult.SUCCEEDED;
            case FAILED -> JobExecutionResult.FAILED;
            case TIMED_OUT -> JobExecutionResult.TIMED_OUT;
            case INTERRUPTED -> JobExecutionResult.INTERRUPTED;
            case LEASE_EXPIRED -> throw new IllegalStateException("执行器不能直接登记租约过期");
        };
    }

    /**
     * 只记录固定代码/身份、时间、trace和错误类型，不输出正文、幂等键、异常原文或竞争令牌。
     * @param code 安全固定代码
     * @param lease 原领取证据
     * @param outcome 实际固定结果
     * @param updated 是否完成持久化
     * @param start 本地单调开始时间
     * @param failureType 安全错误类型
     */
    private void logResult(String code, Lease lease, Outcome outcome, boolean updated, long start, String failureType) {
        Instant end = Instant.now();
        long duration = (System.nanoTime() - start) / 1_000_000;
        var input = lease.submission();
        log.atInfo().addKeyValue("jobCode", code).addKeyValue("executionId", input.executionId())
                .addKeyValue("attempt", lease.attempt()).addKeyValue("scheduleTime", input.scheduleTime())
                .addKeyValue("startTime", lease.startTime()).addKeyValue("endTime", end).addKeyValue("durationMs", duration)
                .addKeyValue("result", outcome.name()).addKeyValue("stateUpdated", updated).addKeyValue("traceId", input.traceId())
                .addKeyValue("errorType", failureType)
                .log("后台任务执行 jobCode={} executionId={} attempt={} scheduleTime={} startTime={} endTime={} durationMs={} result={} stateUpdated={} traceId={} errorType={}",
                        code, input.executionId(), lease.attempt(), input.scheduleTime(), lease.startTime(), end,
                        duration, outcome, updated, input.traceId(), failureType);
    }
}
