package com.patrick.stratum.domain.job;

import java.util.Objects;
import java.util.UUID;

/**
 * 值标识：首期死信重放任务的固定代码，不允许客户端指定实现。
 * <p>示例：登记及查询使用{@code DeadLetterReplayJob.CODE}。</p>
 */
public final class DeadLetterReplayJob {
    /** 重放任务固定注册代码。 */
    public static final String CODE = "outbox.dead-letter-replay";

    /**
     * 为固定处理器生成仅包含归档UUID的规范任务参数。
     * @param archiveId 死信归档身份
     * @return 固定结构的JSON对象
     * @throws NullPointerException 归档身份为空
     * @apiNote 示例：{@code DeadLetterReplayJob.arguments(archiveId)}。
     */
    public static String arguments(UUID archiveId) {
        return "{\"archiveId\":\"" + Objects.requireNonNull(archiveId, "归档标识不能为空") + "\"}";
    }

    /** 常量持有类不可实例化。 */
    private DeadLetterReplayJob() { }
}
