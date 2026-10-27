package com.patrick.stratum.job;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.patrick.stratum.application.event.OutboxReconciliationApplicationService;
import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import lombok.extern.slf4j.Slf4j;

/**
 * 固定周期Relay处理器：事务外依次调用有界对账与已有发布用例，不直接访问数据库或Broker。
 * <p>示例：由Quartz登记固定代码{@code outbox.relay}并通过受锁任务工作器执行。</p>
 */
@Slf4j
public final class OutboxRelayJobHandler implements JobHandler {
    private final OutboxRelayApplicationService relay;
    private final OutboxReconciliationApplicationService reconciliation;
    private final JobDefinition definition;
    private final OutboxRelayRepository.ClaimRequest request;
    private final OutboxRelayRepository.RetryPolicy retry = new OutboxRelayRepository.RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30));
    private final int reconciliationLimit;

    /**
     * 固化周期、限额和事务外应用入口，不创建额外线程。
     * @param relay 已有有界发布入口
     * @param reconciliation 已有有界对账入口
     * @param properties 已校验的周期与资源配置
     * @throws NullPointerException 必需应用入口或配置为空
     * @apiNote 示例：由显式启用的{@code OutboxRelayJobConfiguration}装配。
     */
    public OutboxRelayJobHandler(OutboxRelayApplicationService relay, OutboxReconciliationApplicationService reconciliation,
            OutboxRelayJobConfiguration.Properties properties) {
        this.relay = Objects.requireNonNull(relay); this.reconciliation = Objects.requireNonNull(reconciliation);
        Objects.requireNonNull(properties);
        this.definition = new JobDefinition(JobHandlerRegistry.RELAY_JOB_CODE, JobDefinition.Type.CRON,
                Optional.of(properties.cron()), Optional.empty(), properties.timeoutSec(),
                new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)), JobDefinition.IdempotencyRule.SCHEDULE_TIME);
        this.request = new OutboxRelayRepository.ClaimRequest(properties.batchSize(), Duration.ofMinutes(2));
        this.reconciliationLimit = properties.reconciliationLimit();
    }

    /** {@inheritDoc} */
    @Override public String jobCode() { return JobHandlerRegistry.RELAY_JOB_CODE; }

    /** {@inheritDoc} */
    @Override public Optional<JobDefinition> periodicDefinition() { return Optional.of(definition); }

    /** {@inheritDoc} */
    @Override
    public void execute(Submission submission) throws InterruptedException {
        Objects.requireNonNull(submission);
        if (!jobCode().equals(submission.definition().jobCode()) || !"{}".equals(submission.arguments())) {
            throw new IllegalArgumentException("Relay任务输入不匹配");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Relay执行前已中断");
        var reconciled = reconciliation.runBatch(reconciliationLimit);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Relay对账后已中断");
        int updated = relay.runBatch(request, retry);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Relay发布后已中断");
        log.atInfo().addKeyValue("jobCode", jobCode()).addKeyValue("executionId", submission.executionId())
                .addKeyValue("scanned", reconciled.scanned()).addKeyValue("reconciled", reconciled.reconciled())
                .addKeyValue("updatedDeliveries", updated)
                .log("Outbox周期批次结束 jobCode={} executionId={} scanned={} reconciled={} updatedDeliveries={}",
                        jobCode(), submission.executionId(), reconciled.scanned(), reconciled.reconciled(), updated);
    }
}
