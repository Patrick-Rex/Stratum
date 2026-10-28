package com.patrick.stratum.job;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;

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
         * 在事务外执行已验证的重放任务输入，不直接发送消息。
         * @param submission 已领取的固定任务输入
         * @throws InterruptedException 执行被中断
         * @throws RuntimeException 任务执行失败
         * @apiNote 示例：{@code execution.execute(submission)}。
         */
        void execute(Submission submission) throws InterruptedException;
    }

    /** {@inheritDoc} */
    @Override public String jobCode() { return DeadLetterReplayJob.CODE; }

    /**
     * 校验固定代码、类型、幂等请求UUID和唯一归档参数后调用执行端口。
     * @param submission 已领取的任务快照
     * @throws NullPointerException 任务输入为空
     * @throws IllegalArgumentException 任务输入不符合固定协议
     * @throws InterruptedException 执行前后收到中断或执行端口传播中断
     * @throws RuntimeException 执行端口失败
     * @apiNote 示例：{@code handler.execute(submission)}；成功只表示处理器完成，不表示消费完成。
     */
    @Override public void execute(Submission submission) throws InterruptedException {
        Objects.requireNonNull(submission, "任务输入不能为空");
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
        execution.execute(submission);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("重放任务执行后已中断");
    }
}
