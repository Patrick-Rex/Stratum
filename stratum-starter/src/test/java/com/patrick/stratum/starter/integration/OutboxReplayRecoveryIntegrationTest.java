package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.patrick.stratum.application.cache.CacheUpdateCoordinator;
import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRequester;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.rabbitmq.client.AMQP;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.patrick.stratum.domain.event.OutboxReplayRepository.Outcome.*;
import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 真实DB/Redis/Broker核验原生超限、归档、人工意图、Relay重放和后序恢复。 */
@Import(OutboxReplayRecoveryIntegrationTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxReplayRecoveryIntegrationTest extends MariaDbIntegrationTest {
    private static final VersionedCache.Policy POLICY = new VersionedCache.Policy(Duration.ofMinutes(5), Duration.ZERO);
    @Autowired private ApplicationContext application;
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxRelayApplicationService relay;
    @Autowired private OutboxReplayRequester requester;
    @Autowired private CacheUpdateCoordinator coordinator;
    @Autowired private BusinessProbe business;
    @Autowired private CacheProbe cache;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RabbitAdmin admin;
    @Autowired private CachingConnectionFactory factory;
    private final List<String> aggregates = new ArrayList<>();
    private final Set<UUID> archiveIds = new LinkedHashSet<>();
    private final List<String> keys = new ArrayList<>();

    /**
     * 在专用消息空间开启四订阅并缩短测试重试，不改变生产默认值。
     * @param registry 测试配置
     */
    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("stratum.messaging.consumer.enabled", () -> true);
        registry.add("stratum.messaging.topology.delivery-limit", () -> 1);
        registry.add("stratum.messaging.topology.retry-min", () -> "100ms");
        registry.add("stratum.messaging.topology.retry-max", () -> "100ms");
    }

    /** 恢复自身拓扑和监听，所有故障状态在用例间重置。 */
    @BeforeEach
    void prepare() {
        business.failed.clear(); cache.unavailable.set(false); cache.effects.set(0);
        admin.initialize(); application.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::start);
    }

    /** 先停监听，再按引用顺序清理本用例数据库与Redis及专用拓扑。 */
    @AfterEach
    void cleanup() {
        application.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::stop);
        for (var aggregate : aggregates) {
            jdbc.queryForList("SELECT dead_id FROM outbox_dead_letter WHERE aggregate_id = ?", String.class, aggregate)
                    .forEach(value -> archiveIds.add(UUID.fromString(value)));
        }
        for (var id : archiveIds) jdbc.update("DELETE FROM outbox_replay_request WHERE dead_id = ?", id.toString());
        for (var id : archiveIds) jdbc.update("DELETE FROM outbox_replay_delivery WHERE dead_id = ?", id.toString());
        for (var id : archiveIds) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
        for (var key : keys) redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
        TraceIdContext.clear(); RabbitIntegrationSupport.clearTopology(admin);
    }

    /** 前序真实超限阻塞后序，其他聚合继续；人工持久意图经Relay恢复并保留精确原事件。 */
    @Test
    void recoversDeadPredecessorAndContinuesInOrder() {
        var events = seed(2); var other = seed(1).getFirst(); business.failed.add(events.getFirst().message().eventId()); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> archiveCount(events.getFirst()) == 1 && receipts(other) == 1);
        assertEquals(0L, progress(events.getFirst())); assertEquals("PENDING", originalStatus(events.getLast()));
        assertEquals(0, runBatch()); var archive = archives(events.getFirst()).getFirst();
        var request = request(archive); var result = requester.request(request); assertEquals(QUEUED, result.outcome());
        assertEquals(0L, progress(events.getFirst())); business.failed.remove(events.getFirst().message().eventId());
        await().atMost(Duration.ofSeconds(6)).until(() -> { runBatch(); return receipts(events.getLast()) == 1; });
        assertEquals(2L, progress(events.getFirst())); assertEquals(1L, progress(other));
        assertEquals("RESOLVED", intentStatus(result.deliveryId().orElseThrow())); assertEquals("RESOLVED", archiveStatus(archive));
        assertEquals("SENT", originalStatus(events.getFirst()));
        for (var event : events) {
            var row = jdbc.queryForMap("SELECT payload, trace_id, event_sequence FROM outbox_consumption WHERE event_id = ?", event.message().eventId());
            assertEquals(event.message().payload(), row.get("payload")); assertEquals(event.message().traceId(), row.get("trace_id"));
            assertEquals(event.sequence(), ((Number) row.get("event_sequence")).longValue());
        }
        assertEquals(result, requester.request(request)); assertEquals(ALREADY_CONSUMED, requester.request(request(archive)).outcome());
        assertEquals(1, receipts(events.getFirst()));
    }

    /** 真实提交后缓存失败留下意图，超限后人工重放只执行一次有效Redis版本补偿。 */
    @Test
    void restoresActualCacheCompensationExactlyOnce() {
        String key = "biz:erp:replay-recovery:" + UUID.randomUUID(); keys.add(key);
        assertTrue(cache.putIfNewer(key, new VersionedCache.Value(1, "old"), POLICY)); cache.unavailable.set(true);
        String eventId = unitOfWork.execute(() -> coordinator.invalidateAfterCommit(key, 2, POLICY));
        var event = persisted(eventId); aggregates.add(event.message().aggregateId()); business.failed.add(eventId);
        assertNotNull(redis.opsForValue().get(key)); assertEquals(0, cache.effects.get()); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> archiveCount(event) == 1);
        UUID archive = archives(event).getFirst(); var request = request(archive); var result = requester.request(request);
        cache.unavailable.set(false); business.failed.remove(eventId);
        await().atMost(Duration.ofSeconds(6)).until(() -> { runBatch(); return receipts(event) == 1; });
        assertNull(redis.opsForValue().get(key)); assertFalse(cache.putIfNewer(key, new VersionedCache.Value(1, "late"), POLICY));
        assertTrue(redis.getExpire("version:" + key.substring(4), java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
        String stamp = redis.opsForValue().get("version:" + key.substring(4));
        assertEquals(1, cache.effects.get()); assertEquals("RESOLVED", intentStatus(result.deliveryId().orElseThrow()));
        assertEquals(result, requester.request(request)); assertEquals(ALREADY_CONSUMED, requester.request(request(archive)).outcome());
        assertEquals(0, runBatch()); assertEquals(1, cache.effects.get());
        assertEquals(stamp, redis.opsForValue().get("version:" + key.substring(4))); assertEquals(1L, progress(event));
    }

    /** 重放本身再次超限形成新证据周期，可针对新归档恢复且业务仍只有一次效果。 */
    @Test
    void recoversAnotherDeadLetterCycleWithoutChangingBusinessIdentity() {
        var event = seed(1).getFirst(); business.failed.add(event.message().eventId()); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> archiveCount(event) == 1);
        var firstArchive = archives(event).getFirst(); var first = requester.request(request(firstArchive)); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> archiveCount(event) == 2);
        assertEquals(0, receipts(event)); assertEquals("SENT", intentStatus(first.deliveryId().orElseThrow()));
        var secondArchive = archives(event).stream().filter(id -> !id.equals(firstArchive)).findFirst().orElseThrow();
        assertEquals(ALREADY_QUEUED, requester.request(request(firstArchive)).outcome());
        var second = requester.request(request(secondArchive)); assertEquals(QUEUED, second.outcome());
        business.failed.remove(event.message().eventId()); runBatch();
        await().atMost(Duration.ofSeconds(6)).until(() -> receipts(event) == 1);
        assertEquals("RESOLVED", intentStatus(first.deliveryId().orElseThrow())); assertEquals("RESOLVED", intentStatus(second.deliveryId().orElseThrow()));
        assertEquals("RESOLVED", archiveStatus(firstArchive)); assertEquals("RESOLVED", archiveStatus(secondArchive));
        assertEquals(1L, progress(event)); assertEquals("SENT", originalStatus(event)); assertEquals(1, receipts(event));
    }

    /** 真实Broker非法原体隔离后，人工应用入口仍拒绝创建重放意图。 */
    @Test
    void refusesQuarantinedBrokerEvidenceAtApplicationBoundary() throws Exception {
        byte[] raw = ("invalid-" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var channel = factory.createConnection().createChannel(false)) {
            channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, new AMQP.BasicProperties.Builder().deliveryMode(2).build(), raw);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_dead_letter WHERE body = ?", Integer.class, raw) == 1);
        UUID archive = UUID.fromString(jdbc.queryForObject("SELECT dead_id FROM outbox_dead_letter WHERE body = ?", String.class, raw));
        archiveIds.add(archive); assertEquals("QUARANTINED", archiveStatus(archive));
        var failure = assertThrows(StratumException.class, () -> requester.request(request(archive)));
        assertEquals(40000, failure.getErrorCode().getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_delivery WHERE dead_id = ?", Integer.class, archive.toString()));
    }

    /**
     * 同工作单元登记同聚合连续原始信封，不用SQL修改投递或消费状态。
     * @param count 条数
     * @return 原始投递序列
     */
    private List<Delivery> seed(int count) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate); var events = new ArrayList<Delivery>();
        unitOfWork.execute(() -> {
            for (int index = 1; index <= count; index++) {
                var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                        Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":999999999999999999.123456789012}", "replay-recovery-it");
                outbox.append(event); events.add(new Delivery(event, index));
            } return null;
        }); return List.copyOf(events);
    }

    /**
     * 读取本用例真实生产协调器生成的原信封和持久化序号。
     * @param id 自身事件UUID
     * @return 原始事件
     */
    private Delivery persisted(String id) {
        return jdbc.queryForObject("SELECT * FROM outbox_event WHERE event_id = ?", (row, number) -> new Delivery(new OutboxMessage(
                row.getString("event_id"), row.getString("event_type"), row.getString("aggregate_id"),
                row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), row.getString("payload"), row.getString("trace_id")),
                row.getLong("event_sequence")), id);
    }

    /**
     * 手动运行有限批次，不构造自动调度。
     * @return 已持久化发布结果数
     */
    private int runBatch() {
        return relay.runBatch(new OutboxRelayRepository.ClaimRequest(8, Duration.ofSeconds(30)),
                new OutboxRelayRepository.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    /**
     * 构造人工操作身份。
     * @param archive 原归档UUID
     * @return 请求
     */
    private OutboxReplayRepository.Request request(UUID archive) { return new OutboxReplayRepository.Request(UUID.randomUUID(), archive, "manual-recovery-request"); }

    /**
     * 读取事件所属归档集合，不用时间或随机ID猜测因果。
     * @param event 原事件
     * @return 实际归档ID
     */
    private List<UUID> archives(Delivery event) { return jdbc.queryForList("SELECT dead_id FROM outbox_dead_letter WHERE event_id = ?", String.class, event.message().eventId()).stream().map(UUID::fromString).toList(); }

    /**
     * 读取归档数。
     * @param event 原事件
     * @return 行数
     */
    private int archiveCount(Delivery event) { return archives(event).size(); }

    /**
     * 读取真实消费凭据数。
     * @param event 原事件
     * @return 行数
     */
    private int receipts(Delivery event) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id = ?", Integer.class, event.message().eventId()); }

    /**
     * 读取聚合消费进度。
     * @param event 原事件
     * @return 已消费序号
     */
    private long progress(Delivery event) { return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, event.message().aggregateId()); }

    /**
     * 读取原投递状态。
     * @param event 原事件
     * @return 原状态
     */
    private String originalStatus(Delivery event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, event.message().eventId()); }

    /**
     * 读取归档状态。
     * @param archive 归档UUID
     * @return 当前状态
     */
    private String archiveStatus(UUID archive) { return jdbc.queryForObject("SELECT status FROM outbox_dead_letter WHERE dead_id = ?", String.class, archive.toString()); }

    /**
     * 读取独立意图状态。
     * @param id 意图UUID
     * @return 当前状态
     */
    private String intentStatus(UUID id) { return jdbc.queryForObject("SELECT status FROM outbox_replay_delivery WHERE delivery_id = ?", String.class, id.toString()); }

    /** 包装实际应用/缓存，只在明示事件或提交后缓存调用前注入故障。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /**
         * 包装真实应用端口。
         * @param delegate 实际生产消费者
         * @return 受控失败包装
         */
        @Bean @Primary
        BusinessProbe recoveryBusinessProbe(@Qualifier("outboxConsumerApplicationService") OutboxConsumer delegate) { return new BusinessProbe(delegate); }

        /**
         * 包装真实Redis门面，不替代其版本语义。
         * @param delegate 原生产缓存
         * @return 故障与效果计数器
         */
        @Bean @Primary
        CacheProbe recoveryCacheProbe(@Qualifier("versionedCache") VersionedCache delegate) { return new CacheProbe(delegate); }
    }

    /** 当前用例的故障事件集合，不模拟事务或持久化。 */
    private static final class BusinessProbe implements OutboxConsumer {
        private final OutboxConsumer delegate;
        private final Set<String> failed = ConcurrentHashMap.newKeySet();
        /**
         * 包装真实应用。
         * @param delegate 生产入口
         */
        private BusinessProbe(OutboxConsumer delegate) { this.delegate = delegate; }
        /** {@inheritDoc} */
        @Override public Outcome consume(Delivery delivery, com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group group) {
            if (failed.contains(delivery.message().eventId())) throw new IllegalStateException("受控事件消费故障");
            return delegate.consume(delivery, group);
        }
    }

    /** 在真实Redis失效前故障，记录实际成功调用；没有内存缓存或假版本规则。 */
    private static final class CacheProbe implements VersionedCache {
        private final VersionedCache delegate;
        private final AtomicBoolean unavailable = new AtomicBoolean();
        private final AtomicInteger effects = new AtomicInteger();
        /**
         * 包装真实缓存。
         * @param delegate 生产门面
         */
        private CacheProbe(VersionedCache delegate) { this.delegate = delegate; }
        /** {@inheritDoc} */
        @Override public Optional<Value> get(String key, Policy policy, Supplier<Optional<Value>> loader) { return delegate.get(key, policy, loader); }
        /** {@inheritDoc} */
        @Override public boolean putIfNewer(String key, Value value, Policy policy) { return delegate.putIfNewer(key, value, policy); }
        /** {@inheritDoc} */
        @Override public void invalidate(String key, long minimumVersion, Policy policy) {
            if (unavailable.get()) throw new IllegalStateException("受控提交后缓存不可用");
            delegate.invalidate(key, minimumVersion, policy); effects.incrementAndGet();
        }
    }
}
