package com.patrick.stratum.job;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;

/**
 * 固定一次性死信重放处理器，校验任务输入并委托已有应用执行边界。
 * <p>示例：在任务配置中注入可信执行入口后交给固定注册表。</p>
 */
public final class DeadLetterReplayJobHandler implements JobHandler {
    private static final String PREFIX = "{\"archiveId\":\"";
    private static final String SUFFIX = "\"}";
    private final Execution execution;

    /**
     * 固定执行端口；不自行创建线程、事务或消息连接。
     * @param execution 可信重放任务应用入口
     * @throws NullPointerException 入口为空
     * @apiNote 示例：{@code new DeadLetterReplayJobHandler(execution)}。
     */
    public DeadLetterReplayJobHandler(Execution execution) { this.execution = Objects.requireNonNull(execution); }

    /**
     * 固定处理器的应用执行端口，必须按任务UUID幂等并正确传播中断。
     * <p>示例：由Spring配置传入已有应用入口的方法引用。</p>
     */
    @FunctionalInterface
    public interface Execution {
        /**
         * 在事务外执行已验证的重放任务领取，不直接发送消息。
         * @param lease 已领取的完整竞争证据
         * @throws InterruptedException 执行被中断
         * @throws RuntimeException 任务执行失败
         * @apiNote 示例：{@code execution.execute(lease)}。
         */
        void execute(Lease lease) throws InterruptedException;
    }

    /** {@inheritDoc} */
    @Override public String jobCode() { return DeadLetterReplayJob.CODE; }

    /**
     * 拒绝没有领取证据的直接调用，不能绕过数据库租约核验。
     * @param submission 没有领取证据的任务输入
     * @throws IllegalStateException 始终拒绝该入口
     * @apiNote 示例：由工作器调用{@code executeLease(lease)}，而非此入口。
     */
    @Override public void execute(Submission submission) { throw new IllegalStateException("重放任务必须提供领取证据"); }

    /**
     * 校验固定任务输入后，将原领取证据交给应用执行入口。
     * @param lease 已领取的完整证据
     * @throws NullPointerException 领取证据为空
     * @throws IllegalArgumentException 任务输入不符合固定协议
     * @throws InterruptedException 执行前后收到中断或执行端口传播中断
     * @throws RuntimeException 执行端口失败
     * @apiNote 示例：{@code handler.executeLease(lease)}；完成不表示消费成功。
     */
    @Override public void executeLease(Lease lease) throws InterruptedException {
        Objects.requireNonNull(lease, "任务领取不能为空");
        var submission = lease.submission();
        var definition = submission.definition();
        if (!DeadLetterReplayJob.CODE.equals(definition.jobCode()) || definition.jobType() != JobDefinition.Type.ONCE
                || definition.idempotencyKeyRule() != JobDefinition.IdempotencyRule.EXPLICIT_KEY) {
            throw new IllegalArgumentException("重放任务类型不匹配");
        }
        String arguments = submission.arguments();
        if (arguments.length() != PREFIX.length() + 36 + SUFFIX.length()
                || !arguments.startsWith(PREFIX) || !arguments.endsWith(SUFFIX)) throw new IllegalArgumentException("重放任务参数不匹配");
        try {
            UUID archive = UUID.fromString(arguments.substring(PREFIX.length(), arguments.length() - SUFFIX.length()));
            UUID request = UUID.fromString(submission.idempotencyKey());
            if (!DeadLetterReplayJob.arguments(archive).equals(arguments) || !request.toString().equals(submission.idempotencyKey())) {
                throw new IllegalArgumentException("重放任务身份不匹配");
            }
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("重放任务身份不合法"); }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("重放任务执行前已中断");
        execution.execute(lease);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("重放任务执行后已中断");
    }
}
