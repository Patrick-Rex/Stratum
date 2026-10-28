package com.patrick.stratum.infrastructure.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 装配仅供Outbox Relay使用的有界发布确认适配，不添加业务调用或后台调度。
 * <p>示例：Relay注入OutboxPublisher，在事务外发布已领取且持久化的Delivery。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RabbitOutboxPublisherConfiguration.Properties.class)
public class RabbitOutboxPublisherConfiguration {
    /**
     * 使用独立发布连接，使超时清理不重置声明或消费连接。
     * @param factory 主连接工厂
     * @param json 应用JSON映射器
     * @param properties 发布并发与确认等待参数
     * @param registries 可选指标注册表，独立装配时允许没有注册表
     * @param traces 可选标准追踪边界，独立装配时使用默认适配器
     * @return 随容器销毁的Outbox发布端口
     */
    @Bean
    OutboxPublisher outboxPublisher(CachingConnectionFactory factory, JsonMapper json, Properties properties,
            ObjectProvider<MeterRegistry> registries, ObjectProvider<StandardTraceContext> traces) {
        if (!(factory.getPublisherConnectionFactory() instanceof CachingConnectionFactory publisherFactory)) {
            throw new IllegalStateException("消息发布必须使用独立缓存连接工厂");
        }
        var template = new RabbitTemplate(factory);
        template.setUsePublisherConnection(true);
        template.setMandatory(true);
        template.afterPropertiesSet();
        return new ObservedPublisher(new Adapter(template, publisherFactory::resetConnection, json, properties,
                traces.getIfAvailable(OpenTelemetryTraceContext::new)),
                new MessageMetrics(registries.getIfAvailable()));
    }

    /** 发布端口观测装饰器，保持原返回、异常和关闭语义。 */
    static final class ObservedPublisher implements OutboxPublisher, AutoCloseable {
        private final Adapter delegate;
        private final MessageMetrics metrics;

        /**
         * 包装已创建的发布适配器，不改变连接或线程生命周期。
         * @param delegate 实际发布适配
         * @param metrics 消息计数组件
         */
        ObservedPublisher(Adapter delegate, MessageMetrics metrics) {
            this.delegate = java.util.Objects.requireNonNull(delegate); this.metrics = java.util.Objects.requireNonNull(metrics);
        }

        /** {@inheritDoc} */
        @Override public PublishResult publish(Delivery delivery) {
            try {
                var result = delegate.publish(delivery); metrics.published(result); return result;
            } catch (RuntimeException failure) {
                metrics.publishException(); throw failure;
            }
        }

        /** 关闭被包装的发布客户端。 */
        @Override public void close() { delegate.close(); }
    }

    /**
     * 发布资源配置；确认时限从send返回后开始，传输入队另受连接配置约束。
     * <p>示例：stratum.messaging.publisher.confirm-timeout=2s。</p>
     * @param maxInFlight 并发发送和等待确认的上限
     * @param confirmTimeout 单次确认等待时限
     */
    @ConfigurationProperties("stratum.messaging.publisher")
    public record Properties(@DefaultValue("16") int maxInFlight,
            @DefaultValue("2s") Duration confirmTimeout) {
        /**
         * 拒绝无限并发与无界等待。
         * @param maxInFlight 并发上限
         * @param confirmTimeout 确认等待
         * @throws IllegalArgumentException 参数越界
         */
        public Properties {
            if (maxInFlight < 1 || maxInFlight > 128 || confirmTimeout == null
                    || confirmTimeout.compareTo(Duration.ofMillis(50)) < 0
                    || confirmTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
                throw new IllegalArgumentException("消息发布并发或确认等待超出允许范围");
            }
        }
    }

    /** 发布适配只持有有界并发和客户端资源，不保存跨实例消息状态。 */
    static final class Adapter implements OutboxPublisher, AutoCloseable {
        private static final Logger LOG = LoggerFactory.getLogger(Adapter.class);
        private final RabbitTemplate template;
        private final Runnable resetPublisher;
        private final JsonMapper json;
        private final Properties properties;
        private final StandardTraceContext standardTrace;
        private final Semaphore capacity;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 注入发送客户端与独立连接清理动作，构造时不发送消息。
         * @param template 已启用mandatory的客户端
         * @param resetPublisher 仅重置发布连接的动作
         * @param json JSON映射器
         * @param properties 已校验参数
         */
        Adapter(RabbitTemplate template, Runnable resetPublisher, JsonMapper json, Properties properties) {
            this(template, resetPublisher, json, properties, new OpenTelemetryTraceContext());
        }

