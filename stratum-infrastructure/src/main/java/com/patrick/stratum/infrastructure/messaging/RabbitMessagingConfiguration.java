package com.patrick.stratum.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.rabbitmq.client.impl.nio.NioParams;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 显式启用的RabbitMQ连接装配，限制通道、工作队列和网络等待，不自动声明或消费消息。
 * <p>示例：设置stratum.messaging.enabled=true并提供专用账号与virtual-host后由Spring加载。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.messaging", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RabbitMessagingConfiguration.Properties.class)
public class RabbitMessagingConfiguration {
    /**
     * 配置客户端使用的有界工作池，随Spring上下文关闭。
     * @param properties 已校验的连接配置
     * @return 固定线程数、最多256项等待工作的执行器
     */
    @Bean
    ThreadPoolTaskExecutor rabbitExecutor(Properties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("stratum-rabbit-");
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 创建与消费回调隔离的单线程NIO工作池，避免最小回调池被I/O循环占满。
     * @return 一个I/O线程和最多四项等待任务的执行器
     */
    @Bean
    ThreadPoolTaskExecutor rabbitIoExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("stratum-rabbit-io-");
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    /**
     * 建立延迟连接的工厂，发布确认使用关联模式，关闭时释放客户端连接。
     * @param properties 已校验配置
     * @param rabbitExecutor 受容器管理的有界回调执行器
     * @param rabbitIoExecutor 独立NIO执行器
     * @return 不会在创建时连接Broker的缓存工厂
     */
    @Bean
    CachingConnectionFactory rabbitConnectionFactory(Properties properties,
            @Qualifier("rabbitExecutor") ThreadPoolTaskExecutor rabbitExecutor,
            @Qualifier("rabbitIoExecutor") ThreadPoolTaskExecutor rabbitIoExecutor) {
        var client = new com.rabbitmq.client.ConnectionFactory();
        client.setHost(properties.host());
        client.setPort(properties.port());
        client.setVirtualHost(properties.virtualHost());
        client.setUsername(properties.username());
        client.setPassword(properties.password());
        int timeoutMillis = Math.toIntExact(properties.timeout().toMillis());
        client.setConnectionTimeout(timeoutMillis);
        client.setHandshakeTimeout(timeoutMillis);
        client.setChannelRpcTimeout(timeoutMillis);
        client.setWorkPoolTimeout(timeoutMillis);
        client.setNioParams(new NioParams().setNbIoThreads(1).setWriteQueueCapacity(64)
                .setWriteEnqueuingTimeoutInMs(timeoutMillis)
                .setNioExecutor(rabbitIoExecutor.getThreadPoolExecutor())
                .setConnectionShutdownExecutor(rabbitExecutor.getThreadPoolExecutor()));
        client.useNio();
        client.setRequestedFrameMax(131_072);
        client.setRequestedHeartbeat(30);
        client.setRequestedChannelMax(properties.channels());
        client.setMaxInboundMessageBodySize(2 * 1024 * 1024);
        client.setAutomaticRecoveryEnabled(false);
        client.setTopologyRecoveryEnabled(false);
        var factory = new CachingConnectionFactory(client);
        factory.setChannelCacheSize(properties.channels());
        factory.setChannelCheckoutTimeout(timeoutMillis);
        factory.setCloseTimeout(timeoutMillis);
        factory.setExecutor(rabbitExecutor);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        factory.setConnectionNameStrategy(ignored -> "stratum-messaging");
        return factory;
    }

    /**
     * 不可变外部连接配置，凭据无默认值且字符串表示脱敏。
     * <p>示例：stratum.messaging.timeout=2s；channels=16。</p>
     * @param host Broker主机名或IP
     * @param port AMQP端口
     * @param virtualHost 显式指定的虚拟主机
     * @param username 外部提供的账号
     * @param password 外部提供的口令
     * @param timeout 连接、握手、RPC及通道等待时限
     * @param channels 通道数量上界
     * @param workers 客户端固定工作线程数
     */
    @ConfigurationProperties("stratum.messaging")
    public record Properties(@DefaultValue("127.0.0.1") String host,
            @DefaultValue("5672") int port, String virtualHost, String username, String password,
            @DefaultValue("2s") Duration timeout, @DefaultValue("16") int channels,
            @DefaultValue("4") int workers) {
        /**
         * 在创建任何连接资源前拒绝缺失凭据、非法地址和无界配置。
         * @param host Broker主机名或IP
         * @param port AMQP端口
         * @param virtualHost 虚拟主机
         * @param username 账号
         * @param password 口令
         * @param timeout 网络与通道等待时限
         * @param channels 通道数上界
         * @param workers 固定线程数
         * @throws IllegalArgumentException 必需项为空、超限或包含非法字符
         */
        public Properties {
            if (host == null || host.isBlank() || host.length() > 253
                    || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '/' || c == '@' || c == '?' || c == '#') || port < 1 || port > 65535) {
                throw new IllegalArgumentException("消息连接地址配置不合法");
            }
            requireCredential(virtualHost);
            requireCredential(username);
            requireCredential(password);
            if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                    || timeout.compareTo(Duration.ofSeconds(10)) > 0
                    || channels < 1 || channels > 128 || workers < 1 || workers > 16) {
                throw new IllegalArgumentException("消息连接资源配置超出允许范围");
            }
        }

        /**
         * 校验AMQP短字符串，错误不含原文。
         * @param value 虚拟主机或凭据
         * @throws IllegalArgumentException 空值、控制字符或UTF-8超限
         */
        private static void requireCredential(String value) {
            if (value == null || value.isBlank() || value.length() > 255
                    || value.getBytes(StandardCharsets.UTF_8).length > 255
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("消息虚拟主机与凭据必须显式提供且符合长度限制");
            }
        }

        /**
         * 返回固定脱敏说明，禁止配置日志携带账号和口令。
         * @return 不含任何配置原文的说明，例如RabbitMessagingProperties[配置已隐藏]
         */
        @Override
        public String toString() {
            return "RabbitMessagingProperties[配置已隐藏]";
        }
    }
}
