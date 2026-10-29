package com.patrick.stratum.infrastructure.messaging;

import java.io.IOException;
import java.time.Duration;

import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Context;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/** 显式启用首期两个固定订阅，限制并发、预取、通道与关闭等待。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.messaging.consumer", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RabbitOutboxConsumerConfiguration.Properties.class)
@Slf4j
public class RabbitOutboxConsumerConfiguration {
    /**
     * 消费开关不能绕过消息基础设施开关。
     * @param environment 外部配置
     * @throws IllegalStateException 消息基础设施未启用
     * @apiNote Spring在consumer.enabled=true时创建本配置。
     */
    public RabbitOutboxConsumerConfiguration(Environment environment) {
        if (!environment.getProperty("stratum.messaging.enabled", Boolean.class, false)) {
            throw new IllegalStateException("启用消息消费必须先启用消息基础设施");
        }
    }

    /**
     * 创建独立消费工作池；预留两个生命周期回调线程，恢复任务等待队列同样有界。
     * @param properties 消费资源参数
     * @param connection 连接通道上限
     * @return 受Spring管理的固定线程池
     */
    @Bean
    ThreadPoolTaskExecutor rabbitConsumerExecutor(Properties properties, RabbitMessagingConfiguration.Properties connection) {
        int workers = properties.concurrency() * 2;
        if (connection.channels() < workers + 1) throw new IllegalArgumentException("消息通道上限不足以容纳两个订阅与管理通道");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers + 2); executor.setMaxPoolSize(workers + 2); executor.setQueueCapacity(workers + 2);
        executor.setThreadNamePrefix("stratum-rabbit-consumer-"); executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 装配ERP原始事件留痕订阅。
     * @param factory 主连接工厂
     * @param admin 已声明拓扑的管理器
     * @param executor 独立消费工作池
     * @param properties 资源参数
     * @param consumer 应用消费端口
     * @param json 项目JSON映射器
     * @param registries 可选指标注册表
     * @param traces 可选标准追踪边界，独立装配时使用默认适配器
     * @return 手动确认监听容器
     */
    @Bean
    SimpleMessageListenerContainer erpOutboxListener(CachingConnectionFactory factory, RabbitAdmin admin,
            @Qualifier("rabbitConsumerExecutor") ThreadPoolTaskExecutor executor, Properties properties,
            OutboxConsumer consumer, JsonMapper json, ObjectProvider<MeterRegistry> registries, ObjectProvider<StandardTraceContext> traces) {
        return listener(factory, admin, executor, properties, consumer, json, RabbitTopologyConfiguration.ERP_QUEUE, Group.ERP_AUDIT,
                new MessageMetrics(registries.getIfAvailable()), traces.getIfAvailable(OpenTelemetryTraceContext::new));
    }

    /**
     * 装配缓存失效补偿订阅。
     * @param factory 主连接工厂
     * @param admin 已声明拓扑的管理器
     * @param executor 独立消费工作池
     * @param properties 资源参数
     * @param consumer 应用消费端口
     * @param json 项目JSON映射器
     * @param registries 可选指标注册表
     * @param traces 可选标准追踪边界，独立装配时使用默认适配器
     * @return 手动确认监听容器
     */
    @Bean
    SimpleMessageListenerContainer cacheOutboxListener(CachingConnectionFactory factory, RabbitAdmin admin,
            @Qualifier("rabbitConsumerExecutor") ThreadPoolTaskExecutor executor, Properties properties,
            OutboxConsumer consumer, JsonMapper json, ObjectProvider<MeterRegistry> registries, ObjectProvider<StandardTraceContext> traces) {
        return listener(factory, admin, executor, properties, consumer, json, RabbitTopologyConfiguration.CACHE_QUEUE, Group.CACHE_INVALIDATION,
                new MessageMetrics(registries.getIfAvailable()), traces.getIfAvailable(OpenTelemetryTraceContext::new));
    }

    /**
     * 统一固定订阅的手动确认与有界生命周期，不添加本地消息重试。
     * @param factory 主工厂
     * @param admin 原拓扑管理器
     * @param executor 工作池
     * @param properties 资源参数
     * @param consumer 应用端口
     * @param json JSON映射器
     * @param queue 固定队列
     * @param group 固定组别
     * @param metrics 不改变确认语义的指标组件
     * @param trace 标准追踪边界
     * @return 配置完成的容器
     */
    private static SimpleMessageListenerContainer listener(CachingConnectionFactory factory, RabbitAdmin admin,
            ThreadPoolTaskExecutor executor, Properties properties, OutboxConsumer consumer, JsonMapper json,
            String queue, Group group, MessageMetrics metrics, StandardTraceContext trace) {
        var container = new RootIsolatedListenerContainer(factory);
        container.setQueueNames(queue); container.setAmqpAdmin(admin); container.setTaskExecutor(executor);
        container.setConcurrentConsumers(properties.concurrency()); container.setMaxConcurrentConsumers(properties.concurrency());
        container.setPrefetchCount(properties.prefetch()); container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setChannelTransacted(false); container.setDeBatchingEnabled(false); container.setBatchSize(1);
        container.setReceiveTimeout(200); container.setConsumerStartTimeout(5_000);
        container.setRecoveryInterval(1_000); container.setDeclarationRetries(1); container.setFailedDeclarationRetryInterval(1_000);
        container.setMissingQueuesFatal(true); container.setMismatchedQueuesFatal(true);
        container.setShutdownTimeout(properties.shutdownTimeout().toMillis());
        container.setForceStop(false); container.setForceCloseChannel(true);
        var adapter = new RabbitOutboxConsumerAdapter(new RabbitOutboxMessageCodec(json), consumer, group, metrics, trace);
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> dispatch(adapter, message, channel));
        container.setErrorHandler(failure -> log.atWarn().addKeyValue("consumerGroup", group.name())
                .addKeyValue("errorType", failure.getClass().getSimpleName())
                .log("消息监听传输失败 consumerGroup={} errorType={}", group, failure.getClass().getSimpleName()));
        return container;
    }

    /** 每次消息进入Spring监听前隔离工作线程遗留的标准上下文。 */
    static final class RootIsolatedListenerContainer extends SimpleMessageListenerContainer {
        /**
         * 创建受同一工厂管理的监听容器。
         * @param factory 受容器管理的RabbitMQ连接工厂
         */
        RootIsolatedListenerContainer(CachingConnectionFactory factory) { super(factory); }

        /**
         * 先进入根上下文，再调用Spring监听入口，让Agent插桩在干净边界内建立自身跨度。
         * @param channel 本次消费通道
         * @param data Spring传入的消息数据
         */
        @Override protected void executeListener(Channel channel, Object data) {
            try (var ignored = Context.root().makeCurrent()) { super.executeListener(channel, data); }
        }
    }

    /**
     * 确认传输结果未知时终止本消费通道，让Broker重新投递未确认消息，不重置共享连接。
     * @param adapter 原手动确认适配
     * @param message 当前消息
     * @param channel 当前消费通道
     * @throws IOException 确认传输失败，保持原异常交由安全错误处理器记录类型
     */
    private static void dispatch(RabbitOutboxConsumerAdapter adapter, Message message, Channel channel) throws IOException {
        try { adapter.onMessage(message, channel); }
        catch (IOException | RuntimeException failure) {
            try { channel.abort(); }
            catch (IOException | RuntimeException closeFailure) {
                log.atWarn().addKeyValue("errorType", closeFailure.getClass().getSimpleName())
                        .log("消息消费通道终止失败 errorType={}", closeFailure.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    /**
     * 值配置：两个订阅各自固定并发，单条最大传输体积仍由连接和解码器限制。
     * @param concurrency 每队列工作线程数
     * @param prefetch 每个消费者的未确认消息上限
     * @param shutdownTimeout 容器退出等待
     */
    @ConfigurationProperties("stratum.messaging.consumer")
    public record Properties(@DefaultValue("1") int concurrency, @DefaultValue("16") int prefetch,
            @DefaultValue("5s") Duration shutdownTimeout) {
        /**
         * 限制工作池、消息等待和退出预算。
         * @param concurrency 1至4
         * @param prefetch 1至32
         * @param shutdownTimeout 1至10秒，毫秒精度
         * @throws IllegalArgumentException 参数越界
         * @apiNote 示例：new Properties(1, 16, Duration.ofSeconds(5))。
         */
        public Properties {
            if (concurrency < 1 || concurrency > 4 || prefetch < 1 || prefetch > 32 || shutdownTimeout == null
                    || shutdownTimeout.compareTo(Duration.ofSeconds(1)) < 0
                    || shutdownTimeout.compareTo(Duration.ofSeconds(10)) > 0 || shutdownTimeout.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("消息消费并发、预取或退出等待超出允许范围");
            }
        }
    }
}
