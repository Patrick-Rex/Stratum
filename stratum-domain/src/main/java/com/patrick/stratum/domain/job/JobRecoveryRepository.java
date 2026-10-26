package com.patrick.stratum.domain.job;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;

/**
 * 任务完成与人工恢复技术存储端口，不运行处理器，也不自行开启事务。
 * <p>示例：unitOfWork.execute(() -> recovery.finish(lease, Outcome.SUCCEEDED))。</p>
 */
public interface JobRecoveryRepository {
    /**
     * 以实际数据库时间与原令牌核验，原子保存尝试、状态、退避或dead_job。
     * 成功时同时关联恢复待完成的历史dead_job；人工恢复不会删除历史尝试。
     * @param lease 原领取完整证据
     * @param outcome 固定安全结果分类，不接受异常正文
     * @return 有效持有者已完成为true；忙、过期或已被接管为false
     * @throws IllegalStateException 缺少活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> recovery.finish(lease, Outcome.TIMED_OUT))。
     */
    boolean finish(JobExecutionRepository.Lease lease, Outcome outcome);

    /**
     * 以独立请求UUID幂等恢复当前dead_job，保留原执行身份与参数并开始新失败周期。
     * 仅当前死亡周期可恢复；旧请求返回首次登记身份，不能改变目标。
     * @param request 人工恢复请求与安全trace
     * @return 原执行UUID，登记成功不等于执行成功
     * @throws IllegalStateException 缺少活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> recovery.replay(request))。
     */
    UUID replay(ReplayRequest request);

    /** 值枚举：只记录固定执行分类；LEASE_EXPIRED仅由到期接管路径登记。 */
    enum Outcome { SUCCEEDED, FAILED, TIMED_OUT, INTERRUPTED, LEASE_EXPIRED }

    /**
     * 值对象：人工恢复请求，不能替换任务输入或携带任意执行代码。
     * @param requestId 恢复操作UUID
     * @param deadId 当前死亡记录UUID
     * @param traceId 本次操作的安全关联标识
     */
    record ReplayRequest(UUID requestId, UUID deadId, String traceId) {
        /**
         * 校验目标和安全关联标识。
         * @param requestId 请求UUID
         * @param deadId 死亡记录UUID
         * @param traceId 安全trace
         * @throws NullPointerException 身份缺失
         * @throws IllegalArgumentException trace不合法
         * @apiNote 示例：new ReplayRequest(UUID.randomUUID(), deadId, "replay-1")。
         */
        public ReplayRequest {
            Objects.requireNonNull(requestId, "恢复请求身份不能为空");
            Objects.requireNonNull(deadId, "死亡记录身份不能为空");
            if (!CorrelationIdPolicy.isValid(traceId)) throw new IllegalArgumentException("恢复关联标识不合法");
        }
    }
}
