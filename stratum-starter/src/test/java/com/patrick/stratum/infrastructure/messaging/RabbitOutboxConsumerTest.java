package com.patrick.stratum.infrastructure.messaging;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxConsumer.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过实际发布编码检查严格解码和手动确认，不将通道替身计为Broker验收。 */
@ExtendWith(OutputCaptureExtension.class)
class RabbitOutboxConsumerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    /** 发布器与解码器兼容，64位序号、高精度数值与持久身份完整保留。 */
    @Test
    void decodesActualPublisherEnvelopeWithoutNumericLoss() {
        var message = message(); var delivery = codec().decode(message);
        assertEquals(9_007_199_254_740_993L, delivery.sequence());
        assertEquals(message.getMessageProperties().getMessageId(), delivery.message().eventId());
        var payload = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(delivery.message().payload());
        assertEquals(0, new BigDecimal("99999999999999999999999999.123456789012")
                .compareTo(payload.path("quantity").decimalValue()));
    }

    /** 应用返回前不确认；提交与重复都只确认当前一条。 */
    @Test
    void acknowledgesOnlyAfterApplicationReturns() throws IOException {
        for (var outcome : List.of(CONSUMED, DUPLICATE)) {
            var channel = mock(Channel.class); var port = mock(OutboxConsumer.class); var message = message();
            when(port.consume(any(), eq(Group.ERP_AUDIT))).thenAnswer(call -> {
                verifyNoInteractions(channel); return outcome;
            });
            adapter(port).onMessage(message, channel);
            verify(channel).basicAck(7L, false); verifyNoMoreInteractions(channel);
        }
    }

    /** WAITING走原生nack等待，实际失败才reject计数，日志不泄露异常或载荷。 */
    @Test
    void separatesWaitingAndFailureWithoutLeakingData(CapturedOutput output) throws IOException {
        var channel = mock(Channel.class); var port = mock(OutboxConsumer.class);
        when(port.consume(any(), any())).thenReturn(WAITING);
        adapter(port).onMessage(message(), channel);
        verify(channel).basicNack(7L, false, true); verifyNoMoreInteractions(channel);
        reset(channel); when(port.consume(any(), any())).thenThrow(new IllegalStateException("TOP_SECRET_FAILURE"));
        adapter(port).onMessage(message(), channel);
        verify(channel).basicReject(7L, true); verifyNoMoreInteractions(channel);
        assertFalse(output.getAll().contains("TOP_SECRET_FAILURE"));
        assertFalse(output.getAll().contains("99999999999999999999999999"));
        assertTrue(output.getAll().contains("consumerGroup=ERP_AUDIT"));
    }

    /** 头体身份、整数与持久化元数据改变后不能进入应用入口。 */
    @Test
    void rejectsAlteredMetadataBeforeApplication() throws IOException {
        var messages = new ArrayList<Message>();
        var id = message(); id.getMessageProperties().setMessageId(UUID.randomUUID().toString()); messages.add(id);
        var trace = message(); trace.getMessageProperties().setHeader("X-Trace-Id", "other"); messages.add(trace);
        var sequence = message(); sequence.getMessageProperties().setHeader("stratum-sequence", 9_007_199_254_740_993d); messages.add(sequence);
        var aggregate = message(); aggregate.getMessageProperties().setHeader("stratum-aggregate-id", "other"); messages.add(aggregate);
        var type = message(); type.getMessageProperties().setHeader("stratum-event-type", "erp.other.created"); messages.add(type);
        var mode = message(); mode.getMessageProperties().setReceivedDeliveryMode(MessageDeliveryMode.NON_PERSISTENT); messages.add(mode);
        var missingMode = message(); missingMode.getMessageProperties().setReceivedDeliveryMode(null); messages.add(missingMode);
        var encoding = message(); encoding.getMessageProperties().setContentEncoding("UTF-16"); messages.add(encoding);
        var content = message(); content.getMessageProperties().setContentType("text/plain"); messages.add(content);
        var routing = message(); routing.getMessageProperties().setReceivedRoutingKey("erp.other.created"); messages.add(routing);
        var exchange = message(); exchange.getMessageProperties().setReceivedExchange(""); messages.add(exchange);
        for (var message : messages) {
            var port = mock(OutboxConsumer.class); var channel = mock(Channel.class);
            adapter(port).onMessage(message, channel); verifyNoInteractions(port);
            verify(channel).basicReject(7L, true); verifyNoMoreInteractions(channel);
        }
    }

    /** 载荷有界，严格拒绝重复字段、额外字段、尾随内容和非整数序号。 */
    @Test
    void rejectsMalformedAndOversizedJson() {
        var original = message(); var source = new String(original.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        var badBodies = List.of("{}", source + " {}", source.replaceFirst("\\{", "{\"eventId\":\"duplicate\","),
                source.replace("9007199254740993", "9007199254740993.0"),
                source.replace("9007199254740993", "9223372036854775808"), source.replaceFirst("\\{", "{\"extra\":1,"));
        for (var body : badBodies) assertThrows(IllegalArgumentException.class,
                () -> codec().decode(new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), original.getMessageProperties())));
        assertThrows(IllegalArgumentException.class, () -> codec().decode(new Message(new byte[512 * 1024 + 1], original.getMessageProperties())));
        assertThrows(IllegalArgumentException.class, () -> codec().decode(new Message(new byte[0], original.getMessageProperties())));
        assertThrows(IllegalArgumentException.class, () -> codec().decode(new Message(
                source.getBytes(java.nio.charset.StandardCharsets.UTF_16), original.getMessageProperties())));
        assertThrows(IllegalArgumentException.class, () -> codec().decode(new Message(
                new byte[] {(byte) 0xC3, 0x28}, original.getMessageProperties())));
    }

    /** ack传输结果未知时传播异常，不在同一通道追加reject。 */
    @Test
    void propagatesUnknownAcknowledgementWithoutSecondDisposition() throws IOException {
        var channel = mock(Channel.class); var port = mock(OutboxConsumer.class);
        when(port.consume(any(), any())).thenReturn(CONSUMED);
        doThrow(new IOException("transport-result-unknown")).when(channel).basicAck(7L, false);
        assertThrows(IOException.class, () -> adapter(port).onMessage(message(), channel));
        verify(channel).basicAck(7L, false); verifyNoMoreInteractions(channel);
    }

    /** 已中断的退出线程只退回当前消息，不启动新的应用事务。 */
    @Test
    void requeuesInterruptedDeliveryWithoutInvokingApplication() throws IOException {
        var channel = mock(Channel.class); var port = mock(OutboxConsumer.class); var message = message();
        try {
            Thread.currentThread().interrupt(); adapter(port).onMessage(message, channel);
            assertTrue(Thread.currentThread().isInterrupted()); verifyNoInteractions(port);
            verify(channel).basicNack(7L, false, true); verifyNoMoreInteractions(channel);
        } finally { Thread.interrupted(); }
    }

    /**
     * 已提交、等待和失败分别计数；已提交后的确认异常不伪造另一次处理。
     * @throws IOException 确认通道异常
     */
    @Test
    void recordsConsumptionAndRedeliveryWithoutChangingAcknowledgement() throws IOException {
        var registry = new SimpleMeterRegistry(); var port = mock(OutboxConsumer.class); var channel = mock(Channel.class);
        var observed = new RabbitOutboxConsumerAdapter(codec(), port, Group.ERP_AUDIT, new MessageMetrics(registry));
        try {
            var waiting = message(); waiting.getMessageProperties().setRedelivered(true);
            when(port.consume(any(), any())).thenReturn(WAITING);
            observed.onMessage(waiting, channel); verify(channel).basicNack(7L, false, true);
            reset(channel); when(port.consume(any(), any())).thenThrow(new IllegalStateException("secret-failure"));
            observed.onMessage(message(), channel); verify(channel).basicReject(7L, true);
            reset(channel); doReturn(CONSUMED).when(port).consume(any(), any());
            doThrow(new IOException("ack-unknown")).when(channel).basicAck(7L, false);
            assertThrows(IOException.class, () -> observed.onMessage(message(), channel)); verify(channel).basicAck(7L, false);
            verifyNoMoreInteractions(channel);
            assertEquals(1, registry.get("stratum.messaging.consumed").tags("group", "erp_audit", "result", "waiting").counter().count());
            assertEquals(1, registry.get("stratum.messaging.consumed").tags("group", "erp_audit", "result", "failed").counter().count());
            assertEquals(1, registry.get("stratum.messaging.consumed").tags("group", "erp_audit", "result", "consumed").counter().count());
            assertEquals(1, registry.get("stratum.messaging.redelivered").tag("group", "erp_audit").counter().count());
        } finally { registry.close(); }
    }

    /**
     * 创建严格解码器，不创建网络连接。
     * @return 严格解码器
     */
    private RabbitOutboxMessageCodec codec() { return new RabbitOutboxMessageCodec(json); }

    /**
     * 创建固定ERP订阅适配。
     * @param port 可控应用端口
     * @return 传输适配
     */
    private RabbitOutboxConsumerAdapter adapter(OutboxConsumer port) {
        return new RabbitOutboxConsumerAdapter(codec(), port, Group.ERP_AUDIT);
    }

    /**
     * 从真实生产发布器取得消息并设置Broker接收元数据。
     * @return 待消费消息
     */
    private Message message() {
        var template = mock(RabbitTemplate.class); var captured = new AtomicReference<Message>();
        doAnswer(call -> {
            captured.set(call.getArgument(2)); ((CorrelationData) call.getArgument(3)).getFuture()
                    .complete(new CorrelationData.Confirm(true, null)); return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try (var publisher = new RabbitOutboxPublisherConfiguration.Adapter(template, () -> {}, json,
                new RabbitOutboxPublisherConfiguration.Properties(1, Duration.ofMillis(100)))) {
            var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", "aggregate",
                    Instant.parse("2026-10-26T00:00:00.123456Z"), "{\"quantity\":99999999999999999999999999.123456789012}", "codec-trace");
            assertEquals(OutboxPublisher.PublishResult.CONFIRMED,
                    publisher.publish(new OutboxPublisher.Delivery(event, 9_007_199_254_740_993L)));
        }
        var message = captured.get(); var properties = message.getMessageProperties();
        properties.setReceivedExchange("stratum.topic"); properties.setReceivedRoutingKey("erp.probe.created");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT); properties.setDeliveryTag(7);
        return message;
    }
}
