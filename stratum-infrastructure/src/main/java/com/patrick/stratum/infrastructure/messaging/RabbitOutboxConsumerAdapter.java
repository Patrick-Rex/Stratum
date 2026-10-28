package com.patrick.stratum.infrastructure.messaging;

import java.io.IOException;
import java.util.Objects;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/** 将已提交的应用结果映射到单条手动确认；本类不创建监听线程或重试循环。 */
@Slf4j
final class RabbitOutboxConsumerAdapter implements ChannelAwareMessageListener {
    private final RabbitOutboxMessageCodec codec;
    private final OutboxConsumer consumer;
    private final Group group;
    private final MessageMetrics metrics;

    /**
     * 固定订阅与应用端口，不持有业务状态。
     * @param codec 严格解码器
     * @param consumer 提交后返回的应用入口
     * @param group 唯一订阅组
     */
    RabbitOutboxConsumerAdapter(RabbitOutboxMessageCodec codec, OutboxConsumer consumer, Group group) {
        this(codec, consumer, group, new MessageMetrics(null));
    }

    /**
     * 固定订阅与应用端口，并绑定不改变确认结果的观测组件。
     * @param codec 严格解码器
     * @param consumer 提交后返回的应用入口
     * @param group 唯一订阅组
     * @param metrics 低基数消息计数
     */
    RabbitOutboxConsumerAdapter(RabbitOutboxMessageCodec codec, OutboxConsumer consumer, Group group, MessageMetrics metrics) {
        this.codec = Objects.requireNonNull(codec); this.consumer = Objects.requireNonNull(consumer);
        this.group = Objects.requireNonNull(group); this.metrics = Objects.requireNonNull(metrics);
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
}
