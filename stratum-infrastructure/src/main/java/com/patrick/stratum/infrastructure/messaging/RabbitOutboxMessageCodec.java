package com.patrick.stratum.infrastructure.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** 有界解码原始Outbox信封，验证头体一致性，不输出不可信消息内容。 */
final class RabbitOutboxMessageCodec {
    private final ObjectReader reader;

    /**
     * 固定无损数值及严格JSON读取策略。
     * @param json 项目JSON映射器
     */
    RabbitOutboxMessageCodec(JsonMapper json) {
        reader = Objects.requireNonNull(json).reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /**
     * 将不可信传输数据恢复为领域投递，后续仍须由存储核验原事件。
     * @param message 有界AMQP消息
     * @return 原身份与正序号
     * @throws IllegalArgumentException 大小、JSON、元数据或身份非法；异常不携带正文
     */
    Delivery decode(Message message) {
        try {
            if (message == null || message.getBody().length == 0 || message.getBody().length > 512 * 1024) invalid();
            var properties = message.getMessageProperties();
            if (!MessageProperties.CONTENT_TYPE_JSON.equals(properties.getContentType())
                    || !"UTF-8".equalsIgnoreCase(properties.getContentEncoding()) || properties.getReceivedDeliveryMode() != MessageDeliveryMode.PERSISTENT) invalid();
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(message.getBody())).toString();
            JsonNode root = reader.readTree(body);
            if (!root.isObject() || root.size() != 7 || root.get("payload") == null) invalid();
            var sequence = root.get("sequence");
            if (sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()) invalid();
            var event = new OutboxMessage(text(root, "eventId"), text(root, "eventType"), text(root, "aggregateId"),
                    Instant.parse(text(root, "occurredAt")), root.get("payload").toString(), text(root, "traceId"));
            var delivery = new Delivery(event, sequence.longValue());
            if (!CorrelationIdPolicy.isValid(event.traceId()) || !event.eventId().equals(properties.getMessageId())
                    || !event.traceId().equals(properties.getCorrelationId())
                    || !event.traceId().equals(properties.getHeader(CorrelationIdPolicy.TRACE_HEADER))
                    || !event.aggregateId().equals(properties.getHeader("stratum-aggregate-id"))
                    || !event.eventType().equals(properties.getHeader("stratum-event-type"))
                    || headerSequence(properties.getHeader("stratum-sequence")) != delivery.sequence()) invalid();
            return delivery;
        } catch (RuntimeException | CharacterCodingException failure) {
            throw new IllegalArgumentException("AMQP信封不符合Outbox消费契约");
        }
    }

    /**
     * 拒绝JSON隐式类型转换。
     * @param root 根对象
     * @param field 固定字段名
     * @return 文本值
     */
    private static String text(JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isTextual()) invalid();
        return value.asString();
    }

    /**
     * 仅接收AMQP整数类型，避免浮点数将64位序号舍入。
     * @param value 不可信头值
     * @return 无损整数
     */
    private static long headerSequence(Object value) {
        return switch (value) {
            case Long number -> number;
            case Integer number -> number.longValue();
            case Short number -> number.longValue();
            case Byte number -> number.longValue();
            default -> throw new IllegalArgumentException("序号头必须是整数");
        };
    }

    /**
     * 使用固定错误说明拒绝不可信字段。
     * @throws IllegalArgumentException 契约不符
     */
    private static void invalid() { throw new IllegalArgumentException("AMQP信封非法"); }
}
