package com.patrick.stratum.infrastructure.messaging;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实配置绑定、关闭隔离和有界客户端生命周期，不依赖共享Broker。 */
class RabbitMessagingConfigurationTest {
    /** 缺省和显式关闭时均不创建客户端、凭据或执行器。 */
    @Test
    void disabledConfigurationDoesNotCreateMessagingResources() {
        for (var values : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("stratum.messaging.enabled", "false"))) {
            try (var context = context(values)) {
                context.refresh();
                assertTrue(context.getBeansOfType(CachingConnectionFactory.class).isEmpty());
                assertTrue(context.getBeansOfType(ThreadPoolTaskExecutor.class).isEmpty());
                assertTrue(context.getBeansOfType(RabbitMessagingConfiguration.Properties.class).isEmpty());
            }
        }
    }

    /**
     * 显式启用只装配延迟连接，绑定限制与脱敏生效；工作线程随上下文结束。
     * @throws Exception 本机套接字或执行器不可用
     */
    @Test
    void bindsLimitsWithoutOpeningConnectionAndClosesWorkers() throws Exception {
        ThreadPoolTaskExecutor executor;
        ThreadPoolTaskExecutor ioExecutor;
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var values = properties();
            values.put("stratum.messaging.host", listener.getInetAddress().getHostAddress());
            values.put("stratum.messaging.port", listener.getLocalPort());
            try (var context = context(values)) {
                context.refresh();
                var factory = context.getBean(CachingConnectionFactory.class);
                var client = factory.getRabbitConnectionFactory();
                assertEquals(16, factory.getChannelCacheSize());
                assertEquals(16, client.getRequestedChannelMax());
                assertEquals(2000, client.getConnectionTimeout());
                assertEquals(2000, client.getHandshakeTimeout());
                assertEquals(2000, client.getChannelRpcTimeout());
                assertEquals(2000, client.getWorkPoolTimeout());
                assertEquals(30, client.getRequestedHeartbeat());
                assertFalse(client.isAutomaticRecoveryEnabled());
                assertFalse(client.isTopologyRecoveryEnabled());
                assertTrue(factory.isPublisherConfirms());
                assertTrue(factory.isPublisherReturns());
                assertEquals("RabbitMessagingProperties[配置已隐藏]",
                        context.getBean(RabbitMessagingConfiguration.Properties.class).toString());
                executor = context.getBean("rabbitExecutor", ThreadPoolTaskExecutor.class);
                ioExecutor = context.getBean("rabbitIoExecutor", ThreadPoolTaskExecutor.class);
                assertEquals(131_072, client.getRequestedFrameMax());
                assertEquals(64, client.getNioParams().getWriteQueueCapacity());
                assertEquals(2000, client.getNioParams().getWriteEnqueuingTimeoutInMs());
                assertEquals(1, client.getNioParams().getNbIoThreads());
                assertSame(ioExecutor.getThreadPoolExecutor(), client.getNioParams().getNioExecutor());
                assertEquals(1, ioExecutor.getCorePoolSize());
                assertEquals(1, ioExecutor.getMaxPoolSize());
                assertEquals(4, ioExecutor.getThreadPoolExecutor().getQueue().remainingCapacity());
                assertEquals("io", ioExecutor.submit(() -> "io").get(1, TimeUnit.SECONDS));
                assertEquals(4, executor.getCorePoolSize());
                assertEquals(4, executor.getMaxPoolSize());
                assertEquals(256, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
                assertEquals("ok", executor.submit(() -> "ok").get(1, TimeUnit.SECONDS));
                listener.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, listener::accept);
            }
        }
        assertTrue(executor.getThreadPoolExecutor().isTerminated());
        assertTrue(ioExecutor.getThreadPoolExecutor().isTerminated());
    }

    /** 必要项缺失、空白、控制字符和UTF-8超限均拒绝启动且异常不携带凭据。 */
    @Test
    void rejectsMissingOrMalformedCredentials() {
        for (String field : List.of("virtual-host", "username", "password")) {
            var values = properties();
            values.remove("stratum.messaging." + field);
            rejects(values);
            for (String invalid : List.of("", " ", "invalid\ncredential", "密".repeat(86))) {
                values = properties();
                values.put("stratum.messaging." + field, invalid);
                rejects(values);
            }
        }
    }

    /** 地址与所有资源上下界在连接前校验，禁止无时限和无限通道。 */
    @Test
    void rejectsInvalidAddressesAndResourceLimits() {
        var invalidValues = Map.of(
                "host", List.of("", "amqp://localhost", "user@host", "host name"),
                "port", List.of("0", "65536"),
                "timeout", List.of("0ms", "99ms", "11s"),
                "channels", List.of("0", "129"),
                "workers", List.of("0", "17"));
        invalidValues.forEach((field, candidates) -> candidates.forEach(value -> {
            var values = properties();
            values.put("stratum.messaging." + field, value);
            rejects(values);
        }));
    }

    /**
     * 对方接受TCP但不执行AMQP握手时，连接按配置时限失败并能关闭。
     * @throws Exception 本机套接字不可用
     */
    @Test
    void timesOutUnresponsiveBrokerHandshake() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var values = properties();
            values.put("stratum.messaging.host", listener.getInetAddress().getHostAddress());
            values.put("stratum.messaging.port", listener.getLocalPort());
            values.put("stratum.messaging.timeout", "200ms");
            try (var context = context(values)) {
                context.refresh();
                long start = System.nanoTime();
                assertThrows(org.springframework.amqp.AmqpException.class,
                        () -> context.getBean(CachingConnectionFactory.class).createConnection());
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
                listener.setSoTimeout(1000);
                try (var accepted = listener.accept()) {
                    assertTrue(accepted.isConnected());
                }
            }
        }
    }

    /**
     * 创建未刷新的上下文，避免配置错误时丢失关闭句柄。
     * @param values 配置值
     * @return 由调用方关闭的上下文
     */
    private static AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-rabbit", values));
        context.register(RabbitMessagingConfiguration.class);
        return context;
    }

    /**
     * 为独立用例生成外部凭据，永不写入日志。
     * @return 可修改的配置
     */
    private static Map<String, Object> properties() {
        var values = new HashMap<String, Object>();
        values.put("stratum.messaging.enabled", "true");
        values.put("stratum.messaging.virtual-host", "isolated-test");
        values.put("stratum.messaging.username", "test-user");
        values.put("stratum.messaging.password", UUID.randomUUID().toString());
        return values;
    }

    /**
     * 验证失败关闭与异常消息脱敏。
     * @param values 非法配置
     */
    private static void rejects(Map<String, Object> values) {
        try (var context = context(values)) {
            var failure = assertThrows(RuntimeException.class, context::refresh);
            String password = (String) values.get("stratum.messaging.password");
            if (password != null && !password.isBlank()) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    assertFalse(String.valueOf(cause.getMessage()).contains(password));
                }
            }
        }
    }
}
