package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitDeadLetterBacklogProbe;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在显式指定的本机独立虚拟主机验证真实Broker，完全不连接业务数据库。 */
@Tag("integration")
class RabbitTopologyIntegrationTest {
    private AnnotationConfigApplicationContext context;

    /**
     * 删除当前独立虚拟主机的固定测试拓扑，再关闭所有客户端资源。
     * @throws Exception 通道关闭失败
     */
    @AfterEach
    void closeResources() throws Exception {
        if (context == null) return;
        try {
            if (context.isActive()) {
                RabbitIntegrationSupport.clearTopology(context.getBean(RabbitAdmin.class));
            }
        } finally {
            context.close();
        }
    }

    /**
     * 两次声明保持等价；Topic只把缓存和ERP事件分别送至对应持久化队列。
     * @throws Exception Broker不可用或协议失败
     */
    @Test
    void declaresIdempotentlyAndRoutesOnlyKnownSubscriptions() throws Exception {
        start(Map.of());
        context.getBean(RabbitAdmin.class).initialize();
        try (var channel = channel()) {
            publish(channel, "stratum.cache.invalidate", "cache");
            publish(channel, "erp.purchase.created", "erp");
            var cache = channel.basicGet(CACHE_QUEUE, false);
            var erp = channel.basicGet(ERP_QUEUE, false);
            assertNotNull(cache); assertNotNull(erp);
            assertEquals("cache", new String(cache.getBody(), StandardCharsets.UTF_8));
            assertEquals("erp", new String(erp.getBody(), StandardCharsets.UTF_8));
            assertEquals(2, erp.getProps().getDeliveryMode());
            channel.basicAck(cache.getEnvelope().getDeliveryTag(), false);
            channel.basicAck(erp.getEnvelope().getDeliveryTag(), false);
            assertNull(channel.basicGet(CACHE_QUEUE, true));
            assertNull(channel.basicGet(ERP_QUEUE, true));
            assertNull(channel.basicGet(CACHE_QUEUE + ".dlq", true));
            assertNull(channel.basicGet(ERP_QUEUE + ".dlq", true));
        }
    }

