package com.patrick.stratum.starter.integration;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxReconciliationRepository;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group.ERP_AUDIT;
import static com.patrick.stratum.domain.event.OutboxReconciliationRepository.ScanOutcome.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 独占MariaDB上验证真实单项对账、锁顺序和持久游标，不继承共享测试库配置。 */
@Tag("integration")
@ExtendWith({OutboxReconciliationIntegrationTest.FixtureCleanup.class, SpringExtension.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxReconciliationIntegrationTest {
    private static IsolatedMariaDbFixture database;
    private static ConfigurableApplicationContext application;
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final OutboxConsumer consumer;
    private final OutboxReconciliationRepository reconciliation;
    private final OutboxRelayRepository relay;
    private final StringRedisTemplate redis;
    private final List<String> cacheKeys = new ArrayList<>();
    private boolean verified;

    /**
     * 注入独占测试上下文的生产端口与数据库客户端。
     * @param context 本类独享的Spring上下文
     * @param jdbc 独占数据库断言
     * @param unitOfWork 实际事务边界
     * @param outbox 同事务事件追加
     * @param consumer 实际消费凭据入口
     * @param reconciliation 对账存储端口
     * @param relay 发送完成端口
     * @param redis 仅清理本测试随机缓存键
     */
    @Autowired
    OutboxReconciliationIntegrationTest(ApplicationContext context, JdbcTemplate jdbc, UnitOfWork unitOfWork,
            OutboxRepository outbox, OutboxConsumer consumer, OutboxReconciliationRepository reconciliation,
            OutboxRelayRepository relay, StringRedisTemplate redis) {
        application = (ConfigurableApplicationContext) context;
        this.jdbc = jdbc; this.unitOfWork = unitOfWork; this.outbox = outbox; this.consumer = consumer;
        this.reconciliation = reconciliation; this.relay = relay; this.redis = redis;
    }

    /**
     * 为本测试类创建独占临时MariaDB，生命周期失败时由夹具自身兜底清理。
     * @throws Exception 容器或连接创建失败
     */
    @BeforeAll
    static void startDatabase() throws Exception { if (database == null) database = IsolatedMariaDbFixture.start(); }

    /**
     * 仅注册该夹具随机库的连接，不继承共享stratum_it动态属性。
     * @param registry Spring测试属性注册器
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (database == null) database = IsolatedMariaDbFixture.start();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> required("STRATUM_IT_REDIS_PORT"));
        registry.add("spring.data.redis.password", () -> System.getenv().getOrDefault("STRATUM_IT_REDIS_PASSWORD", ""));
    }

    /** 确认当前连接实际落在夹具随机库，避免误扫描其他测试或用户数据。 */
    @BeforeEach
    void verifyDatabase() throws SQLException {
        String expected = database.jdbcUrl().substring(database.jdbcUrl().lastIndexOf('/') + 1);
        assertTrue(expected.matches("stratum_it_[a-f0-9]{32}"));
        try (var connection = jdbc.getDataSource().getConnection()) {
            String actualUrl = connection.getMetaData().getURL();
            int query = actualUrl.indexOf('?');
            String baseUrl = query < 0 ? actualUrl : actualUrl.substring(0, query);
            assertTrue(database.jdbcUrl().equals(baseUrl), "连接目标与本测试夹具不符");
            assertEquals(expected, connection.getCatalog());
        }
        verified = true;
    }

    /** 清除本独占数据库的测试记录并复位游标；不连接或删除其他数据库。 */
    @AfterEach
    void cleanupRecords() {
        if (!verified) return;
        jdbc.update("DELETE FROM outbox_consumption");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM outbox_aggregate_progress");
        jdbc.update("UPDATE outbox_reconciliation_cursor SET consumed_at=NULL, event_id=NULL, consumer_group=NULL, cycle_upper_bound=NULL WHERE cursor_id=1");
        for (var key : cacheKeys) redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
    }

    /** 在Spring测试扩展完成类级上下文清理后，关闭仅由本类创建的数据库容器。 */
    static final class FixtureCleanup implements AfterAllCallback {
        /** {@inheritDoc} */
        @Override
        public void afterAll(ExtensionContext context) {
            try { if (application != null && application.isActive()) application.close(); }
            finally { if (database != null) database.close(); }
        }
    }

    /** 两个夹具的端口、随机库与账号互不相同，交叉凭据无法访问对方。 */
    @Test
    void ownsAnIsolatedDatabaseAndCredentials() throws Exception {
        try (var other = IsolatedMariaDbFixture.start()) {
            assertNotEquals(database.jdbcUrl(), other.jdbcUrl());
            assertNotEquals(database.username(), other.username());
            assertThrows(SQLException.class, () -> {
                try (var ignored = DriverManager.getConnection(database.jdbcUrl(), other.username(), other.password())) { }
            });
            try (var connection = DriverManager.getConnection(other.jdbcUrl(), other.username(), other.password())) {
                assertEquals(other.jdbcUrl().substring(other.jdbcUrl().lastIndexOf('/') + 1), connection.getCatalog());
            }
        }
    }

    /** 过期租约在真实凭据与进度一致时收敛，保留旧状态/失败次数，迟到确认不能覆盖。 */
    @Test
    void reconcilesExpiredLeaseAndFencesLateConfirmation() {
        var event = consumed(); var claim = processing(event, -1, 2);
        assertEquals(RECONCILED, scan());
        var row = eventRow(event);
        assertEquals("CONSUMED", row.status()); assertEquals("PROCESSING", row.origin());
        assertEquals(2, row.retryCount()); assertNull(row.token());
        assertFalse(unitOfWork.execute(() -> relay.finish(claim, OutboxPublisher.PublishResult.CONFIRMED, retryPolicy())));
        assertEquals(CYCLE_COMPLETE, scan());
    }

    /** 有效租约只扫描不修改，随后模拟发布确认仍能写入SENT。 */
    @Test
    void preservesLiveLeaseUntilConfirmation() {
        var event = consumed(); var claim = processing(event, 30, 0);
        assertEquals(SCANNED, scan()); assertEquals("PROCESSING", eventRow(event).status());
        assertTrue(unitOfWork.execute(() -> relay.finish(claim, OutboxPublisher.PublishResult.CONFIRMED, retryPolicy())));
        assertEquals("SENT", eventRow(event).status()); assertNull(eventRow(event).origin());
    }

    /** 已有FAILED来源和累计失败次数在消费凭据支持下保留，不伪装成发布确认。 */
    @Test
    void retainsFailedOriginAndRetryCount() {
        var event = consumed();
        jdbc.update("UPDATE outbox_event SET status='FAILED', consumed_from_status=NULL, retry_count=5 WHERE event_id=?", event.message().eventId());
        assertEquals(RECONCILED, scan());
        var row = eventRow(event);
        assertEquals("CONSUMED", row.status()); assertEquals("FAILED", row.origin()); assertEquals(5, row.retryCount());
    }

    /** 缓存失效真实消费凭据也可对账，按其固定消费组验证。 */
    @Test
    void reconcilesActualCacheInvalidationReceipt() {
        String key = "biz:erp:probe:" + UUID.randomUUID(); cacheKeys.add(key);
        var message = new OutboxMessage(UUID.randomUUID().toString(), "stratum.cache.invalidate", UUID.randomUUID().toString(),
                Instant.parse("2026-10-26T10:00:00.123456Z"),
                "{\"key\":\"" + key + "\",\"minimumVersion\":4,\"ttlMillis\":60000,\"jitterMillis\":0}", "cache-reconcile");
        unitOfWork.execute(() -> { outbox.append(message); return null; });
        var event = new Delivery(message, 1);
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(event, com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group.CACHE_INVALIDATION));
        setPending(event);
        assertEquals(RECONCILED, scan()); assertEquals("CONSUMED", eventRow(event).status());
        assertTrue(redis.getExpire("version:" + key.substring(4), TimeUnit.MILLISECONDS) > 0);
    }

    /** 进度低于消费凭据时拒绝收敛；修复进度后下一轮可重新验证该凭据。 */
    @Test
    void requiresProgressCompatibleWithReceipt() {
        var event = consumed(); setPending(event);
        jdbc.update("UPDATE outbox_aggregate_progress SET consumed_sequence=0 WHERE aggregate_id=?", event.message().aggregateId());
        assertEquals(SCANNED, scan()); assertEquals("PENDING", eventRow(event).status());
        jdbc.update("UPDATE outbox_aggregate_progress SET consumed_sequence=1 WHERE aggregate_id=?", event.message().aggregateId());
        assertEquals(CYCLE_COMPLETE, scan()); assertEquals(RECONCILED, scan());
    }

    /** 原事件行被其他事务持有时跳过并在后续轮次重访。 */
    @Test
    void revisitsBusyEventRow() throws Exception {
        var event = consumed(); setPending(event);
        revisitBusy(event, "SELECT event_id FROM outbox_event WHERE event_id=? FOR UPDATE");
    }

    /** 消费凭据行被持有时跳过并重访，验证关联查询的实际SKIP LOCKED行为。 */
    @Test
    void revisitsBusyReceiptRow() throws Exception {
        var event = consumed(); setPending(event);
        revisitBusy(event, "SELECT event_id FROM outbox_consumption WHERE event_id=? FOR UPDATE");
    }

    /** 多种凭据篡改不能触发状态变更；修复后下一轮能重新扫描。 */
    @Test
    void verifiesFullReceiptBeforeRepair() {
        var events = new ArrayList<Delivery>();
        for (int i = 0; i < 6; i++) events.add(consumed());
        for (var event : events) setPending(event);
        jdbc.update("UPDATE outbox_consumption SET payload='{\"altered\":true}' WHERE event_id=?", events.get(0).message().eventId());
        jdbc.update("UPDATE outbox_consumption SET trace_id=CONCAT(trace_id,' ') WHERE event_id=?", events.get(1).message().eventId());
        jdbc.update("UPDATE outbox_consumption SET event_type='ERP.probe.created' WHERE event_id=?", events.get(2).message().eventId());
        jdbc.update("UPDATE outbox_consumption SET occurred_at=TIMESTAMPADD(MICROSECOND,1,occurred_at) WHERE event_id=?", events.get(3).message().eventId());
        jdbc.update("UPDATE outbox_consumption SET consumer_group='CACHE_INVALIDATION' WHERE event_id=?", events.get(4).message().eventId());
        jdbc.update("UPDATE outbox_consumption SET event_sequence=2 WHERE event_id=?", events.get(5).message().eventId());
        for (int i = 0; i < events.size(); i++) assertEquals(SCANNED, scan());
        assertEquals(CYCLE_COMPLETE, scan());
        for (var event : events) assertEquals("PENDING", eventRow(event).status());
        jdbc.update("UPDATE outbox_consumption SET payload=? WHERE event_id=?", events.getFirst().message().payload(), events.getFirst().message().eventId());
        boolean found = false;
        for (int i = 0; i < events.size(); i++) if (scan() == RECONCILED) found = true;
        assertTrue(found); assertEquals("CONSUMED", eventRow(events.getFirst()).status());
    }

    /** 忙的聚合不阻塞其他凭据，游标先前进并在新轮次重新访问忙行。 */
    @Test
    void skipsBusyAggregateAndRevisitsNextCycle() throws Exception {
        var first = consumed(); var second = consumed(); setPending(first); setPending(second);
        jdbc.update("UPDATE outbox_consumption SET consumed_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)) WHERE event_id=?", first.message().eventId());
        jdbc.update("UPDATE outbox_consumption SET consumed_at=TIMESTAMPADD(SECOND,-9,UTC_TIMESTAMP(6)) WHERE event_id=?", second.message().eventId());
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = workers.submit(() -> unitOfWork.execute(() -> {
                jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id=? FOR UPDATE", Long.class, first.message().aggregateId());
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试锁等待中断"); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                assertEquals(SCANNED, scan()); assertEquals(RECONCILED, scan()); assertEquals(CYCLE_COMPLETE, scan());
                assertEquals("PENDING", eventRow(first).status()); assertEquals("CONSUMED", eventRow(second).status());
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertEquals(RECONCILED, scan()); assertEquals("CONSUMED", eventRow(first).status());
    }

    /** 新提交凭据晚于本轮固定上界时在下一轮处理，游标本身可在事务间持久化。 */
    @Test
    void boundsEachCycleAndResumesFromPersistedCursor() {
        var first = consumed(); setPending(first);
        assertEquals(RECONCILED, scan());
        var second = consumed(); setPending(second);
        jdbc.update("UPDATE outbox_consumption SET consumed_at=(SELECT TIMESTAMPADD(MICROSECOND,1,cycle_upper_bound) FROM outbox_reconciliation_cursor WHERE cursor_id=1) WHERE event_id=?", second.message().eventId());
        assertEquals(CYCLE_COMPLETE, scan()); assertEquals("PENDING", eventRow(second).status());
        await().atMost(Duration.ofSeconds(2)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT consumed_at<=UTC_TIMESTAMP(6) FROM outbox_consumption WHERE event_id=?", Boolean.class, second.message().eventId())));
        assertEquals(SCANNED, scan());
        assertEquals(RECONCILED, scan()); assertEquals("CONSUMED", eventRow(second).status());
    }

    /** 事务回滚同时撤销事件和游标，重试同一凭据可成功；无工作单元拒绝。 */
    @Test
    void rollsBackCursorAndStateTogether() {
        var event = consumed(); setPending(event);
        assertThrows(IllegalStateException.class, reconciliation::scanOne);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertEquals(RECONCILED, reconciliation.scanOne());
            throw new IllegalStateException("模拟对账写入后失败");
        }));
        assertEquals("PENDING", eventRow(event).status());
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM outbox_reconciliation_cursor WHERE cursor_id=1", LocalDateTime.class));
        assertEquals(RECONCILED, scan());
    }

    /** 两个事务争用数据库单例游标时仅一个收敛，令牌行锁而非进程内同步提供资格。 */
    @Test
    void coordinatesConcurrentInstancesWithDatabaseCursor() throws Exception {
        var event = consumed(); setPending(event);
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> { ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS)); return scan(); });
            var second = workers.submit(() -> { ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS)); return scan(); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result == RECONCILED).count());
            assertTrue(results.stream().allMatch(result -> result == RECONCILED || result == BUSY || result == CYCLE_COMPLETE));
        }
        assertEquals("CONSUMED", eventRow(event).status());
    }

    /**
     * 用独立真实事务持有指定行，验证跳过后下一轮恢复。
     * @param event 本测试原事件
     * @param query 由测试代码固定提供的锁定SQL
     * @throws Exception 竞争任务失败或超时
     */
    private void revisitBusy(Delivery event, String query) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = workers.submit(() -> unitOfWork.execute(() -> {
                jdbc.queryForObject(query, String.class, event.message().eventId()); locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试锁等待中断"); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                assertEquals(SCANNED, scan()); assertEquals(CYCLE_COMPLETE, scan());
                assertEquals("PENDING", eventRow(event).status());
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertEquals(RECONCILED, scan()); assertEquals("CONSUMED", eventRow(event).status());
    }

    /**
     * 先用真实业务入口生成消费凭据，再由测试构造旧版本可能留下的待对账状态。
     * @return 本独占数据库的事件及序号
     */
    private Delivery consumed() {
        var message = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":12345678901234567890.123456}", "reconcile-trace");
        unitOfWork.execute(() -> { outbox.append(message); return null; });
        var event = new Delivery(message, 1);
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(event, ERP_AUDIT));
        return event;
    }

    /**
     * 在隔离库中模拟旧实现留下的PENDING行，不改变真实消费凭据。
     * @param event 本测试事件
     */
    private void setPending(Delivery event) {
        jdbc.update("UPDATE outbox_event SET status='PENDING', consumed_from_status=NULL, lease_token=NULL, lease_until=NULL WHERE event_id=?", event.message().eventId());
    }

    /**
     * 在隔离库中模拟旧实现留下的处理租约，返回与行一致的原竞争证据。
     * @param event 本测试事件
     * @param seconds 相对数据库时间的秒数
     * @param failures 已记录失败次数
     * @return 模拟持久领取，不代表实际发布
     */
    private OutboxRelayRepository.Claim processing(Delivery event, int seconds, int failures) {
        var token = UUID.randomUUID();
        jdbc.update("UPDATE outbox_event SET status='PROCESSING', consumed_from_status=NULL, lease_token=?, lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)), retry_count=? WHERE event_id=?",
                token.toString(), seconds, failures, event.message().eventId());
        var expiry = jdbc.queryForObject("SELECT lease_until FROM outbox_event WHERE event_id=?", LocalDateTime.class, event.message().eventId());
        return new OutboxRelayRepository.Claim(event, token, expiry.toInstant(ZoneOffset.UTC), failures);
    }

    /**
     * 通过真实事务边界运行一个对账步骤。
     * @return 持久化后的步骤结果
     */
    private OutboxReconciliationRepository.ScanOutcome scan() { return unitOfWork.execute(reconciliation::scanOne); }

    /**
     * 读取本用例事件状态及竞争令牌。
     * @param event 原事件
     * @return 简化断言快照
     */
    private EventRow eventRow(Delivery event) {
        return jdbc.queryForObject("SELECT status, consumed_from_status, retry_count, lease_token FROM outbox_event WHERE event_id=?",
                (row, number) -> new EventRow(row.getString(1), row.getString(2), row.getInt(3), row.getString(4)), event.message().eventId());
    }

    /**
     * 固定测试退避策略。
     * @return 有界策略
     */
    private OutboxRelayRepository.RetryPolicy retryPolicy() {
        return new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    /**
     * 读取必须显式指定的本机Redis测试端口，不在异常中输出配置值。
     * @param name 环境变量名称
     * @return 非空值
     */
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少集成测试配置：" + name);
        return value;
    }

    /**
     * 只包含本用例断言需要的状态快照。
     * @param status 当前状态
     * @param origin 收敛前状态
     * @param retryCount 已记录失败次数
     * @param token 可空领取令牌
     */
    private record EventRow(String status, String origin, int retryCount, String token) { }
}
