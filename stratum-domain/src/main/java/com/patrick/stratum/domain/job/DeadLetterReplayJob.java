package com.patrick.stratum.domain.job;

/**
 * 值标识：首期死信重放任务的固定代码，不允许客户端指定实现。
 * <p>示例：登记及查询使用{@code DeadLetterReplayJob.CODE}。</p>
 */
public final class DeadLetterReplayJob {
    /** 重放任务固定注册代码。 */
    public static final String CODE = "outbox.dead-letter-replay";

    /** 常量持有类不可实例化。 */
    private DeadLetterReplayJob() { }
}
