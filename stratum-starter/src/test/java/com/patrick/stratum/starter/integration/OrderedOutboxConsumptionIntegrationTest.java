package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.patrick.stratum.application.event.OutboxConsumerApplicationService;
import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.TransactionalUnitOfWork;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.CacheInvalidation;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxConsumerConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration;
import com.patrick.stratum.infrastructure.outbox.OutboxConsumptionRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxConsumer.Outcome.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 完整Starter与独立消费上下文在真实DB/Redis/Broker核验严格顺序、重投去重及补偿。 */
@Import(OrderedOutboxConsumptionIntegrationTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderedOutboxConsumptionIntegrationTest extends MariaDbIntegrationTest {
    private static final VersionedCache.Policy CACHE_POLICY = new VersionedCache.Policy(Duration.ofMinutes(5), Duration.ZERO);
    @Autowired private ApplicationContext application;
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxRelayApplicationService relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private VersionedCache cache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RabbitAdmin admin;
    @Autowired private Probe primary;
    private AnnotationConfigApplicationContext secondary;
    private final List<String> aggregates = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    /**
     * 只启用受守卫的隔离消息空间和两个固定监听。
     * @param registry 动态配置
     */
    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("stratum.messaging.consumer.enabled", () -> true);
        registry.add("stratum.messaging.topology.retry-min", () -> "100ms");
        registry.add("stratum.messaging.topology.retry-max", () -> "100ms");
    }

    /** 重建自身拓扑并恢复监听，不触碰用户队列；探针状态在用例之间清空。 */
    @BeforeEach
    void prepare() {
        primary.reset(); admin.initialize();
        application.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::start);
    }

    /** 停止两个实例后再删除自身凭据、事件、Redis键和专用拓扑。 */
    @AfterEach
    void cleanup() {
        stopPrimary();
        if (secondary != null) { secondary.close(); secondary = null; }
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
        for (var key : keys) redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
        RabbitIntegrationSupport.clearTopology(admin);
    }

    /** 真实Relay驱动两条连续ERP留痕和缓存补偿，消费进度由应用提交而非SQL探针推进。 */
    @Test
    void completesOrderedAuditAndActualCacheCompensation() {
        var erp = seed("erp.probe.created", "{\"amount\":999999999999999999.123456789012}", 2);
        var key = key(); assertTrue(cache.putIfNewer(key, new VersionedCache.Value(1, "old"), CACHE_POLICY));
        var invalidation = cacheEvent(key, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runBatch(); return receipts(erp.getLast()) == 1 && receipts(invalidation) == 1;
        });
        assertEquals(2L, progress(erp.getFirst())); assertEquals(1L, progress(invalidation));
        for (var event : erp) assertEquals(event.message().payload(), jdbc.queryForObject(
                "SELECT payload FROM outbox_consumption WHERE event_id = ?", String.class, event.message().eventId()));
        assertNull(redis.opsForValue().get(key));
        assertFalse(cache.putIfNewer(key, new VersionedCache.Value(1, "stale"), CACHE_POLICY));
        assertEquals(1, primary.cacheProbe.effects.get());
        assertEquals("SENT", status(erp.getLast()));
    }

    /** 首实例提交后模拟确认前失败，第二实例从真实Broker重投读到重复且不再次失效。 */
    @Test
    void deduplicatesAcrossIndependentTransactionsAfterCommitBeforeAckFailure() throws Exception {
        var key = key(); var event = cacheEvent(key, 2);
        primary.failAfter.set(event.message().eventId()); runBatch();
        assertTrue(primary.afterCommit.await(5, TimeUnit.SECONDS)); stopPrimary();
        assertEquals(1, receipts(event)); assertEquals(1L, progress(event));
        assertTrue(cache.putIfNewer(key, new VersionedCache.Value(3, "newer"), CACHE_POLICY));
        String stamp = redis.opsForValue().get("version:" + key.substring(4));
        startSecondary(); var other = secondary.getBean(Probe.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> other.duplicates.get() == 1);
        secondary.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::stop);
        assertEquals(1, receipts(event)); assertEquals(1, primary.cacheProbe.effects.get()); assertEquals(0, other.cacheProbe.effects.get());
        assertFalse(primary.connections.isEmpty()); assertFalse(other.connections.isEmpty());
        assertTrue(java.util.Collections.disjoint(primary.connections, other.connections));
        assertNotSame(application.getBean(CachingConnectionFactory.class).createConnection().getDelegate(),
                secondary.getBean(CachingConnectionFactory.class).createConnection().getDelegate());
        assertEquals(stamp, redis.opsForValue().get("version:" + key.substring(4)));
        assertEquals("newer", cache.get(key, CACHE_POLICY, Optional::empty).orElseThrow().payload());
    }

    /** 前序真实失败后转为受控等待，后序持续PENDING，其他聚合仍推进；恢复后按序完成。 */
    @Test
    void blocksFailedPredecessorAndAllowsOtherAggregateToContinue() throws Exception {
        var blocked = seed("erp.probe.created", "{}", 2); var other = seed("erp.probe.created", "{}", 1).getFirst();
        primary.failBefore.set(blocked.getFirst().message().eventId()); runBatch();
        assertTrue(primary.beforeFailure.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(5)).until(() -> receipts(other) == 1);
        assertEquals(0L, progress(blocked.getFirst())); assertEquals("PENDING", status(blocked.getLast()));
        assertEquals(0, runBatch()); assertEquals("SENT", status(blocked.getFirst()));
        primary.blocked.set(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> receipts(blocked.getFirst()) == 1);
        runBatch(); await().atMost(Duration.ofSeconds(5)).until(() -> receipts(blocked.getLast()) == 1);
        assertEquals(2L, progress(blocked.getFirst())); assertEquals(1L, progress(other));
    }

    /**
     * 调用有界手动Relay，不启动调度或无限重试。
     * @return 持久化发送结果数
     */
    private int runBatch() {
        return relay.runBatch(new OutboxRelayRepository.ClaimRequest(8, Duration.ofSeconds(30)),
                new OutboxRelayRepository.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    /** 停止当前应用的两个监听，保证夹具清理不与消费竞争。 */
    private void stopPrimary() {
        application.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::stop);
    }

    /** 创建独立数据库连接和事务代理的第二消费实例，缓存仍通过真实共享Redis门面访问。 */
    private void startSecondary() {
        var properties = new HashMap<>(RabbitIntegrationSupport.connectionProperties());
        properties.put("stratum.messaging.consumer.enabled", true);
        properties.put("stratum.messaging.topology.retry-min", "100ms"); properties.put("stratum.messaging.topology.retry-max", "100ms");
        secondary = new AnnotationConfigApplicationContext();
        secondary.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        var source = new DriverManagerDataSource(System.getenv("STRATUM_IT_DB_URL"),
                System.getenv("STRATUM_IT_DB_USERNAME"), System.getenv("STRATUM_IT_DB_PASSWORD"));
        secondary.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(source));
        secondary.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        secondary.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        secondary.register(TransactionConfiguration.class, TransactionalUnitOfWork.class, OutboxConsumptionRepositoryImpl.class);
        secondary.registerBean(Probe.class, () -> new Probe(secondary.getBean(UnitOfWork.class),
                secondary.getBean(OutboxConsumptionRepository.class), cache, secondary.getBean(JsonMapper.class),
                secondary.getBean(JdbcTemplate.class)));
        secondary.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class, RabbitOutboxConsumerConfiguration.class);
        secondary.refresh();
    }

    /**
     * 同事务登记固定精度、随机聚合的原始事件。
     * @param type 主题
     * @param payload JSON原文
     * @param count 数量
     * @return 不可变投递序列
     */
    private List<Delivery> seed(String type, String payload, int count) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate); var events = new ArrayList<Delivery>();
        unitOfWork.execute(() -> {
            for (int index = 1; index <= count; index++) {
                var message = new OutboxMessage(UUID.randomUUID().toString(), type, aggregate,
                        Instant.parse("2026-10-26T10:00:00.123456Z"), payload, "ordered-consumer-it");
                outbox.append(message); events.add(new Delivery(message, index));
            } return null;
        }); return List.copyOf(events);
    }

    /**
     * 生成持久化的合法缓存补偿事件。
     * @param key 自身缓存键
     * @param version 最低版本
     * @return 原事件
     */
    private Delivery cacheEvent(String key, long version) {
        return seed(CacheInvalidation.EVENT_TYPE,
                json.writeValueAsString(new CacheInvalidation(key, version, CACHE_POLICY.ttl().toMillis(), 0)), 1).getFirst();
    }

    /**
     * 记录随机业务缓存键以便清理。
     * @return 隔离键
     */
    private String key() { var key = "biz:erp:ordered:" + UUID.randomUUID(); keys.add(key); return key; }

    /**
     * 独立读取事件消费凭据。
     * @param event 原事件
     * @return 凭据数量
     */
    private int receipts(Delivery event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id = ?", Integer.class, event.message().eventId());
    }

    /**
     * 独立读取真实应用提交的顺序。
     * @param event 所属聚合事件
     * @return 已消费序号
     */
    private long progress(Delivery event) {
        return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                Long.class, event.message().aggregateId());
    }

    /**
     * 独立读取发布状态。
     * @param event 原事件
     * @return 当前状态
     */
    private String status(Delivery event) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, event.message().eventId());
    }

    /** 将可控故障放在真实应用调用前后，不替代事务或Redis行为。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /**
         * 为完整Starter提供包装实际应用的主消费端口。
         * @param unitOfWork 真实事务代理
         * @param repository 原消费存储
         * @param cache 真实缓存
         * @param json JSON映射器
         * @param jdbc 事务内连接探针
         * @return 主实例消费探针
         */
        @Bean @Primary
        Probe primaryConsumerProbe(UnitOfWork unitOfWork, OutboxConsumptionRepository repository,
                VersionedCache cache, JsonMapper json, JdbcTemplate jdbc) {
            return new Probe(unitOfWork, repository, cache, json, jdbc);
        }
    }

    /** 为第二上下文开启同一Application工作单元的真实事务代理。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration { }

    /** 真实应用入口周围的有限故障探针，进程级状态只用于测试控制。 */
    private static final class Probe implements OutboxConsumer {
        private final OutboxConsumer delegate;
        private final CacheProbe cacheProbe;
        private final Set<Long> connections = ConcurrentHashMap.newKeySet();
        private final AtomicInteger duplicates = new AtomicInteger();
        private final AtomicReference<String> failAfter = new AtomicReference<>();
        private final AtomicReference<String> failBefore = new AtomicReference<>();
        private final AtomicReference<String> blocked = new AtomicReference<>();
        private CountDownLatch afterCommit = new CountDownLatch(1);
        private CountDownLatch beforeFailure = new CountDownLatch(1);

        /**
         * 组合生产应用与可见真实连接的存储包装。
         * @param unitOfWork 实际应用事务代理
         * @param repository 原存储
         * @param cache 真实Redis门面
         * @param json JSON映射器
         * @param jdbc 事务内连接探针
         */
        private Probe(UnitOfWork unitOfWork, OutboxConsumptionRepository repository, VersionedCache cache, JsonMapper json, JdbcTemplate jdbc) {
            cacheProbe = new CacheProbe(cache);
            OutboxConsumptionRepository tracked = new OutboxConsumptionRepository() {
                /** {@inheritDoc} */
                @Override public Result begin(Delivery delivery, Group group) {
                    connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); return repository.begin(delivery, group);
                }
                /** {@inheritDoc} */
                @Override public void complete(Delivery delivery, Group group) { repository.complete(delivery, group); }
            };
            delegate = new OutboxConsumerApplicationService(unitOfWork, tracked, cacheProbe, json);
        }

        /** 清除上个用例的有限探针状态。 */
        private void reset() {
            failAfter.set(null); failBefore.set(null); blocked.set(null); duplicates.set(0); connections.clear();
            cacheProbe.effects.set(0); afterCommit = new CountDownLatch(1); beforeFailure = new CountDownLatch(1);
        }

        /** {@inheritDoc} */
        @Override public Outcome consume(Delivery delivery, OutboxConsumptionRepository.Group group) {
            String id = delivery.message().eventId();
            String before = failBefore.get();
            if (id.equals(before) && failBefore.compareAndSet(before, null)) {
                blocked.set(id); beforeFailure.countDown(); throw new IllegalStateException("受控消费前失败");
            }
            if (id.equals(blocked.get())) return WAITING;
            var outcome = delegate.consume(delivery, group);
            if (outcome == DUPLICATE) duplicates.incrementAndGet();
            String after = failAfter.get();
            if (outcome == CONSUMED && id.equals(after) && failAfter.compareAndSet(after, null)) {
                blocked.set(id); afterCommit.countDown(); throw new IllegalStateException("模拟提交后确认前失败");
            }
            return outcome;
        }
    }

    /** 仅统计真实缓存失效调用，不替代版本屏障。 */
    private static final class CacheProbe implements VersionedCache {
        private final VersionedCache delegate;
        private final AtomicInteger effects = new AtomicInteger();
        /**
         * 包装实际缓存。
         * @param delegate 原门面
         */
        private CacheProbe(VersionedCache delegate) { this.delegate = delegate; }
        /** {@inheritDoc} */
        @Override public Optional<Value> get(String key, Policy policy, Supplier<Optional<Value>> loader) { return delegate.get(key, policy, loader); }
        /** {@inheritDoc} */
        @Override public boolean putIfNewer(String key, Value value, Policy policy) { return delegate.putIfNewer(key, value, policy); }
        /** {@inheritDoc} */
        @Override public void invalidate(String key, long minimumVersion, Policy policy) {
            effects.incrementAndGet(); delegate.invalidate(key, minimumVersion, policy);
        }
    }
}
