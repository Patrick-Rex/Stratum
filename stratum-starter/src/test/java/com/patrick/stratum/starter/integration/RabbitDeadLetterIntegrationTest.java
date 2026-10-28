package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxDeadLetterCollector;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.rabbitmq.client.AMQP;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;

/** 真实Starter/DB/Redis/Broker验证原生超限后归档、归档故障保留和重复收存。 */
@Import(RabbitDeadLetterIntegrationTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitDeadLetterIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private ApplicationContext application;
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxRelayApplicationService relay;
    @Autowired private OutboxPublisher publisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private RabbitAdmin admin;
    @Autowired private CachingConnectionFactory factory;
    @Autowired private MeterRegistry metrics;
    @Autowired private CollectorProbe probe;
    private final List<String> aggregates = new ArrayList<>();

    /**
     * 为本测试专用空间启用四个订阅及较短但有界原生延迟。
     * @param registry 动态配置
     */
    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("stratum.messaging.consumer.enabled", () -> true);
        registry.add("stratum.messaging.topology.delivery-limit", () -> 1);
        registry.add("stratum.messaging.topology.retry-min", () -> "100ms");
        registry.add("stratum.messaging.topology.retry-max", () -> "100ms");
    }

    /** 重建自身拓扑并恢复四个订阅，清空本用例故障开关。 */
    @BeforeEach
    void prepare() {
        probe.unavailable.set(false); probe.failAfter.set(false); probe.failures.set(0);
        admin.initialize(); listeners().forEach(SimpleMessageListenerContainer::start);
    }

    /** 停止监听后清理本用例归档/事件/技术进度和专用拓扑。 */
    @AfterEach
    void cleanup() {
        listeners().forEach(SimpleMessageListenerContainer::stop);
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_dead_letter WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
        for (var id : probe.archivedIds) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        probe.archivedIds.clear(); RabbitIntegrationSupport.clearTopology(admin);
    }

    /** 两个业务订阅原生失败超限后自动归档原身份与正文，不推进业务进度。 */
    @Test
    void archivesBothQueuesAfterActualNativeFailureLimit() {
        double erpCount = metrics.counter("stratum.messaging.deadletter.archived", "group", "erp_audit", "category", "retries_exhausted").count();
        double cacheCount = metrics.counter("stratum.messaging.deadletter.archived", "group", "cache_invalidation", "category", "retries_exhausted").count();
        var erp = seed("erp.probe.created"); var cache = seed("stratum.cache.invalidate"); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> archives(erp) == 1 && archives(cache) == 1);
        await().atMost(Duration.ofSeconds(3)).until(() -> metrics.get("stratum.messaging.deadletter.archived")
                .tags("group", "erp_audit", "category", "retries_exhausted").counter().count() >= erpCount + 1
                && metrics.get("stratum.messaging.deadletter.archived")
                .tags("group", "cache_invalidation", "category", "retries_exhausted").counter().count() >= cacheCount + 1);
        for (var event : List.of(erp, cache)) {
            var row = jdbc.queryForMap("SELECT * FROM outbox_dead_letter WHERE event_id = ?", event.message().eventId());
            assertEquals("RETRIES_EXHAUSTED", row.get("category")); assertEquals("DEAD", row.get("status"));
            assertEquals(event.message().traceId(), row.get("trace_id")); assertEquals(event.message().payload(), row.get("payload"));
            var metadata = json.readTree(row.get("metadata_json").toString());
            assertEquals("delivery_limit", metadata.path("firstDeathReason").path("value").asString());
            assertDoesNotThrow(() -> UUID.fromString(metadata.path("stratum-publication-id").path("value").asString()));
            assertEquals(0L, jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                    Long.class, event.message().aggregateId()));
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> ready(ERP_QUEUE + ".dlq") == 0 && ready(CACHE_QUEUE + ".dlq") == 0);
    }

    /** 受控归档不可用时原DLQ原生延迟保留，恢复归档后才移除消息。 */
    @Test
    void retainsDeadLetterDuringArchiveFailureAndRecovers() {
        double failed = metrics.counter("stratum.messaging.deadletter.archive.failed", "group", "erp_audit").count();
        var event = seed("erp.probe.created"); probe.unavailable.set(true); runBatch();
        await().atMost(Duration.ofSeconds(5)).until(() -> probe.failures.get() >= 2);
        await().atMost(Duration.ofSeconds(3)).until(() -> metrics.get("stratum.messaging.deadletter.archive.failed")
                .tag("group", "erp_audit").counter().count() >= failed + 2);
        assertEquals(0, archives(event)); deadListener(ERP_QUEUE).stop();
        await().atMost(Duration.ofSeconds(3)).until(() -> ready(ERP_QUEUE + ".dlq") == 1);
        probe.unavailable.set(false); deadListener(ERP_QUEUE).start();
        await().atMost(Duration.ofSeconds(5)).until(() -> archives(event) == 1);
        await().atMost(Duration.ofSeconds(3)).until(() -> ready(ERP_QUEUE + ".dlq") == 0);
    }

    /** 提交后确认前故障导致同次发布重复归档只一行；新发布使用新投递身份区分证据周期。 */
    @Test
    void deduplicatesArchiveRedeliveryAndSeparatesPublicationAttempts() {
        var event = seed("erp.probe.created"); probe.failAfter.set(true); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> archives(event) == 1 && seen(event) == 2);
        assertEquals(OutboxPublisher.PublishResult.CONFIRMED, publisher.publish(event));
        await().atMost(Duration.ofSeconds(6)).until(() -> archives(event) == 2);
        var publications = jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$."stratum-publication-id".value'))
                FROM outbox_dead_letter WHERE event_id = ?
                """, String.class, event.message().eventId());
        assertEquals(2, new java.util.HashSet<>(publications).size());
        assertEquals(0L, jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                Long.class, event.message().aggregateId()));
    }

    /** 直接注入非法Broker原体只进入隔离归档，不被丢弃或解释为业务事件。 */
    @Test
    void quarantinesMalformedDeadLetterBody() throws Exception {
        byte[] raw = {(byte) 0xff, 0, (byte) 0xc0};
        try (var channel = factory.createConnection().createChannel(false)) {
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, new AMQP.BasicProperties.Builder().deliveryMode(2).build(), raw);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> !probe.archivedIds.isEmpty());
        var id = probe.archivedIds.getFirst();
        var row = jdbc.queryForMap("SELECT body, status, event_id FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        assertArrayEquals(raw, (byte[]) row.get("body")); assertEquals("QUARANTINED", row.get("status")); assertNull(row.get("event_id"));
    }

    /** DLQ自身超过默认20次失败仍保留原消息，不能在持久化故障时二次淘汰。 */
    @Test
    void preservesDeadLetterBeyondDefaultQuorumFailureLimit() throws Exception {
        deadListener(ERP_QUEUE).stop();
        try (var channel = factory.createConnection().createChannel(false)) {
            channel.basicQos(1); var received = new ArrayBlockingQueue<com.rabbitmq.client.Delivery>(2);
            String tag = channel.basicConsume(ERP_QUEUE + ".dlq", false, (consumer, delivery) -> {
                if (!received.offer(delivery)) throw new IllegalStateException("测试接收容量超限");
            }, consumer -> { });
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, new AMQP.BasicProperties.Builder().deliveryMode(2).build(), new byte[] {1});
            try {
                for (int attempt = 0; attempt <= 22; attempt++) {
                    var message = received.poll(5, TimeUnit.SECONDS); assertNotNull(message);
                    if (attempt < 22) channel.basicReject(message.getEnvelope().getDeliveryTag(), true);
                    else {
                        assertTrue(((Number) message.getProperties().getHeaders().get("x-delivery-count")).longValue() > 20);
                        channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
                    }
                }
            } finally { channel.basicCancel(tag); }
        }
    }

    /**
     * 查找固定业务来源对应的死信容器。
     * @param source 原业务队列
     * @return 当前死信监听
     */
    private SimpleMessageListenerContainer deadListener(String source) {
        return application.getBean(source.equals(ERP_QUEUE) ? "erpDeadLetterListener" : "cacheDeadLetterListener", SimpleMessageListenerContainer.class);
    }

    /**
     * 读取当前四个固定订阅。
     * @return 容器快照
     */
    private List<SimpleMessageListenerContainer> listeners() { return List.copyOf(application.getBeansOfType(SimpleMessageListenerContainer.class).values()); }

    /**
     * 在真实工作单元登记故障消息；业务消费端口由探针固定失败。
     * @param type 首期合法主题
     * @return 原始投递
     */
    private Delivery seed(String type) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate);
        var event = new OutboxMessage(UUID.randomUUID().toString(), type, aggregate,
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":999999999999999999.123456789012}", "dead-integration-trace");
        unitOfWork.execute(() -> { outbox.append(event); return null; }); return new Delivery(event, 1);
    }

    /** 手动执行有界Relay，不添加生产调度。 */
    private void runBatch() {
        relay.runBatch(new OutboxRelayRepository.ClaimRequest(8, Duration.ofSeconds(30)),
                new OutboxRelayRepository.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    /**
     * 查询本测试消息已提交的归档数量。
     * @param event 原事件
     * @return 行数
     */
    private int archives(Delivery event) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_dead_letter WHERE event_id = ?", Integer.class, event.message().eventId()); }

    /**
     * 查询本次发布证据的重复收存计数。
     * @param event 原事件
     * @return 当前最高收存次数
     */
    private long seen(Delivery event) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(seen_count), 0) FROM outbox_dead_letter WHERE event_id = ?", Long.class, event.message().eventId());
    }

    /**
     * 读取Broker可投递数，不将已预取数算作已确认。
     * @param queue 固定队列
     * @return Broker可投递数
     */
    private long ready(String queue) { return admin.getQueueInfo(queue).getMessageCount(); }

    /** 只替换业务结果与归档前后故障，不替换MQ或数据库。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /**
         * 固定业务失败以触发真实原生超限。
         * @return 受控失败端口
         */
        @Bean @Primary
        OutboxConsumer failingBusinessConsumer() { return (delivery, group) -> { throw new IllegalStateException("受控业务失败"); }; }

        /**
         * 包装实际应用归档端口。
         * @param collector 生产应用实现
         * @return 故障探针
         */
        @Bean @Primary
        CollectorProbe deadLetterCollectorProbe(@Qualifier("outboxDeadLetterApplicationService") OutboxDeadLetterCollector collector) {
            return new CollectorProbe(collector);
        }
    }

    /** 归档前后故障开关及成功ID集合，仅供当前用例清理。 */
    private static final class CollectorProbe implements OutboxDeadLetterCollector {
        private final OutboxDeadLetterCollector delegate;
        private final AtomicBoolean unavailable = new AtomicBoolean();
        private final AtomicBoolean failAfter = new AtomicBoolean();
        private final AtomicInteger failures = new AtomicInteger();
        private final List<UUID> archivedIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        /**
         * 包装真实事务入口。
         * @param delegate 实际应用端口
         */
        private CollectorProbe(OutboxDeadLetterCollector delegate) { this.delegate = delegate; }
        /** {@inheritDoc} */
        @Override public UUID archive(Letter letter) {
            if (unavailable.get()) { failures.incrementAndGet(); throw new IllegalStateException("受控归档不可用"); }
            var id = delegate.archive(letter); archivedIds.add(id);
            if (failAfter.compareAndSet(true, false)) throw new IllegalStateException("模拟归档提交后确认前失败");
            return id;
        }
    }
}
