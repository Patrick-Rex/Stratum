package com.patrick.stratum.domain.event;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;

/**
 * 原事件人工恢复存储端口，参与已有写工作单元，不发送消息或替换事件身份。
 * <p>示例：{@code unitOfWork.execute(() -> repository.recover(request))}。</p>
 */
public interface OutboxOriginalRecoveryRepository {
    /**
     * 幂等登记恢复请求，并以真实消费事实和聚合顺序复核原事件资格。
     * @param request 请求身份、原事件、可信操作人及当前操作关联标识
     * @return 首次请求的稳定回执，不表示发布或消费已完成
     * @throws IllegalStateException 未处于已有写工作单元
     * @throws com.patrick.stratum.common.error.StratumException 请求身份冲突或事件当前不可恢复
     * @apiNote 示例：{@code repository.recover(new Request(requestId, eventId, operatorId, "manual-1"))}。
     */
    Receipt recover(Request request);

    /** 人工请求的持久结果；恢复表示可重新领取，消费成功须由凭据证明。 */
    enum Outcome {
        /** 原事件已恢复为可领取状态。 */ RECOVERED,
        /** 原事件已有可核实的消费事实。 */ ALREADY_CONSUMED
    }

    /**
     * 值对象：不含替换正文的原事件恢复请求；操作人由认证入口提供。
     * @param requestId 幂等请求UUID
     * @param eventId 原事件UUID
     * @param operatorId 服务端认证的操作人UUID
     * @param traceId 本次操作关联标识
     */
    record Request(UUID requestId, UUID eventId, UUID operatorId, String traceId) {
        /**
         * 校验身份和关联标识。
         * @param requestId 请求UUID
         * @param eventId 原事件UUID
         * @param operatorId 可信操作人UUID
         * @param traceId 安全关联标识
         * @throws NullPointerException UUID为空
         * @throws IllegalArgumentException 关联标识非法
         * @apiNote 示例：{@code new Request(requestId, eventId, operatorId, "manual-1")}。
         */
        public Request {
            Objects.requireNonNull(requestId); Objects.requireNonNull(eventId); Objects.requireNonNull(operatorId);
            if (!CorrelationIdPolicy.isValid(traceId)) throw new IllegalArgumentException("恢复关联标识非法");
        }
    }

    /**
     * 值对象：首次登记的稳定结果及操作人，不以登记成功冒充发送成功。
     * @param requestId 首次请求UUID
     * @param eventId 原事件UUID
     * @param operatorId 首次操作人UUID
     * @param traceId 首次操作关联标识
     * @param outcome 首次结果
     * @param previousFailures 恢复前累计失败次数，仅实际恢复时存在
     */
    record Receipt(UUID requestId, UUID eventId, UUID operatorId, String traceId, Outcome outcome,
                   OptionalInt previousFailures) {
        /**
         * 校验结果与恢复前失败次数一致。
         * @param requestId 首次请求UUID
         * @param eventId 原事件UUID
         * @param operatorId 首次操作人UUID
         * @param traceId 首次操作关联标识
         * @param outcome 首次结果
         * @param previousFailures 可选恢复前累计次数
         * @throws NullPointerException 必需值为空
         * @throws IllegalArgumentException 关联标识、结果或次数非法
         * @apiNote 示例：{@code new Receipt(requestId, eventId, operatorId, "manual-1", Outcome.RECOVERED, OptionalInt.of(2))}。
         */
        public Receipt {
            Objects.requireNonNull(requestId); Objects.requireNonNull(eventId); Objects.requireNonNull(operatorId);
            Objects.requireNonNull(outcome); Objects.requireNonNull(previousFailures);
            if (!CorrelationIdPolicy.isValid(traceId) || (outcome == Outcome.RECOVERED) != previousFailures.isPresent()
                    || (previousFailures.isPresent() && previousFailures.getAsInt() < 0)) {
                throw new IllegalArgumentException("恢复回执字段不一致");
            }
        }
    }
}
