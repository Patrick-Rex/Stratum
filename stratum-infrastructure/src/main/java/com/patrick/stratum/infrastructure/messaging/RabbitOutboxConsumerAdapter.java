package com.patrick.stratum.infrastructure.messaging;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import com.rabbitmq.client.Channel;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/** 将已提交的应用结果映射到单条手动确认；本类不创建监听线程或重试循环。 */
@Slf4j
final class RabbitOutboxConsumerAdapter implements ChannelAwareMessageListener {
    private final RabbitOutboxMessageCodec codec;
    private final OutboxConsumer consumer;
    private final Group group;
    private final MessageMetrics metrics;
    private final StandardTraceContext standardTrace;

    /**
     * 固定订阅与应用端口，不持有业务状态。
     * @param codec 严格解码器
     * @param consumer 提交后返回的应用入口
     * @param group 唯一订阅组
     */
    RabbitOutboxConsumerAdapter(RabbitOutboxMessageCodec codec, OutboxConsumer consumer, Group group) {
        this(codec, consumer, group, new MessageMetrics(null), new OpenTelemetryTraceContext());
    }

    /**
     * 固定订阅与应用端口，并绑定不改变确认结果的观测组件。
     * @param codec 严格解码器
     * @param consumer 提交后返回的应用入口
     * @param group 唯一订阅组
     * @param metrics 低基数消息计数
     */
    RabbitOutboxConsumerAdapter(RabbitOutboxMessageCodec codec, OutboxConsumer consumer, Group group, MessageMetrics metrics) {
        this(codec, consumer, group, metrics, new OpenTelemetryTraceContext());
    }

    /**
     * 注入当前线程标准上下文边界，不改变原消费端口及确认策略。
     * @param codec 严格解码器
     * @param consumer 提交后返回的应用入口
     * @param group 唯一订阅组
     * @param metrics 低基数消息计数
     * @param standardTrace 标准追踪边界
     * @throws NullPointerException 必需依赖为空
     */
    RabbitOutboxConsumerAdapter(RabbitOutboxMessageCodec codec, OutboxConsumer consumer, Group group, MessageMetrics metrics,
            StandardTraceContext standardTrace) {
        this.codec = Objects.requireNonNull(codec); this.consumer = Objects.requireNonNull(consumer);
        this.group = Objects.requireNonNull(group); this.metrics = Objects.requireNonNull(metrics);
        this.standardTrace = Objects.requireNonNull(standardTrace);
    }

