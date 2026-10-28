package com.patrick.stratum.infrastructure.messaging;

import java.time.Duration;

import com.patrick.stratum.domain.event.DeadLetterBacklogProbe;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 首期缓存补偿和ERP事件留痕的持久化拓扑，不包含消息发布或消费者。
 * <p>示例：启用stratum.messaging后，启动时验证Broker版本并声明Topic与四个Quorum队列。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RabbitTopologyConfiguration.Properties.class)
public class RabbitTopologyConfiguration {
    public static final String EXCHANGE = "stratum.topic";
    public static final String DEAD_EXCHANGE = "stratum.dlx";
    public static final String CACHE_QUEUE = "q.stratum.invalidate";
    public static final String ERP_QUEUE = "q.erp.events";

    /**
     * 声明固定订阅及死信路由，队列不设置会删除待处理事件的TTL。
     * @param properties 队列容量和重试配置
     * @return 主Topic、死信Direct、四队列及四绑定
     */
    @Bean
    Declarables rabbitTopology(Properties properties) {
        var topic = new TopicExchange(EXCHANGE, true, false);
        var dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        var cache = businessQueue(CACHE_QUEUE, properties);
        var erp = businessQueue(ERP_QUEUE, properties);
        var cacheDead = deadQueue(CACHE_QUEUE + ".dlq", properties);
        var erpDead = deadQueue(ERP_QUEUE + ".dlq", properties);
        return new Declarables(topic, dead, cacheDead, erpDead, cache, erp,
                BindingBuilder.bind(cache).to(topic).with("stratum.cache.invalidate"),
                BindingBuilder.bind(erp).to(topic).with("erp.#"),
                BindingBuilder.bind(cacheDead).to(dead).with(CACHE_QUEUE),
                BindingBuilder.bind(erpDead).to(dead).with(ERP_QUEUE));
    }

    /**
     * 创建由初始化步骤显式启用的管理员，版本核实前不会自动声明资源。
     * @param factory 有界连接工厂
     * @return 尚未自动启动的拓扑管理员
     */
    @Bean
    RabbitAdmin rabbitAdmin(CachingConnectionFactory factory) {
        var admin = new RabbitAdmin(factory);
        admin.setAutoStartup(false);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }

    /**
     * 提供只读死信队列探针，不创建采集任务或消费消息。
     * @param admin 使用现有有界连接的管理员
     * @return 固定订阅组的单次查询端口
     */
    @Bean DeadLetterBacklogProbe deadLetterBacklogProbe(RabbitAdmin admin) {
        return new RabbitDeadLetterBacklogProbe(admin);
    }

    /**
     * 核实原生重试版本后声明全部拓扑，声明失败中止启动；重连时继续自动声明。
     * @param factory 连接工厂
     * @param rabbitAdmin 拓扑管理员
     * @return 所有单例装配后的初始化动作
     */
    @Bean
    SmartInitializingSingleton rabbitTopologyInitializer(CachingConnectionFactory factory, RabbitAdmin rabbitAdmin) {
        return () -> {
            var connection = factory.createConnection();
            try {
                String version = String.valueOf(connection.getDelegate().getServerProperties().get("version"));
                String[] parts = version.split("\\.", 3);
                if (parts.length < 2 || !parts[0].matches("[0-9]{1,3}") || !parts[1].matches("[0-9]{1,3}")) {
                    throw new IllegalStateException("不能确认RabbitMQ原生延迟重试版本");
                }
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                if (major < 4 || (major == 4 && minor < 3)) {
                    throw new IllegalStateException("消息拓扑要求RabbitMQ 4.3或更高版本");
                }
                rabbitAdmin.setAutoStartup(true);
                rabbitAdmin.afterPropertiesSet();
                rabbitAdmin.initialize();
            } finally {
                connection.close();
            }
        };
    }

    /**
     * 构造原生延迟重试和至少一次死信转移的业务队列。
     * @param name 固定队列名
     * @param properties 已校验配置
     * @return 持久化业务队列
     */
    private static Queue businessQueue(String name, Properties properties) {
        return queueBuilder(name, properties)
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(name)
                .deliveryLimit(properties.deliveryLimit())
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-delayed-retry-type", "all")
                .withArgument("x-delayed-retry-min", properties.retryMin().toMillis())
                .withArgument("x-delayed-retry-max", properties.retryMax().toMillis()).build();
    }

    /**
     * 归档故障时保留死信，取消DLQ自身的次数淘汰并使用原生有界延迟，容量限制保持不变。
     * @param name 固定死信队列
     * @param properties 容量和延迟边界
     * @return 不设置TTL及二次死信转移的持久化队列
     */
    private static Queue deadQueue(String name, Properties properties) {
        return queueBuilder(name, properties).deliveryLimit(-1)
                .withArgument("x-delayed-retry-type", "all")
                .withArgument("x-delayed-retry-min", properties.retryMin().toMillis())
                .withArgument("x-delayed-retry-max", properties.retryMax().toMillis()).build();
    }

    /**
     * 为业务与死信统一声明容量拒绝，保留最早消息。
     * @param name 固定队列名
     * @param properties 已校验配置
     * @return 可继续配置的持久化Quorum构建器
     */
    private static QueueBuilder queueBuilder(String name, Properties properties) {
        return QueueBuilder.durable(name).quorum().maxLength(properties.maxMessages())
                .maxLengthBytes(properties.maxBytes()).overflow(QueueBuilder.Overflow.rejectPublish);
    }

    /**
     * 拓扑容量和Broker原生重试边界，修改后需重启并按Broker等价声明规则迁移。
     * <p>示例：stratum.messaging.topology.retry-min=1s。</p>
     * @param maxMessages 单队列消息阈值，Quorum存在少量在途超额
     * @param maxBytes 单队列字节阈值
     * @param deliveryLimit 失败重投次数上限
     * @param retryMin 原生线性延迟基数
     * @param retryMax 原生延迟上限
     */
    @ConfigurationProperties("stratum.messaging.topology")
    public record Properties(@DefaultValue("10000") int maxMessages,
            @DefaultValue("67108864") int maxBytes, @DefaultValue("5") int deliveryLimit,
            @DefaultValue("1s") Duration retryMin, @DefaultValue("30s") Duration retryMax) {
        /**
         * 拒绝无界容量、关闭重试保护和超长延迟配置。
         * @param maxMessages 消息阈值
         * @param maxBytes 字节阈值
         * @param deliveryLimit 失败次数上限
         * @param retryMin 最小延迟
         * @param retryMax 最大延迟
         * @throws IllegalArgumentException 任一配置超出允许边界
         */
        public Properties {
            if (maxMessages < 1 || maxMessages > 100_000 || maxBytes < 1024 || maxBytes > 256 * 1024 * 1024
                    || deliveryLimit < 1 || deliveryLimit > 100 || retryMin == null || retryMax == null
                    || retryMin.compareTo(Duration.ofMillis(100)) < 0 || retryMin.compareTo(Duration.ofMinutes(1)) > 0
                    || retryMax.compareTo(retryMin) < 0 || retryMax.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("消息拓扑容量或重试配置超出允许范围");
            }
        }
    }
}
