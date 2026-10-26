package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxPublisherConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxPublisher.PublishResult.*;
import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在专用真实Broker空间验收生产发布适配，尚不添加Relay或消费业务。 */
@Tag("integration")
class RabbitOutboxPublisherIntegrationTest {
    private AnnotationConfigApplicationContext context;

    /** 清理本用例固定拓扑，随后关闭主/发布连接与两类工作线程。 */
    @AfterEach
    void closeResources() {
        if (context == null) return;
        try {
            if (context.isActive()) {
                var admin = context.getBean(RabbitAdmin.class);
                for (String queue : List.of(CACHE_QUEUE, ERP_QUEUE, CACHE_QUEUE + ".dlq", ERP_QUEUE + ".dlq")) {
                    admin.deleteQueue(queue);
                }
                admin.deleteExchange(EXCHANGE);
                admin.deleteExchange(DEAD_EXCHANGE);
            }
        } finally { context.close(); }
    }

    /**
     * 两订阅均获真实confirm且收到完整JSON/持久属性，发布与主连接物理隔离。
     * @throws Exception Broker协议失败
     */
    @Test
    void confirmsBothSubscriptionsAndPreservesEnvelope() throws Exception {
        start(Map.of());
        var publisher = context.getBean(OutboxPublisher.class);
        var factory = context.getBean(CachingConnectionFactory.class);
        try (var channel = factory.createConnection().createChannel(false)) {
            for (var entry : Map.of("erp.purchase.created", ERP_QUEUE, "stratum.cache.invalidate", CACHE_QUEUE).entrySet()) {
                var delivery = delivery(entry.getKey());
                assertEquals(CONFIRMED, publisher.publish(delivery));
                var received = channel.basicGet(entry.getValue(), false);
                assertNotNull(received);
                var json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .build().readTree(received.getBody());
                assertEquals(delivery.message().eventId(), received.getProps().getMessageId());
                assertEquals(delivery.message().traceId(), received.getProps().getCorrelationId());
                assertEquals(delivery.message().traceId(), received.getProps().getHeaders().get("X-Trace-Id").toString());
                assertEquals(delivery.sequence(), ((Number) received.getProps().getHeaders().get("stratum-sequence")).longValue());
                assertEquals(2, received.getProps().getDeliveryMode());
                assertEquals("application/json", received.getProps().getContentType());
                assertEquals(delivery.message().aggregateId(), json.path("aggregateId").asText());
                assertEquals(delivery.message().traceId(), json.path("traceId").asText());
                assertEquals(delivery.sequence(), json.path("sequence").asLong());
                assertEquals("value", json.path("payload").path("test").asText());
                assertEquals(0, new BigDecimal("99999999999999999999999999.123456789012")
                        .compareTo(json.path("payload").path("quantity").decimalValue()));
                channel.basicAck(received.getEnvelope().getDeliveryTag(), false);
            }
            assertNotSame(factory.createConnection().getDelegate(),
                    factory.getPublisherConnectionFactory().createConnection().getDelegate());
        }
    }

    /** 删除目标后mandatory返回不可路由，修复拓扑后原eventId可以重新发送。 */
    @Test
    void classifiesUnroutableAndRecoversSameEventAfterRepair() {
        start(Map.of());
        var factory = context.getBean(CachingConnectionFactory.class);
        var original = factory.createConnection().getDelegate();
        var publisher = context.getBean(OutboxPublisher.class);
        assertEquals(CONFIRMED, publisher.publish(delivery("stratum.cache.invalidate")));
        context.getBean(RabbitAdmin.class).deleteQueue(ERP_QUEUE);
        var delivery = delivery("erp.sale.created");
        assertEquals(RETURNED, publisher.publish(delivery));
        context.getBean(RabbitAdmin.class).initialize();
        assertEquals(CONFIRMED, publisher.publish(delivery));
        assertTrue(original.isOpen());
        assertSame(original, factory.createConnection().getDelegate());
    }

    /** 队列容量拒绝映射为REJECTED，绝不能记录为CONFIRMED。 */
    @Test
    void classifiesBrokerNackAtCapacity() {
        start(Map.of("stratum.messaging.topology.max-messages", "1"));
        var publisher = context.getBean(OutboxPublisher.class);
        assertEquals(CONFIRMED, publisher.publish(delivery("erp.sale.created")));
        OutboxPublisher.PublishResult result = CONFIRMED;
        for (int i = 0; i < 20 && result == CONFIRMED; i++) result = publisher.publish(delivery("erp.sale.created"));
        assertEquals(REJECTED, result);
    }

    /** 已建立的发布连接重置后自动重新连接，主连接与拓扑继续可用。 */
    @Test
    void reconnectsPublisherWithoutClosingMainConnection() {
        start(Map.of());
        var factory = context.getBean(CachingConnectionFactory.class);
        var main = factory.createConnection().getDelegate();
        var publisher = context.getBean(OutboxPublisher.class);
        assertEquals(CONFIRMED, publisher.publish(delivery("erp.sale.created")));
        ((CachingConnectionFactory) factory.getPublisherConnectionFactory()).resetConnection();
        assertTrue(main.isOpen());
        assertEquals(CONFIRMED, publisher.publish(delivery("erp.sale.created")));
        assertSame(main, factory.createConnection().getDelegate());
    }

    /**
     * 只装配连接、拓扑及发布器，并严格限制到本机随机专用测试空间。
     * @param overrides 本用例覆盖参数
     */
    private void start(Map<String, Object> overrides) {
        String vhost = required("STRATUM_IT_MQ_VHOST");
        String username = required("STRATUM_IT_MQ_USERNAME");
        if (!vhost.matches("stratum-it-[a-f0-9]{32}") || !username.equals(vhost)) {
            throw new IllegalStateException("消息集成测试必须使用独立stratum-it虚拟主机及同名账号");
        }
        var values = new HashMap<String, Object>();
        values.put("stratum.messaging.enabled", "true");
        values.put("stratum.messaging.host", "127.0.0.1");
        values.put("stratum.messaging.port", required("STRATUM_IT_MQ_PORT"));
        values.put("stratum.messaging.virtual-host", vhost);
        values.put("stratum.messaging.username", username);
        values.put("stratum.messaging.password", required("STRATUM_IT_MQ_PASSWORD"));
        values.putAll(overrides);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-publisher", values));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class,
                RabbitOutboxPublisherConfiguration.class);
        context.refresh();
    }

    /**
     * 构造发布探针，真实Relay会从数据库已提交记录读取相同结构。
     * @param eventType 已登记主题
     * @return 随机事件投递
     */
    private static OutboxPublisher.Delivery delivery(String eventType) {
        return new OutboxPublisher.Delivery(new OutboxMessage(UUID.randomUUID().toString(), eventType,
                UUID.randomUUID().toString(), Instant.parse("2026-10-26T00:00:00Z"), "{\"test\":\"value\",\"quantity\":99999999999999999999999999.123456789012}", "real-publisher-trace"),
                9_007_199_254_740_993L);
    }

    /**
     * 缺失测试连接配置时直接失败。
     * @param name 必需环境变量
     * @return 非空值
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少消息集成测试配置：" + name);
        return value;
    }
}
