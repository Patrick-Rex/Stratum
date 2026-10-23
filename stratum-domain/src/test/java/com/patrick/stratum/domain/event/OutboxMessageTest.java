package com.patrick.stratum.domain.event;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证框架无关的消息边界及投递终态约束。 */
class OutboxMessageTest {

    /** 合法事件保持原始数据与 UTC 时间。 */
    @Test
    void retainsValidMessage() {
        var message = message("erp.material.created", "{\"name\":\"物料\"}");
        assertEquals("erp.material.created", message.eventType());
        assertEquals(Instant.EPOCH, message.occurredAt());
        assertEquals("{\"name\":\"物料\"}", message.payload());
    }

    /** UUID 大小写变体归一后使用相同唯一键，不能绕过重复事件保护。 */
    @Test
    void normalizesEventIdCase() {
        var message = new OutboxMessage("ABCDEFAB-1234-4567-89AB-ABCDEFABCDEF", "erp.material.created",
                "aggregate", Instant.EPOCH, "{}", "trace");
        assertEquals("abcdefab-1234-4567-89ab-abcdefabcdef", message.eventId());
    }

    /** RabbitMQ 通配符不能作为发布主题进入信封。 */
    @Test
    void rejectsWildcardTopic() {
        assertThrows(IllegalArgumentException.class, () -> message("erp.material.#", "{}"));
    }

    /** UTF-8 字节上限同时约束多字节消息，不能只按字符数限流。 */
    @Test
    void rejectsOversizedUnicodePayload() {
        assertThrows(IllegalArgumentException.class,
                () -> message("erp.material.created", "物".repeat(OutboxMessage.MAX_PAYLOAD_BYTES / 3 + 1)));
    }

    /** 空数据不能作为有效事件落盘。 */
    @Test
    void rejectsEmptyPayload() {
        assertThrows(IllegalArgumentException.class, () -> message("erp.material.created", " "));
    }

    /** 已发送状态不能退回待投递或被重复发送。 */
    @Test
    void sentStateIsTerminal() {
        for (OutboxStatus target : OutboxStatus.values()) {
            assertFalse(OutboxStatus.SENT.canTransitionTo(target));
        }
        assertTrue(OutboxStatus.PROCESSING.canTransitionTo(OutboxStatus.PENDING));
        assertTrue(OutboxStatus.PROCESSING.canTransitionTo(OutboxStatus.FAILED));
        assertTrue(OutboxStatus.FAILED.canTransitionTo(OutboxStatus.PENDING));
        assertFalse(OutboxStatus.PENDING.canTransitionTo(OutboxStatus.SENT));
    }

    /**
     * 创建具有固定时间和随机标识的测试信封。
     * @param topic 待验证主题
     * @param payload 待验证数据
     * @return 事件信封
     */
    private static OutboxMessage message(String topic, String payload) {
        return new OutboxMessage(UUID.randomUUID().toString(), topic, "aggregate", Instant.EPOCH, payload, "trace");
    }
}
