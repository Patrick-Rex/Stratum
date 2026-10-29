package com.patrick.stratum.job;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;

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
    private final JobMetrics metrics;
    private final JobMetrics.Settings settings;
    private final StandardTraceContext standardTrace;

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
        String previousBusiness = MDC.get("businessTraceId"); String previousTrace = MDC.get("trace_id"); String previousSpan = MDC.get("span_id");
        TraceIdContext.setTraceId(lease.submission().traceId());
        MDC.put("businessTraceId", lease.submission().traceId()); MDC.remove("trace_id"); MDC.remove("span_id");
        long start = System.nanoTime();
        try {
            Optional<StandardTraceContext.Carrier> parent;
            StandardTraceContext.TraceScope restored;
            try {
                var fields = lease.standardTrace();
                parent = Objects.requireNonNull(standardTrace.normalize(fields.map(value -> value.traceparent()).orElse(null),
                        fields.map(value -> value.tracestate()).orElse(null)));
                restored = Objects.requireNonNull(standardTrace.restore(parent));
            } catch (RuntimeException failure) {
                control.finishing(Outcome.FAILED); control.record(JobExecutionResult.STATE_UNCERTAIN);
                recordMetric(code, JobExecutionResult.STATE_UNCERTAIN, System.nanoTime() - start);
                try { log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("任务标准追踪恢复失败，保留租约"); }
                catch (RuntimeException ignored) { /* 告警失败不能让任务继续执行。 */ }
                return;
            }
            try (restored) {
                parent.ifPresent(value -> {
                    MDC.put("trace_id", value.traceparent().substring(3, 35));
                    MDC.put("span_id", value.traceparent().substring(36, 52));
                });
                runRestoredAttempt(code, executionId, handler, lease, control, run, start);
            }
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow());
            else TraceIdContext.clear();
            restoreMdc("businessTraceId", previousBusiness); restoreMdc("trace_id", previousTrace); restoreMdc("span_id", previousSpan);
        }
    }

    /**
     * 在已恢复的追踪作用域内执行处理器，并在独立工作单元完成本次领取。
     * @param code 预期任务代码
     * @param executionId 预期执行UUID
     * @param handler 已登记处理器
     * @param lease 已提交领取
     * @param control 本次截止协调
     * @param run 是否允许调用处理器
     * @param start 本次实际工作开始的单调时刻
     */
    private void runRestoredAttempt(String code, UUID executionId, JobHandler handler, Lease lease,
            JobRunControl control, boolean run, long start) {
        Outcome actual = Outcome.FAILED;
        String failureType = "";
        try {
            if (!code.equals(lease.submission().definition().jobCode()) || !executionId.equals(lease.submission().executionId())) {
                throw new IllegalStateException("任务领取目标不匹配");
            }
            if (run) {
                handler.executeLease(lease);
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
            Outcome outcome = control.finishing(actual);
            boolean updated = jobs.finish(lease, outcome);
            var result = updated ? result(outcome) : JobExecutionResult.STATE_UNCERTAIN;
            control.record(result);
            long elapsed = System.nanoTime() - start;
            boolean slow = elapsed >= TimeUnit.MILLISECONDS.toNanos(settings.slowThresholdMs());
            recordMetric(code, result, elapsed);
            logResult(code, lease, outcome, updated, elapsed, failureType, slow);
        }
    }

    /**
     * 有界记录任务指标，指标故障不改变已决定的任务状态。
     * @param code 已登记代码
     * @param result 固定结果
     * @param elapsed 单调时钟耗时纳秒
     */
    private void recordMetric(String code, JobExecutionResult result, long elapsed) {
        try { metrics.record(code, result, elapsed); }
        catch (RuntimeException failure) {
            try {
                log.atWarn().addKeyValue("jobCode", code).addKeyValue("errorType", failure.getClass().getSimpleName())
                        .log("任务指标记录失败 jobCode={} errorType={}", code, failure.getClass().getSimpleName());
            } catch (RuntimeException ignored) { /* 观测组件故障不改变任务状态。 */ }
        }
    }

    /**
     * 恢复进入任务前的单个日志上下文字段。
     * @param key 固定字段名
     * @param value 可空先前值
     */
    private void restoreMdc(String key, String value) {
        if (value == null) MDC.remove(key); else MDC.put(key, value);
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
     * @param elapsed 实际处理耗时纳秒
     * @param failureType 安全错误类型
     * @param slow 是否达到慢任务阈值
     */
    private void logResult(String code, Lease lease, Outcome outcome, boolean updated, long elapsed, String failureType, boolean slow) {
        Instant end = Instant.now();
        long duration = elapsed / 1_000_000;
        var input = lease.submission();
        var event = slow || !updated || outcome != Outcome.SUCCEEDED ? log.atWarn() : log.atInfo();
        event.addKeyValue("jobCode", code).addKeyValue("executionId", input.executionId())
                .addKeyValue("attempt", lease.attempt()).addKeyValue("scheduleTime", input.scheduleTime())
                .addKeyValue("startTime", lease.startTime()).addKeyValue("endTime", end).addKeyValue("durationMs", duration)
                .addKeyValue("result", outcome.name()).addKeyValue("stateUpdated", updated).addKeyValue("traceId", input.traceId())
                .addKeyValue("errorType", failureType)
                .log("后台任务执行 jobCode={} executionId={} attempt={} scheduleTime={} startTime={} endTime={} durationMs={} result={} stateUpdated={} traceId={} errorType={}",
                        code, input.executionId(), lease.attempt(), input.scheduleTime(), lease.startTime(), end,
                        duration, outcome, updated, input.traceId(), failureType);
    }
}
