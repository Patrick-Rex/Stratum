package com.patrick.stratum.domain.event;

import java.util.Objects;

/**
 * Outbox Relay专用发布端口，业务写用例只能追加Outbox，不得直接调用本端口。
 * <p>示例：Relay在事务外调用publisher.publish(new Delivery(persistedMessage, sequence))，
 * 仅CONFIRMED允许把已领取记录标为SENT；此结果不表示消费者已成功。</p>
 */
public interface OutboxPublisher {
    /**
     * 发布已持久化且被Relay领取的完整事件，等待有界确认，不自行重试业务消息。
     * @param delivery 复用Outbox信封与数据库持久化序号
     * @return 确认、不可路由、Broker否定、超时、不可用或非法信封；除确认外均不得标记发送成功
     * @throws IllegalArgumentException 投递内容为空、格式损坏或不在支持的事件路由范围
     * @throws IllegalStateException 调用方仍处于业务写事务
     * @apiNote 示例：PublishResult result = publisher.publish(new Delivery(message, 1L))。
     */
    PublishResult publish(Delivery delivery);

    /** 发布结果值对象枚举；结果不确定时重试必须沿用同一eventId。 */
    enum PublishResult {
        /** Broker确认。 */ CONFIRMED,
        /** Broker退回不可路由消息。 */ RETURNED,
        /** Broker否定确认，可能由暂时性容量限制引起。 */ REJECTED,
        /** 发布确认超时，实际投递结果不确定。 */ TIMED_OUT,
        /** 发布器不可用，实际投递结果可能不确定。 */ UNAVAILABLE,
        /** 本地校验确认信封无效。 */ INVALID_ENVELOPE
    }

    /**
     * 值对象：Relay领取后的不可变投递内容，不改变原始UUID与业务事件。
     * @param message 已同事务持久化的原始信封
     * @param sequence 同聚合持久化顺序号，不能用时间或UUID临时推断
     */
    record Delivery(OutboxMessage message, long sequence) {
        /**
         * 验证必需信封与正序号，序号分配由后续数据库仓储完成。
         * @param message 已落盘信封
         * @param sequence 正顺序号
         * @throws NullPointerException 信封为空
         * @throws IllegalArgumentException 序号不是正数
         * @apiNote 示例：new Delivery(message, 1L)。
         */
        public Delivery {
            Objects.requireNonNull(message, "投递信封不能为空");
            if (sequence < 1) throw new IllegalArgumentException("投递序号必须为正数");
        }

        /**
         * 只输出事件标识和序号，避免误把业务正文写入日志。
         * @return 安全摘要，例如OutboxDelivery[eventId=UUID, sequence=1]
         */
        @Override
        public String toString() {
            return "OutboxDelivery[eventId=" + message.eventId() + ", sequence=" + sequence + "]";
        }
    }
}
