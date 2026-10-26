package com.patrick.stratum.domain.event;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证Relay投递值对象边界，不把端口契约测试误计为真实消息投递。 */
class OutboxPublisherTest {
    /** 序号零值、负数和空信封无法进入发布契约。 */
    @Test
    void rejectsMissingMessageAndUnassignedSequence() {
        var message = message();
        assertThrows(NullPointerException.class, () -> new OutboxPublisher.Delivery(null, 1));
        for (long invalid : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> new OutboxPublisher.Delivery(message, invalid));
        }
    }

    /** 投递保持完整原始信封和64位持久化序号，不重新生成事件身份。 */
    @Test
    void preservesOriginalEnvelopeAndLargeSequence() {
        var message = message();
        var delivery = new OutboxPublisher.Delivery(message, Long.MAX_VALUE);
        assertSame(message, delivery.message());
        assertEquals(Long.MAX_VALUE, delivery.sequence());
        assertEquals(new OutboxPublisher.Delivery(message, Long.MAX_VALUE), delivery);
    }

    /** 日志摘要不带事件正文或业务关联字段，只允许身份与序号。 */
    @Test
    void doesNotExposePayloadInSummary() {
        var message = message();
        String summary = new OutboxPublisher.Delivery(message, 1).toString();
        assertTrue(summary.contains(message.eventId()));
        assertTrue(summary.contains("sequence=1"));
        assertFalse(summary.contains("sensitive-business-value"));
        assertFalse(summary.contains(message.traceId()));
    }

    /**
     * 创建纯领域测试信封。
     * @return 固定时间且随机事件标识的不可变信封
     */
    private static OutboxMessage message() {
        return new OutboxMessage(UUID.randomUUID().toString(), "erp.material.created", "aggregate",
                Instant.parse("2026-10-26T00:00:00Z"), "{\"name\":\"sensitive-business-value\"}", "publisher-test-trace");
    }
}