    /**
     * 被动查询核对两个死信队列就绪数、未确认消息及独立故障恢复。
     * @throws Exception Broker不可用或通道操作失败
     */
    @Test
    void readsVisibleDeadLettersWithoutCountingUnacknowledged() throws Exception {
        start(Map.of());
        var admin = context.getBean(RabbitAdmin.class);
        var probe = new RabbitDeadLetterBacklogProbe(admin);
        try (var channel = channel()) {
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "first".getBytes(StandardCharsets.UTF_8));
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "second".getBytes(StandardCharsets.UTF_8));
            channel.basicPublish(DEAD_EXCHANGE, CACHE_QUEUE, null, "cache".getBytes(StandardCharsets.UTF_8));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                    probe.ready(Group.ERP_AUDIT).orElse(-1) == 2 && probe.ready(Group.CACHE_INVALIDATION).orElse(-1) == 1);
            var held = channel.basicGet(ERP_QUEUE + ".dlq", false); assertNotNull(held);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> probe.ready(Group.ERP_AUDIT).orElse(-1) == 1);
            assertEquals(1, probe.ready(Group.CACHE_INVALIDATION).orElseThrow());
            channel.basicAck(held.getEnvelope().getDeliveryTag(), false);
            assertTrue(admin.deleteQueue(ERP_QUEUE + ".dlq"));
            assertTrue(probe.ready(Group.ERP_AUDIT).isEmpty());
            assertEquals(1, probe.ready(Group.CACHE_INVALIDATION).orElseThrow());
            admin.initialize();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> probe.ready(Group.ERP_AUDIT).orElse(-1) == 0);
        }
    }

    /** 查询异常不被误报为零，非法订阅组由调用方修正。 */
    @Test
    void treatsBacklogQueryExceptionAsUnavailable() {
        var admin = mock(RabbitAdmin.class);
        when(admin.getQueueInfo(ERP_QUEUE + ".dlq")).thenThrow(new IllegalStateException("测试故障"));
        var probe = new RabbitDeadLetterBacklogProbe(admin);
        assertTrue(probe.ready(Group.ERP_AUDIT).isEmpty());
        assertThrows(NullPointerException.class, () -> probe.ready(null));
    }

    /**
     * 死信队列原生延迟重试期间不计入可见数，延迟到期后重新可见。
     * @throws Exception Broker通道或等待失败
     */
    @Test
    void excludesDeadLetterNativeDelayedRetryFromVisibleCount() throws Exception {
        start(Map.of("stratum.messaging.topology.retry-min", "3s", "stratum.messaging.topology.retry-max", "3s"));
        var probe = new RabbitDeadLetterBacklogProbe(context.getBean(RabbitAdmin.class));
        try (var channel = channel()) {
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "delayed".getBytes(StandardCharsets.UTF_8));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> probe.ready(Group.ERP_AUDIT).orElse(-1) == 1);
            var delivery = channel.basicGet(ERP_QUEUE + ".dlq", false); assertNotNull(delivery);
            long started = System.nanoTime();
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            var acknowledgedState = channel.queueDeclarePassive(ERP_QUEUE + ".dlq");
            assertEquals(0, acknowledgedState.getMessageCount());
            assertEquals(0, probe.ready(Group.ERP_AUDIT).orElseThrow());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> probe.ready(Group.ERP_AUDIT).orElse(-1) == 1);
            assertTrue(System.nanoTime() - started >= Duration.ofSeconds(2).toNanos());
            var returned = channel.basicGet(ERP_QUEUE + ".dlq", false); assertNotNull(returned);
            channel.basicAck(returned.getEnvelope().getDeliveryTag(), false);
        }
    }

    /**
     * nack显式返回使用原生延迟而不计失败；成功ack后不留消息。
     * @throws Exception Broker不可用或等待超时
     */
    @Test
    void delaysReturnedMessageWithoutCountingItAsFailure() throws Exception {
        start(Map.of());
        try (var channel = channel()) {
            channel.basicQos(1);
            var deliveries = deliveries(channel, CACHE_QUEUE);
            publish(channel, "stratum.cache.invalidate", "returned");
            var first = take(deliveries);
            long start = System.nanoTime();
            channel.basicNack(first.getEnvelope().getDeliveryTag(), false, true);
            var second = take(deliveries);
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(180));
            assertTrue(second.getEnvelope().isRedeliver());
            var headers = second.getProperties().getHeaders();
            assertEquals(0L, ((Number) headers.getOrDefault("x-delivery-count", 0L)).longValue());
            assertTrue(((Number) headers.get("x-acquired-count")).longValue() >= 1);
            channel.basicAck(second.getEnvelope().getDeliveryTag(), false);
        }
    }

    /**
     * reject失败按Broker线性延迟重试，超限进入对应DLQ并保留事件标识和正文。
     * @throws Exception Broker不可用或等待超时
     */
    @Test
    void deadLettersAfterNativeFailureLimit() throws Exception {
        start(Map.of());
        try (var channel = channel()) {
            channel.basicQos(1);
            var business = deliveries(channel, ERP_QUEUE);
            var dead = deliveries(channel, ERP_QUEUE + ".dlq");
            String id = publish(channel, "erp.sale.fulfilled", "poison");
            for (int attempt = 0; attempt <= 2; attempt++) {
                var delivery = take(business);
                assertEquals(id, delivery.getProperties().getMessageId());
                long count = ((Number) delivery.getProperties().getHeaders()
                        .getOrDefault("x-delivery-count", 0L)).longValue();
                assertEquals(attempt, count);
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
            }
            var failed = take(dead);
            assertEquals(id, failed.getProperties().getMessageId());
            assertEquals("poison", new String(failed.getBody(), StandardCharsets.UTF_8));
            assertEquals(2, failed.getProperties().getDeliveryMode());
            assertTrue(String.valueOf(failed.getProperties().getHeaders().get("x-death")).contains("delivery_limit"));
            channel.basicAck(failed.getEnvelope().getDeliveryTag(), false);
            assertNull(channel.basicGet(ERP_QUEUE, true));
        }
    }

    /**
     * Quorum容量阈值最终拒绝发布，已接收的最早消息保持不被丢头。
     * @throws Exception Broker不可用或确认失败
     */
    @Test
    void rejectsQueueOverflowWithoutDroppingOldestMessage() throws Exception {
        start(Map.of("stratum.messaging.topology.max-messages", "1"));
        try (var channel = channel()) {
            String first = publish(channel, "erp.purchase.created", "oldest");
            boolean rejected = false;
            for (int i = 0; i < 20 && !rejected; i++) {
                channel.basicPublish(EXCHANGE, "erp.purchase.created", properties(), new byte[]{1});
                rejected = !channel.waitForConfirms(2000);
            }
            assertTrue(rejected, "容量超限必须收到Broker否定确认");
            assertEquals(first, channel.basicGet(ERP_QUEUE, true).getProps().getMessageId());
        }
    }

    /** 旧版或无法识别的Broker在拓扑声明前失败，连接句柄仍被释放。 */
    @Test
    void rejectsUnsupportedBrokerBeforeDeclaringTopology() {
        for (String version : List.of("4.2.9", "3.13.0", "unknown")) {
            var factory = mock(CachingConnectionFactory.class);
            var connection = mock(org.springframework.amqp.rabbit.connection.Connection.class);
            var delegate = mock(com.rabbitmq.client.Connection.class);
            when(factory.createConnection()).thenReturn(connection);
            when(connection.getDelegate()).thenReturn(delegate);
            when(delegate.getServerProperties()).thenReturn(Map.of("version", version));
            try (var unsupported = new AnnotationConfigApplicationContext()) {
                unsupported.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "enabled", Map.of("stratum.messaging.enabled", "true")));
                unsupported.registerBean(CachingConnectionFactory.class, () -> factory);
                unsupported.register(RabbitTopologyConfiguration.class);
                assertThrows(RuntimeException.class, unsupported::refresh);
                verify(connection).close();
                verify(connection, never()).createChannel(anyBoolean());
            }
        }
    }

    /** 拓扑配置拒绝无界容量、非法次数和延迟，同时默认关闭不创建RabbitAdmin。 */
    @Test
    void validatesBoundsAndKeepsDisabledTopologyAbsent() {
        var invalid = List.of(
                new Object[]{0, 1024, 1, Duration.ofMillis(100), Duration.ofSeconds(1)},
                new Object[]{1, 1023, 1, Duration.ofMillis(100), Duration.ofSeconds(1)},
                new Object[]{1, 1024, 0, Duration.ofMillis(100), Duration.ofSeconds(1)},
                new Object[]{1, 1024, 1, Duration.ofMillis(99), Duration.ofSeconds(1)},
                new Object[]{1, 1024, 1, Duration.ofSeconds(2), Duration.ofSeconds(1)});
        for (Object[] values : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new RabbitTopologyConfiguration.Properties(
                    (int) values[0], (int) values[1], (int) values[2], (Duration) values[3], (Duration) values[4]));
        }
        try (var disabled = new AnnotationConfigApplicationContext()) {
            disabled.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class);
            disabled.refresh();
            assertTrue(disabled.getBeansOfType(RabbitAdmin.class).isEmpty());
            assertTrue(disabled.getBeansOfType(CachingConnectionFactory.class).isEmpty());
        }
    }

    /**
     * 只允许显式专用虚拟主机与账号，避免清理用户现有队列。
     * @param overrides 本用例拓扑参数
     */
    private void start(Map<String, Object> overrides) {
        var values = RabbitIntegrationSupport.connectionProperties();
        values.put("stratum.messaging.topology.retry-min", "200ms");
        values.put("stratum.messaging.topology.retry-max", "600ms");
        values.put("stratum.messaging.topology.delivery-limit", "2");
        values.putAll(overrides);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-mq", values));
        context.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class);
        context.refresh();
    }

    /**
     * 借用真实通道，调用方使用try资源管理归还。
     * @return 具备发布确认的通道
     */
    private Channel channel() {
        return context.getBean(CachingConnectionFactory.class).createConnection().createChannel(false);
    }

    /**
     * 注册有界投递接收器；prefetch由调用方限制。
     * @param channel 当前通道
     * @param queue 固定队列
     * @return 容量为8的接收队列
     * @throws Exception 消费注册失败
     */
    private static ArrayBlockingQueue<Delivery> deliveries(Channel channel, String queue) throws Exception {
        var received = new ArrayBlockingQueue<Delivery>(8);
        channel.basicConsume(queue, false, (tag, delivery) -> received.offer(delivery), tag -> {});
        return received;
    }

    /**
     * 有时限等待真实Broker投递，不使用轮询睡眠。
     * @param deliveries 有界接收队列
     * @return 实际消息
     * @throws InterruptedException 当前线程被中断
     */
    private static Delivery take(ArrayBlockingQueue<Delivery> deliveries) throws InterruptedException {
        var delivery = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivery, "Broker未在时限内投递消息");
        return delivery;
    }

    /**
     * 仅测试夹具向真实Broker发布探针，不构成生产绕过Outbox的发送路径。
     * @param channel 当前通道
     * @param route 探针路由
     * @param body 探针正文
     * @return 已获确认的消息标识
     * @throws Exception 发布失败
     */
    private static String publish(Channel channel, String route, String body) throws Exception {
        var properties = properties();
        channel.basicPublish(EXCHANGE, route, properties, body.getBytes(StandardCharsets.UTF_8));
        assertTrue(channel.waitForConfirms(2000));
        return properties.getMessageId();
    }

    /**
     * 创建持久化探针属性，空Header供原生重试计数追加。
     * @return 随机标识的探针属性
     */
    private static AMQP.BasicProperties properties() {
        return new AMQP.BasicProperties.Builder().messageId(UUID.randomUUID().toString())
                .deliveryMode(2).contentType("application/octet-stream").headers(Map.of()).build();
    }

}
