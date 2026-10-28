package com.patrick.stratum.domain.job;

import java.util.UUID;

/**
 * 任务取消技术端口，只允许从未开始的任务在已有写工作单元中进入取消终态。
 * <p>示例：{@code unitOfWork.execute(() -> cancellations.cancelUnstarted(id, code))}。</p>
 */
public interface JobCancellationRepository {
    /** 值枚举：已取消、目标不存在或不匹配、任务已经开始。 */
    enum Result { CANCELLED, NOT_FOUND, ALREADY_STARTED }

    /**
     * 持任务行锁竞争取消；重复取消返回同一结果，领取先提交则不能取消。
     * @param executionId 任务执行UUID
     * @param jobCode 调用方允许取消的固定任务代码
     * @return 已取消、目标不存在或已经开始
     * @throws NullPointerException 任务身份为空
     * @throws IllegalStateException 不在写工作单元内
     * @throws IllegalArgumentException 任务代码非法
     * @throws com.patrick.stratum.common.error.StratumException 存储竞争或版本耗尽
     * @apiNote 示例：{@code cancellations.cancelUnstarted(id, "outbox.dead-letter-replay")}。
     */
    Result cancelUnstarted(UUID executionId, String jobCode);
}
