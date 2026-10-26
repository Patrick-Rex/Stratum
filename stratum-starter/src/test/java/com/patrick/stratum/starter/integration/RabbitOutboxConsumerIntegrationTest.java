package com.patrick.stratum.starter.integration;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxConsumerConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxPublisherConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxConsumer.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** 真实Broker检查监听启停、原生计数、手动确认和预取归还；应用端口为受控替身。 */
@Tag("integration")
class RabbitOutboxConsumerIntegrationTest {
    private AnnotationConfigApplicationContext context;

    /** 停止自身监听后清理专用空间拓扑，关闭连接与工作池。 */
    @AfterEach
    void cleanup() {
        if (context != null && context.isActive()) {
            context.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::stop);
            if (context.getBeanNamesForType(RabbitAdmin.class).length != 0) {
                RabbitIntegrationSupport.clearTopology(context.getBean(RabbitAdmin.class));
            }
            context.close();
        }
    }

    /** 默认关闭时不创建容器/消费线程，也不需要应用端口或连接。 */
    @Test
    void disabledModeHasNoConsumerResources() {
        context = new AnnotationConfigApplicationContext();
        context.register(RabbitOutboxConsumerConfiguration.class); context.refresh();
        assertTrue(context.getBeansOfType(SimpleMessageListenerContainer.class).isEmpty());
        assertTrue(context.getBeansOfType(ThreadPoolTaskExecutor.class).isEmpty());
    }

    /** 消费开关不能绕过基础设施开关；边界配置拒绝无限并发/预取/退出。 */
    @Test
    void validatesEnablementAndResourceBounds() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("stratum.messaging.consumer.enabled", true)));
        context.register(RabbitOutboxConsumerConfiguration.class);
        assertThrows(RuntimeException.class, context::refresh);
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxConsumerConfiguration.Properties(0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxConsumerConfiguration.Properties(5, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxConsumerConfiguration.Properties(1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxConsumerConfiguration.Properties(1, 33, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxConsumerConfiguration.Properties(1, 1, Duration.ofSeconds(11)));
    }

    /** 启用时通道上限必须覆盖两个订阅及管理通道，失败不留下消费线程。 */
    @Test
    void rejectsInsufficientChannels() {
        assertThrows(RuntimeException.class, () -> start((delivery, group) -> CONSUMED,
                Map.of("stratum.messaging.channels", 2)));
    }

    /** 受控确认IOException只终止当前消费通道，不追加确认或重置真实共享连接。 */
    @Test
    void closesOnlyFailedConsumerChannelOnUnknownDisposition() throws IOException {
        start((delivery, group) -> { fail("非法信封不能进入应用"); return CONSUMED; }, Map.of());
        var listener = (ChannelAwareMessageListener) context.getBean("erpOutboxListener", SimpleMessageListenerContainer.class)
                .getMessageListener();
        var properties = new MessageProperties(); properties.setDeliveryTag(7);
        var message = new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
        var channel = mock(Channel.class);
        doThrow(new IOException("模拟确认结果未知")).when(channel).basicReject(7L, true);
        assertThrows(IOException.class, () -> listener.onMessage(message, channel));
        verify(channel).basicReject(7L, true); verify(channel).abort(); verifyNoMoreInteractions(channel);
        assertTrue(context.getBean(CachingConnectionFactory.class).createConnection().getDelegate().isOpen());
    }

    /** 真实等待nack不增加失败数，实际reject才增加，成功后消息已确认且线程有界。 */
    @Test
    void waitsThenRejectsThenAcknowledgesOnRealBroker() throws Exception {
        var attempts = new AtomicInteger(); var completed = new CountDownLatch(1);
        start((delivery, group) -> {
            assertEquals(Group.ERP_AUDIT, group);
            return switch (attempts.incrementAndGet()) {
                case 1 -> WAITING;
                case 2 -> throw new IllegalStateException("受控应用失败");
                default -> { completed.countDown(); yield CONSUMED; }
            };
        }, Map.of());
        var listener = context.getBean("erpOutboxListener", SimpleMessageListenerContainer.class);
        var counts = new CopyOnWriteArrayList<Long>();
        listener.addAfterReceivePostProcessors(message -> {
            Object value = message.getMessageProperties().getHeader("x-delivery-count");
            counts.add(value == null ? 0L : ((Number) value).longValue()); return message;
        });
        var pool = context.getBean("rabbitConsumerExecutor", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
        assertEquals(4, pool.getMaximumPoolSize()); assertEquals(AcknowledgeMode.MANUAL, listener.getAcknowledgeMode());
        assertEquals(OutboxPublisher.PublishResult.CONFIRMED, context.getBean(OutboxPublisher.class).publish(delivery()));
        assertTrue(completed.await(10, TimeUnit.SECONDS)); listener.stop();
        assertEquals(3, attempts.get()); assertEquals(List.of(0L, 0L, 1L), counts);
        assertEquals(0, context.getBean(RabbitAdmin.class).getQueueInfo(RabbitTopologyConfiguration.ERP_QUEUE).getMessageCount());
        var factory = context.getBean(CachingConnectionFactory.class); var connection = factory.createConnection().getDelegate();
        cleanup(); assertFalse(connection.isOpen()); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** 退出预算耗尽后中断当前处理并归还全部未确认消息，生命周期回调不被工作线程占满阻塞。 */
    @Test
    void returnsPrefetchedMessagesAndClosesOwnResources() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var processed = new AtomicInteger();
        start((delivery, group) -> {
            processed.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试退出等待超限"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return CONSUMED;
        }, Map.of("stratum.messaging.consumer.prefetch", 2, "stratum.messaging.consumer.shutdown-timeout", "1s"));
        var publisher = context.getBean(OutboxPublisher.class); var first = delivery(); var second = delivery();
        assertEquals(OutboxPublisher.PublishResult.CONFIRMED, publisher.publish(first));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(OutboxPublisher.PublishResult.CONFIRMED, publisher.publish(second));
        var listener = context.getBean("erpOutboxListener", SimpleMessageListenerContainer.class);
        await().atMost(Duration.ofSeconds(3)).until(() -> context.getBean(RabbitAdmin.class)
                .getQueueInfo(RabbitTopologyConfiguration.ERP_QUEUE).getMessageCount() == 0);
        var stopped = new CountDownLatch(1);
        try { listener.stop(stopped::countDown); assertTrue(stopped.await(5, TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        assertFalse(listener.isRunning());
        assertEquals(1, processed.get());
        await().atMost(Duration.ofSeconds(3)).until(() -> context.getBean(RabbitAdmin.class)
                .getQueueInfo(RabbitTopologyConfiguration.ERP_QUEUE).getMessageCount() == 2);
        var factory = context.getBean(CachingConnectionFactory.class);
        try (var channel = factory.createConnection().createChannel(false)) {
            var returnedIds = new java.util.HashSet<String>();
            for (int index = 0; index < 2; index++) {
                var returned = channel.basicGet(RabbitTopologyConfiguration.ERP_QUEUE, false);
                assertNotNull(returned); returnedIds.add(returned.getProps().getMessageId());
                channel.basicAck(returned.getEnvelope().getDeliveryTag(), false);
            }
            assertEquals(java.util.Set.of(first.message().eventId(), second.message().eventId()), returnedIds);
        }
        var pool = context.getBean("rabbitConsumerExecutor", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
        var connection = factory.createConnection().getDelegate(); cleanup();
        assertFalse(connection.isOpen()); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * 在受守卫的专用vhost装配真实工厂、拓扑、发布器及监听容器。
     * @param consumer 受控应用结果
     * @param overrides 本测试资源配置
     */
    private void start(OutboxConsumer consumer, Map<String, Object> overrides) {
        var properties = new HashMap<>(RabbitIntegrationSupport.connectionProperties());
        properties.put("stratum.messaging.consumer.enabled", true);
        properties.put("stratum.messaging.topology.retry-min", "100ms");
        properties.put("stratum.messaging.topology.retry-max", "100ms");
        properties.putAll(overrides);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.registerBean(OutboxConsumer.class, () -> consumer);
        context.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class,
                RabbitOutboxPublisherConfiguration.class, RabbitOutboxConsumerConfiguration.class);
        context.refresh();
    }

    /**
     * 生成仅供传输验收的事件，完整事务效果由E02.3d另验。
     * @return 不重复的ERP投递
     */
    private OutboxPublisher.Delivery delivery() {
        return new OutboxPublisher.Delivery(new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created",
                UUID.randomUUID().toString(), Instant.now(), "{}", "listener-it-trace"), 1);
    }
}