        /**
         * 注入标准上下文边界，供发送时提取有限传播头。
         * @param template 已启用mandatory的客户端
         * @param resetPublisher 仅重置发布连接的动作
         * @param json JSON映射器
         * @param properties 已校验参数
         * @param standardTrace 当前线程标准追踪边界
         * @throws NullPointerException 标准追踪边界为空
         */
        Adapter(RabbitTemplate template, Runnable resetPublisher, JsonMapper json, Properties properties, StandardTraceContext standardTrace) {
            this.template = template;
            this.resetPublisher = resetPublisher;
            this.json = json;
            this.properties = properties;
            this.standardTrace = java.util.Objects.requireNonNull(standardTrace);
            this.capacity = new Semaphore(properties.maxInFlight());
        }

        /** {@inheritDoc} */
        @Override
        public PublishResult publish(Delivery delivery) {
            if (delivery == null) throw new IllegalArgumentException("投递内容不能为空");
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("不能在业务写事务内发布Outbox消息");
            }
            if (closed.get() || !capacity.tryAcquire()) return PublishResult.UNAVAILABLE;
            try {
                Message message = encode(delivery);
                var correlation = new CorrelationData(UUID.randomUUID().toString());
                try {
                    template.send(RabbitTopologyConfiguration.EXCHANGE, delivery.message().eventType(), message, correlation);
                    var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    if (correlation.getReturned() != null) return PublishResult.RETURNED;
                    return confirm.isAck() ? PublishResult.CONFIRMED : PublishResult.REJECTED;
                } catch (TimeoutException failure) {
                    resetConnection();
                    return PublishResult.TIMED_OUT;
                } catch (InterruptedException failure) {
                    resetConnection();
                    Thread.currentThread().interrupt();
                    return PublishResult.UNAVAILABLE;
                } catch (ExecutionException | RuntimeException failure) {
                    resetConnection();
                    return PublishResult.UNAVAILABLE;
                }
            } finally {
                capacity.release();
            }
        }

        /**
         * 编码完整JSON信封与安全关联头，保持payload为JSON值而非二次转义字符串。
         * @param delivery 已落盘投递
         * @return 最大512KiB的持久化消息
         * @throws IllegalArgumentException 主题、关联值或JSON数据无效
         */
        private Message encode(Delivery delivery) {
            var event = delivery.message();
            if (!(event.eventType().startsWith("erp.") || event.eventType().equals("stratum.cache.invalidate"))
                    || !CorrelationIdPolicy.isValid(event.traceId())) {
                throw new IllegalArgumentException("消息主题或关联标识不符合首期订阅契约");
            }
            byte[] body;
            try {
                var payload = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(event.payload());
                body = json.writeValueAsBytes(Map.of("eventId", event.eventId(), "eventType", event.eventType(),
                        "aggregateId", event.aggregateId(), "occurredAt", event.occurredAt().toString(),
                        "payload", payload, "traceId", event.traceId(), "sequence", delivery.sequence()));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("消息数据不是有效JSON信封");
            }
            if (body.length > 512 * 1024) throw new IllegalArgumentException("消息信封超过512KiB限制");
            var headers = new MessageProperties();
            headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            headers.setContentEncoding("UTF-8");
            headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            headers.setMessageId(event.eventId());
            headers.setCorrelationId(event.traceId());
            headers.setHeader(CorrelationIdPolicy.TRACE_HEADER, event.traceId());
            headers.setHeader("stratum-sequence", delivery.sequence());
            headers.setHeader("stratum-publication-id", UUID.randomUUID().toString());
            headers.setHeader("stratum-aggregate-id", event.aggregateId());
            headers.setHeader("stratum-event-type", event.eventType());
            addStandardTrace(headers);
            return new Message(body, headers);
        }

        /**
         * 只传播当前有效W3C上下文，不保存或手动传播baggage；提取故障不阻断业务发布。
         * @param headers 待发送的消息属性
         */
        private void addStandardTrace(MessageProperties headers) {
            try {
                standardTrace.capture().flatMap(value -> standardTrace.normalize(value.traceparent(), value.tracestate())).ifPresent(value -> {
                    headers.setHeader("traceparent", value.traceparent());
                    if (!value.tracestate().isEmpty()) headers.setHeader("tracestate", value.tracestate());
                });
            } catch (RuntimeException failure) {
                headers.getHeaders().remove("traceparent"); headers.getHeaders().remove("tracestate");
                try { LOG.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("消息标准追踪上下文捕获失败"); }
                catch (RuntimeException ignored) { /* 告警故障不改变消息投递。 */ }
            }
        }

        /** 超时或连接错误时清理挂起确认；清理失败则保持关闭，避免后续无限累积。 */
        private void resetConnection() {
            try {
                resetPublisher.run();
            } catch (RuntimeException failure) {
                closed.set(true);
            }
        }

        /** 关闭发送客户端，连接工厂由容器按依赖顺序销毁。 */
        @Override
        public void close() {
            closed.set(true);
            template.destroy();
        }
    }
}
