package com.patrick.stratum.infrastructure.messaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterCollector;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/** 随消息消费开关启用两个DLQ归档订阅，提交后确认，失败留在原DLQ延迟等待。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.messaging.consumer", name = "enabled", havingValue = "true")
@Slf4j
public class RabbitDeadLetterConfiguration {
    /**
     * 两个归档工作线程及两个生命周期线程，全部等待资源有界。
     * @param connection 主连接配置
     * @param business 业务订阅并发
     * @return 随上下文退出的归档工作池
     */
    @Bean
    ThreadPoolTaskExecutor rabbitDeadLetterExecutor(RabbitMessagingConfiguration.Properties connection,
            RabbitOutboxConsumerConfiguration.Properties business) {
        if (connection.channels() < business.concurrency() * 2 + 3) {
            throw new IllegalArgumentException("消息通道上限不足以容纳业务、死信及管理通道");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); executor.setMaxPoolSize(4); executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("stratum-rabbit-dead-"); executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 装配ERP死信归档。
     * @param factory 主工厂
     * @param admin 受管理拓扑
     * @param executor 归档工作池
     * @param settings 有界退出预算
     * @param collector 应用归档端口
     * @param json JSON映射器
     * @param registries 可选指标注册表
     * @return 单工作线程、预取1的手动确认容器
     */
    @Bean
    SimpleMessageListenerContainer erpDeadLetterListener(CachingConnectionFactory factory, RabbitAdmin admin,
            @Qualifier("rabbitDeadLetterExecutor") ThreadPoolTaskExecutor executor,
            RabbitOutboxConsumerConfiguration.Properties settings, OutboxDeadLetterCollector collector, JsonMapper json,
            ObjectProvider<MeterRegistry> registries) {
        return listener(factory, admin, executor, settings, collector, json, RabbitTopologyConfiguration.ERP_QUEUE, Group.ERP_AUDIT,
                new MessageMetrics(registries.getIfAvailable()));
    }

    /**
     * 装配缓存补偿死信归档。
     * @param factory 主工厂
     * @param admin 受管理拓扑
     * @param executor 归档工作池
     * @param settings 有界退出预算
     * @param collector 应用归档端口
     * @param json JSON映射器
     * @param registries 可选指标注册表
     * @return 单工作线程、预取1的手动确认容器
     */
    @Bean
    SimpleMessageListenerContainer cacheDeadLetterListener(CachingConnectionFactory factory, RabbitAdmin admin,
            @Qualifier("rabbitDeadLetterExecutor") ThreadPoolTaskExecutor executor,
            RabbitOutboxConsumerConfiguration.Properties settings, OutboxDeadLetterCollector collector, JsonMapper json,
            ObjectProvider<MeterRegistry> registries) {
        return listener(factory, admin, executor, settings, collector, json, RabbitTopologyConfiguration.CACHE_QUEUE, Group.CACHE_INVALIDATION,
                new MessageMetrics(registries.getIfAvailable()));
    }

    /**
     * 统一归档容器的有限资源及安全传输错误处理。
     * @param factory 主连接
     * @param admin 原拓扑
     * @param executor 归档池
     * @param settings 退出预算
     * @param collector 应用端口
     * @param json JSON映射器
     * @param source 固定业务来源队列
     * @param group 固定订阅组
     * @param metrics 不改变归档及确认语义的指标
     * @return 已配置容器
     */
    private static SimpleMessageListenerContainer listener(CachingConnectionFactory factory, RabbitAdmin admin,
            ThreadPoolTaskExecutor executor, RabbitOutboxConsumerConfiguration.Properties settings,
            OutboxDeadLetterCollector collector, JsonMapper json, String source, Group group, MessageMetrics metrics) {
        var container = new SimpleMessageListenerContainer(factory);
        container.setQueueNames(source + ".dlq"); container.setAmqpAdmin(admin); container.setTaskExecutor(executor);
        container.setConcurrentConsumers(1); container.setMaxConcurrentConsumers(1); container.setPrefetchCount(1);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL); container.setDeBatchingEnabled(false); container.setBatchSize(1);
        container.setReceiveTimeout(200); container.setConsumerStartTimeout(5_000); container.setRecoveryInterval(1_000);
        container.setDeclarationRetries(1); container.setFailedDeclarationRetryInterval(1_000);
        container.setMissingQueuesFatal(true); container.setMismatchedQueuesFatal(true);
        container.setShutdownTimeout(settings.shutdownTimeout().toMillis()); container.setForceCloseChannel(true);
        var codec = new RabbitOutboxMessageCodec(json);
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            try { archive(message, channel, collector, codec, json, source, group, metrics); }
            catch (IOException | RuntimeException failure) {
                try { channel.abort(); }
                catch (IOException | RuntimeException closeFailure) {
                    log.atWarn().addKeyValue("errorType", closeFailure.getClass().getSimpleName())
                            .log("死信消费通道终止失败 errorType={}", closeFailure.getClass().getSimpleName());
                }
                throw failure;
            }
        });
        container.setErrorHandler(failure -> log.atWarn().addKeyValue("consumerGroup", group.name())
                .addKeyValue("errorType", failure.getClass().getSimpleName())
                .log("死信监听传输失败 consumerGroup={} errorType={}", group, failure.getClass().getSimpleName()));
        return container;
    }

    /**
     * 归档提交后才确认，应用失败用原生nack延迟保留，不丢弃非法信封。
     * @param message 原消息
     * @param channel 原通道
     * @param collector 提交后返回的应用端口
     * @param codec 原信封解码器
     * @param json 元数据编码器
     * @param source 固定来源队列
     * @param group 固定来源组
     * @param metrics 不改变归档及确认语义的指标
     * @throws IOException 确认传输异常；由外层关闭本通道恢复
     */
    private static void archive(Message message, Channel channel, OutboxDeadLetterCollector collector,
            RabbitOutboxMessageCodec codec, JsonMapper json, String source, Group group, MessageMetrics metrics) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        if (Thread.currentThread().isInterrupted()) { channel.basicNack(tag, false, true); return; }
        try {
            var properties = message.getMessageProperties();
            Optional<Delivery> delivery;
            try {
                var parsed = codec.decode(message);
                if (!group.accepts(parsed.message().eventType()) || !source.equals(properties.getReceivedRoutingKey())
                        || !(source + ".dlq").equals(properties.getConsumerQueue())
                        || !RabbitTopologyConfiguration.DEAD_EXCHANGE.equals(properties.getReceivedExchange())
                        || !"delivery_limit".equals(String.valueOf(properties.getHeaders().get("x-first-death-reason")))) {
                    throw new IllegalArgumentException("死信来源不符");
                }
                delivery = Optional.of(parsed);
            } catch (IllegalArgumentException invalid) { delivery = Optional.empty(); }
            var letter = new Letter(UUID.randomUUID(), group, message.getBody(), metadata(properties, json, source),
                    delivery, delivery.isPresent() ? Category.RETRIES_EXHAUSTED : Category.INVALID_ENVELOPE,
                    count(properties.getHeader("x-delivery-count")));
            if (collector.archive(letter) == null) throw new IllegalStateException("死信归档未返回标识");
            metrics.archived(group, letter.category());
        } catch (RuntimeException failure) {
            metrics.archiveFailed(group);
            log.atWarn().addKeyValue("consumerGroup", group.name()).addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("死信归档失败 consumerGroup={} errorType={}", group, failure.getClass().getSimpleName());
            channel.basicNack(tag, false, true);
            return;
        }
        channel.basicAck(tag, false);
    }

    /**
     * 保存稳定且有界的已登记头摘要，不纳入投递标签或本次重投计数，避免重复归档产生新身份。
     * @param properties Broker接收属性
     * @param json JSON编码器
     * @param source 固定来源
     * @return 有界元数据对象JSON
     */
    private static String metadata(MessageProperties properties, JsonMapper json, String source) {
        var values = new HashMap<String, Object>();
        values.put("source", source); values.put("messageId", snapshot(properties.getMessageId()));
        values.put("correlationId", snapshot(properties.getCorrelationId()));
        values.put("contentType", snapshot(properties.getContentType())); values.put("contentEncoding", snapshot(properties.getContentEncoding()));
        values.put("deliveryMode", String.valueOf(properties.getReceivedDeliveryMode()));
        for (String header : List.of("X-Trace-Id", "stratum-sequence", "stratum-aggregate-id", "stratum-event-type", "stratum-publication-id")) {
            values.put(header, snapshot(properties.getHeader(header)));
        }
        values.put("firstDeathReason", snapshot(properties.getHeader("x-first-death-reason")));
        values.put("firstDeathQueue", snapshot(properties.getHeader("x-first-death-queue")));
        Object history = properties.getHeader("x-death");
        if (history instanceof List<?> deaths && !deaths.isEmpty() && deaths.getFirst() instanceof Map<?, ?> first) {
            values.put("deathCount", count(first.get("count"))); values.put("deathReason", snapshot(first.get("reason")));
            Object time = first.get("time"); values.put("deathTime", time instanceof Date date ? date.getTime() : snapshot(time));
        }
        return json.writeValueAsString(values);
    }

    /**
     * 非法超长头只保留摘要和指纹；原体完整保留，不让不可信头突破归档内存预算。
     * @param value AMQP属性值
     * @return 原短值或有界预览与SHA-256
     */
    private static Map<String, Object> snapshot(Object value) {
        String text = value == null ? "" : value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (text.length() <= 1024 && bytes.length <= 4096) return Map.of("value", text);
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return Map.of("preview", text.substring(0, Math.min(1024, text.length())), "bytes", bytes.length, "sha256", fingerprint);
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("JDK缺少标准SHA-256算法"); }
    }

    /**
     * 仅采纳Broker整数计数，缺失或非法时为0，不从配置推断历史次数。
     * @param value Broker提供的计数
     * @return 非负可见计数
     */
    private static long count(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                ? Math.max(0, ((Number) value).longValue()) : 0;
    }
}
