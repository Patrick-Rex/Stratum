package com.patrick.stratum.domain.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 值对象：不可变的跨边界事件信封，由 Application 序列化数据后同事务落盘。
 * @param eventId 全局事件 UUID
 * @param eventType 三段式主题，例如 erp.material.created
 * @param aggregateId 聚合标识
 * @param occurredAt 事件发生时间，UTC
 * @param payload JSON 事件数据，由持久化端进一步验证 JSON 格式
 * @param traceId 关联链路标识
 */
public record OutboxMessage(String eventId, String eventType, String aggregateId,
        Instant occurredAt, String payload, String traceId) {
    public static final int MAX_PAYLOAD_BYTES = 262_144;

    /**
     * 验证信封边界，防止空事件、不可路由主题及无界消息进入 Outbox。
     * @param eventId 事件 UUID
     * @param eventType 三段式主题
     * @param aggregateId 非空聚合标识，最长 128 字符
     * @param occurredAt UTC 发生时间
     * @param payload 非空 JSON 数据，最大 256 KiB
     * @param traceId 非空链路标识，最长 64 字符
     * @throws IllegalArgumentException 字段非法或消息过大时抛出
     * @apiNote 示例：new OutboxMessage(UUID.randomUUID().toString(), "erp.material.created", "id", Instant.now(), "{}", "trace")。
     */
    public OutboxMessage {
        requireText(eventId, 36, "eventId");
        if (!UUID.fromString(eventId).toString().equalsIgnoreCase(eventId)) {
            throw new IllegalArgumentException("eventId 必须是标准 UUID");
        }
        eventId = UUID.fromString(eventId).toString();
        requireText(eventType, 200, "eventType");
        if (!eventType.matches("[a-z0-9_-]+\\.[a-z0-9_-]+\\.[a-z0-9_-]+")) {
            throw new IllegalArgumentException("eventType 必须是三段式主题");
        }
        requireText(aggregateId, 128, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(traceId, 64, "traceId");
        requireText(payload, MAX_PAYLOAD_BYTES, "payload");
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("事件数据不能超过 256 KiB");
        }
    }

    /**
     * 检查字符串非空及存储边界。
     * @param value 字段值
     * @param maximumLength 最大字符数
     * @param field 字段名
     * @throws IllegalArgumentException 空白或超长时抛出
     */
    private static void requireText(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " 为空或超过长度限制");
        }
    }
}