    /**
     * 只有成功提交或重复凭据才ack，等待nack不增加失败次数，实际失败reject进入原生重试。
     * @param message Broker消息
     * @param channel 当前消费通道
     * @throws IOException 确认传输失败；不将未知确认结果转换为另一次reject
     * @apiNote 由MANUAL模式监听容器调用，例如adapter.onMessage(message, channel)。
     */
    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        String previousBusiness = MDC.get("businessTraceId"); String previousTrace = MDC.get("trace_id"); String previousSpan = MDC.get("span_id");
        MDC.remove("businessTraceId"); MDC.remove("trace_id"); MDC.remove("span_id");
        try {
            if (Thread.currentThread().isInterrupted()) { consume(message, channel); return; }
            boolean activeSpan;
            try {
                var current = Span.current().getSpanContext();
                activeSpan = current.isValid() && !current.isRemote();
            } catch (RuntimeException failure) {
                deferTraceFailure(message, channel, failure); return;
            }
            if (activeSpan) { consume(message, channel); return; }
            Optional<StandardTraceContext.Carrier> parent;
            StandardTraceContext.TraceScope restored;
            try {
                var headers = message.getMessageProperties().getHeaders();
                parent = Objects.requireNonNull(standardTrace.normalize(
                        boundedHeader(headers.get("traceparent"), 1024), boundedHeader(headers.get("tracestate"), 512)));
                restored = Objects.requireNonNull(standardTrace.restore(parent));
            } catch (RuntimeException failure) {
                deferTraceFailure(message, channel, failure); return;
            }
            try (restored) {
                parent.ifPresent(value -> {
                    MDC.put("trace_id", value.traceparent().substring(3, 35));
                    MDC.put("span_id", value.traceparent().substring(36, 52));
                });
                consume(message, channel);
            }
        } finally {
            restoreMdc("businessTraceId", previousBusiness); restoreMdc("trace_id", previousTrace); restoreMdc("span_id", previousSpan);
        }
    }

    /**
     * 在已建立的消息作用域中处理单次投递并映射手动确认。
     * @param message Broker消息
     * @param channel 当前消费通道
     * @throws IOException 确认结果未知
     */
    private void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        boolean redelivered = Boolean.TRUE.equals(message.getMessageProperties().isRedelivered());
        if (Thread.currentThread().isInterrupted()) {
            metrics.consumed(group, MessageMetrics.ConsumerResult.INTERRUPTED, redelivered);
            channel.basicNack(tag, false, true); return;
        }
        Delivery delivery = null;
        OutboxConsumer.Outcome outcome;
        try {
            delivery = codec.decode(message);
            MDC.put("businessTraceId", delivery.message().traceId());
            if (!group.accepts(delivery.message().eventType())
                    || !RabbitTopologyConfiguration.EXCHANGE.equals(message.getMessageProperties().getReceivedExchange())
                    || !delivery.message().eventType().equals(message.getMessageProperties().getReceivedRoutingKey())) {
                throw new IllegalArgumentException("传输主题与消费订阅不符");
            }
            outcome = Objects.requireNonNull(consumer.consume(delivery, group));
        } catch (RuntimeException failure) {
            metrics.consumed(group, MessageMetrics.ConsumerResult.FAILED, redelivered);
            String eventId = delivery == null ? "invalid" : delivery.message().eventId();
            String traceId = delivery == null ? "invalid" : delivery.message().traceId();
            log.atWarn().addKeyValue("eventId", eventId).addKeyValue("messageId", eventId)
                    .addKeyValue("traceId", traceId).addKeyValue("consumerGroup", group.name())
                    .addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("Outbox消费失败 eventId={} messageId={} traceId={} consumerGroup={} errorType={}",
                            eventId, eventId, traceId, group, failure.getClass().getSimpleName());
            channel.basicReject(tag, true);
            return;
        }
        var category = switch (outcome) {
            case CONSUMED -> MessageMetrics.ConsumerResult.CONSUMED;
            case DUPLICATE -> MessageMetrics.ConsumerResult.DUPLICATE;
            case WAITING -> MessageMetrics.ConsumerResult.WAITING;
        };
        metrics.consumed(group, category, redelivered);
        switch (outcome) {
            case CONSUMED, DUPLICATE -> channel.basicAck(tag, false);
            case WAITING -> channel.basicNack(tag, false, true);
        }
    }

    /**
     * 追踪组件故障时不进入业务事务，以Broker原生等待重投而不增加业务失败次数。
     * @param message 当前未确认消息
     * @param channel 当前消费通道
     * @param failure 仅记录异常类型
     * @throws IOException 重投确认结果未知
     */
    private void deferTraceFailure(Message message, Channel channel, RuntimeException failure) throws IOException {
        try { log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName())
                .addKeyValue("consumerGroup", group.name()).log("消息追踪上下文恢复失败，等待重投"); }
        catch (RuntimeException ignored) { /* 日志故障不改变消息重投。 */ }
        boolean redelivered = Boolean.TRUE.equals(message.getMessageProperties().isRedelivered());
        metrics.consumed(group, MessageMetrics.ConsumerResult.WAITING, redelivered);
        channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
    }

    /**
     * 仅接受有界文本头，拒绝未知类型及过长值，避免展开不可信对象。
     * @param value Broker原始头值
     * @param limit 允许字符数上限
     * @return 有界文本或空值
     */
    private static String boundedHeader(Object value, int limit) {
        return value instanceof String text && text.length() <= limit ? text : null;
    }

    /**
     * 恢复进入监听适配器之前的日志上下文。
     * @param key 固定MDC键
     * @param value 原值，可为空
     */
    private static void restoreMdc(String key, String value) {
        if (value == null) MDC.remove(key); else MDC.put(key, value);
    }
}
