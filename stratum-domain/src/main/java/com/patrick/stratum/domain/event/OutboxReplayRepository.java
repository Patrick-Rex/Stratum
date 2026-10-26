package com.patrick.stratum.domain.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;

/** 人工重放技术端口，只登记持久化投递意图，不执行MQ或改变原事件身份。 */
public interface OutboxReplayRepository {
    /**
     * 核验本地原事件及聚合头，幂等登记重放请求与独立投递意图。
     * @param request 有界请求身份、归档及关联ID
     * @return 已登记、已有意图或已消费的稳定结果
     * @throws IllegalStateException 没有可写应用工作单元
     * @throws com.patrick.stratum.common.error.StratumException 身份不符、资源缺失或当前状态冲突
     * @apiNote 示例：unitOfWork.execute(() -> repository.enqueue(request))；任何失败必须回滚整个工作单元。
     */
    Receipt enqueue(Request request);

    /** 重放请求结果；同requestId返回首次结果，不表示投递或消费已经完成。 */
    enum Outcome { QUEUED, ALREADY_QUEUED, ALREADY_CONSUMED }

    /**
     * 值对象：一次人工重放请求，不接收可替换的事件正文。
     * @param requestId 幂等请求UUID
     * @param archiveId 需要恢复的归档UUID
     * @param traceId 当前人工操作链路，不覆盖原事件trace
     */
    record Request(UUID requestId, UUID archiveId, String traceId) {
        /**
         * 校验身份和安全关联标识。
         * @param requestId 请求UUID
         * @param archiveId 归档UUID
         * @param traceId 安全关联值
         * @throws NullPointerException 身份为空
         * @throws IllegalArgumentException 关联标识非法
         * @apiNote 示例：new Request(UUID.randomUUID(), archiveId, "manual-replay")。
         */
        public Request {
            Objects.requireNonNull(requestId); Objects.requireNonNull(archiveId);
            if (!CorrelationIdPolicy.isValid(traceId)) throw new IllegalArgumentException("重放关联标识非法");
        }
    }

    /**
     * 值对象：稳定重放回执，只有需要发送的结果才包含独立投递ID。
     * @param requestId 请求UUID
     * @param archiveId 归档UUID
     * @param deliveryId 重放投递意图ID，不替换事件messageId
     * @param outcome 首次请求结果
     */
    record Receipt(UUID requestId, UUID archiveId, Optional<UUID> deliveryId, Outcome outcome) {
        /**
         * 保持消费完成与投递意图的互斥语义。
         * @param requestId 请求UUID
         * @param archiveId 归档UUID
         * @param deliveryId 可选独立意图
         * @param outcome 结果
         * @throws NullPointerException 必需字段为空
         * @throws IllegalArgumentException 意图与结果不一致
         * @apiNote 示例：new Receipt(requestId, archiveId, Optional.of(deliveryId), Outcome.QUEUED)。
         */
        public Receipt {
            Objects.requireNonNull(requestId); Objects.requireNonNull(archiveId); Objects.requireNonNull(deliveryId); Objects.requireNonNull(outcome);
            if ((outcome == Outcome.ALREADY_CONSUMED) == deliveryId.isPresent()) {
                throw new IllegalArgumentException("重放结果与投递身份不一致");
            }
        }
    }
}
