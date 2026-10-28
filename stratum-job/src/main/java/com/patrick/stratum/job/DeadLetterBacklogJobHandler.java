package com.patrick.stratum.job;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.patrick.stratum.application.event.DeadLetterBacklogSamplingService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.quartz.CronExpression;

/**
 * 固定死信可见数采集处理器，由现有受锁工作器调用事务外应用入口。
 * <p>示例：登记{@code deadletter.backlog}后由专用Quartz调度组触发。</p>
 */
public final class DeadLetterBacklogJobHandler implements JobHandler {
    private final DeadLetterBacklogSamplingService sampling;
    private final JobDefinition definition;

    /**
     * 固化合法Cron及任务执行预算；每个周期最多尝试一次，下一周期重新采样。
     * @param sampling 事务外采集用例
     * @param cron 合法的UTC Quartz Cron表达式
     * @param timeoutSec 执行预算秒数
     * @throws NullPointerException 采集用例为空
     * @throws IllegalArgumentException 计划或执行预算不合法
     * @apiNote 示例：{@code new DeadLetterBacklogJobHandler(sampling, "0 0/5 * * * ?", 60)}。
     */
    public DeadLetterBacklogJobHandler(DeadLetterBacklogSamplingService sampling, String cron, int timeoutSec) {
        this.sampling = Objects.requireNonNull(sampling, "采集用例不能为空");
        if (cron == null || cron.length() > 120 || cron.chars().anyMatch(Character::isISOControl)
                || !CronExpression.isValidExpression(cron)) throw new IllegalArgumentException("采集Cron不合法");
        this.definition = new JobDefinition(JobHandlerRegistry.BACKLOG_JOB_CODE, JobDefinition.Type.CRON,
                Optional.of(cron), Optional.empty(), timeoutSec,
                new JobDefinition.RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)), JobDefinition.IdempotencyRule.SCHEDULE_TIME);
    }

    /** {@inheritDoc} */
    @Override public String jobCode() { return JobHandlerRegistry.BACKLOG_JOB_CODE; }

    /** {@inheritDoc} */
    @Override public Optional<JobDefinition> periodicDefinition() { return Optional.of(definition); }

    /** {@inheritDoc} */
    @Override public void execute(Submission submission) throws InterruptedException {
        Objects.requireNonNull(submission, "任务输入不能为空");
        if (!jobCode().equals(submission.definition().jobCode()) || !"{}".equals(submission.arguments())) {
            throw new IllegalArgumentException("死信采集任务输入不匹配");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("死信采集执行前已中断");
        sampling.sample();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("死信采集结束时已中断");
    }
}
