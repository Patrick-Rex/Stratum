package com.patrick.stratum.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxPublisher.PublishResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在Starter测试类路径验证适配故障分支，包名允许检查非公开适配而不扩大生产API。 */
class RabbitOutboxPublisherTest {
    /** 成功必须拿到confirm，完整信封和64位序号保持，payload没有二次字符串编码。 */
    @Test
    void confirmsEnvelopeAndPreservesHeaders() {
        var template = mock(RabbitTemplate.class);
        var captured = new AtomicReference<Message>();
        doAnswer(call -> {
            captured.set(call.getArgument(2));
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq("stratum.topic"), anyString(), any(Message.class), any(CorrelationData.class));
        var input = delivery("erp.material.created", "{\"name\":\"物料\",\"quantity\":99999999999999999999999999.123456789012}");
        try (var adapter = adapter(template, () -> {}, 1, 100)) {
            assertEquals(CONFIRMED, adapter.publish(input));
            var message = captured.get();
            var json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build().readTree(message.getBody());
            assertEquals(7, json.size());
            assertEquals(input.message().eventId(), json.path("eventId").asText());
            assertEquals(input.message().occurredAt().toString(), json.path("occurredAt").asText());
            assertEquals(input.sequence(), json.path("sequence").asLong());
            assertTrue(json.path("payload").isObject());
            assertEquals("物料", json.path("payload").path("name").asText());
            assertEquals(0, new BigDecimal("99999999999999999999999999.123456789012")
                    .compareTo(json.path("payload").path("quantity").decimalValue()));
            var headers = message.getMessageProperties();
            assertEquals(input.message().eventId(), headers.getMessageId());
            assertEquals(MessageDeliveryMode.PERSISTENT, headers.getDeliveryMode());
            assertEquals("application/json", headers.getContentType());
            assertEquals(input.message().traceId(), headers.getHeader("X-Trace-Id"));
            assertEquals(input.sequence(), ((Number) headers.getHeader("stratum-sequence")).longValue());
        }
        verify(template).destroy();
    }

    /** mandatory返回优先于肯定确认，否定确认也绝不成功。 */
    @Test
    void distinguishesReturnedAndRejectedConfirmations() {
        var template = mock(RabbitTemplate.class);
        var returned = new AtomicInteger();
        var resets = new AtomicInteger();
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (returned.getAndIncrement() == 0) {
                correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "stratum.topic", "erp.x.y"));
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            } else correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try (var adapter = adapter(template, resets::incrementAndGet, 1, 100)) {
            assertEquals(RETURNED, adapter.publish(delivery("erp.material.created", "{}")));
            assertEquals(REJECTED, adapter.publish(delivery("erp.material.created", "{}")));
            assertEquals(0, resets.get());
        }
    }

    /** 超时清理连接且释放本地容量；清理失败保持关闭，不能继续累积未确认消息。 */
    @Test
    void boundsConfirmWaitAndFailsClosedIfResetFails() {
        var template = mock(RabbitTemplate.class);
        var resets = new AtomicInteger();
        try (var adapter = adapter(template, resets::incrementAndGet, 1, 50)) {
            assertEquals(TIMED_OUT, adapter.publish(delivery("erp.material.created", "{}")));
            assertEquals(TIMED_OUT, adapter.publish(delivery("erp.material.created", "{}")));
            assertEquals(2, resets.get());
        }
        var failing = mock(RabbitTemplate.class);
        try (var adapter = adapter(failing, () -> { throw new IllegalStateException("reset failed"); }, 1, 50)) {
            assertEquals(TIMED_OUT, adapter.publish(delivery("erp.material.created", "{}")));
            assertEquals(UNAVAILABLE, adapter.publish(delivery("erp.material.created", "{}")));
            verify(failing, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        }
    }

    /** 调用中断和发送错误都不可用，中断标志保留且不会把异常原文交给调用者。 */
    @Test
    void preservesInterruptionAndClassifiesSendFailure() {
        var template = mock(RabbitTemplate.class);
        var resets = new AtomicInteger();
        try (var adapter = adapter(template, resets::incrementAndGet, 1, 100)) {
            Thread.currentThread().interrupt();
            try {
                assertEquals(UNAVAILABLE, adapter.publish(delivery("erp.material.created", "{}")));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            doThrow(new IllegalStateException("private-cause")).when(template)
                    .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            assertEquals(UNAVAILABLE, adapter.publish(delivery("erp.material.created", "{}")));
            doAnswer(call -> {
                ((CorrelationData) call.getArgument(3)).getFuture()
                        .completeExceptionally(new IllegalStateException("private-confirm-cause"));
                return null;
            }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            assertEquals(UNAVAILABLE, adapter.publish(delivery("erp.material.created", "{}")));
            assertEquals(3, resets.get());
        }
    }

    /**
     * 未确认发送占用唯一容量，第二调用立即拒绝；前次确认后恢复容量。
     * @throws Exception 并发用例超时
     */
    @Test
    void boundsInFlightAndRecoversCapacity() throws Exception {
        var template = mock(RabbitTemplate.class);
        var firstCorrelation = new CompletableFuture<CorrelationData>();
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (attempts.getAndIncrement() == 0) firstCorrelation.complete(correlation);
            else correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try (var adapter = adapter(template, () -> {}, 1, 2000);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> adapter.publish(delivery("erp.material.created", "{}")));
            var correlation = firstCorrelation.get(1, TimeUnit.SECONDS);
            try {
                assertEquals(UNAVAILABLE, adapter.publish(delivery("erp.material.created", "{}")));
                assertEquals(1, attempts.get());
            } finally { correlation.getFuture().complete(new CorrelationData.Confirm(true, null)); }
            assertEquals(CONFIRMED, first.get(1, TimeUnit.SECONDS));
            assertEquals(CONFIRMED, adapter.publish(delivery("erp.material.created", "{}")));
        }
    }

    /** JSON/路由/关联值非法与事务内调用在发送前拒绝，配置边界同样必须受限。 */
    @Test
    void rejectsInvalidInputAndPublishingInsideTransaction() {
        var template = mock(RabbitTemplate.class);
        try (var adapter = adapter(template, () -> {}, 1, 100)) {
            assertThrows(IllegalArgumentException.class, () -> adapter.publish(null));
            assertThrows(IllegalArgumentException.class, () -> adapter.publish(delivery("other.material.created", "{}")));
            for (String malformed : new String[]{"{", "{} {}", "plain"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> adapter.publish(delivery("erp.material.created", malformed)));
            }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(IllegalStateException.class, () -> adapter.publish(delivery("erp.material.created", "{}")));
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            verify(template, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        }
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxPublisherConfiguration.Properties(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxPublisherConfiguration.Properties(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RabbitOutboxPublisherConfiguration.Properties(129, Duration.ofSeconds(1)));
    }

    /** 包装器记录不可用及参数异常，保持原结果、异常及关闭动作。 */
    @Test
    void observesPublisherWithoutChangingFailureOrClose() {
        var template = mock(RabbitTemplate.class); var registry = new SimpleMeterRegistry();
        doThrow(new IllegalStateException("private-test-value")).when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var delegate = adapter(template, () -> {}, 1, 100);
        try (var publisher = new RabbitOutboxPublisherConfiguration.ObservedPublisher(delegate, new MessageMetrics(registry))) {
            assertEquals(UNAVAILABLE, publisher.publish(delivery("erp.material.created", "{}")));
            assertThrows(IllegalArgumentException.class, () -> publisher.publish(null));
            assertEquals(1, registry.get("stratum.messaging.published").tag("result", "unavailable").counter().count());
            assertEquals(1, registry.get("stratum.messaging.published").tag("result", "exception").counter().count());
            assertNull(registry.find("stratum.messaging.consumed").counter());
        } finally { registry.close(); }
        verify(template).destroy();
    }

    /** Spring关闭外层发布端口时同时关闭被包装的真实客户端。 */
    @Test
    void closesObservedPublisherWithSpringContext() {
        var template = mock(RabbitTemplate.class);
        var delegate = adapter(template, () -> {}, 1, 100);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(OutboxPublisher.class,
                    () -> new RabbitOutboxPublisherConfiguration.ObservedPublisher(delegate, new MessageMetrics(null)));
            context.refresh(); assertNotNull(context.getBean(OutboxPublisher.class));
        }
        verify(template).destroy();
    }

    /**
     * 构造明确可控的适配，不创建真实连接。
     * @param template 发送替身
     * @param reset 重置动作
     * @param capacity 并发上限
     * @param millis 确认等待毫秒
     * @return 由用例关闭的适配
     */
    private static RabbitOutboxPublisherConfiguration.Adapter adapter(RabbitTemplate template, Runnable reset,
            int capacity, int millis) {
        return new RabbitOutboxPublisherConfiguration.Adapter(template, reset, JsonMapper.builder().build(),
                new RabbitOutboxPublisherConfiguration.Properties(capacity, Duration.ofMillis(millis)));
    }

    /**
     * 构造纯测试投递，序号高于JavaScript精确整数上界以验证long保留。
     * @param eventType 测试主题
     * @param payload JSON正文
     * @return 不可变测试投递
     */
    private static OutboxPublisher.Delivery delivery(String eventType, String payload) {
        var message = new OutboxMessage(UUID.randomUUID().toString(), eventType, "aggregate-test",
                Instant.parse("2026-10-26T00:00:00Z"), payload, "publisher-trace");
        return new OutboxPublisher.Delivery(message, 9_007_199_254_740_993L);
    }
}
