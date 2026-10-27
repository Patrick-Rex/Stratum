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
import com.patrick.stratum.application.event.OutboxReconciliationApplicationService;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxOriginalRecoveryRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final OutboxReconciliationApplicationService reconciliationService;
    private final OutboxOriginalRecoveryRepository originalRecovery;
    private final OutboxRelayRepository relay;
    private final StringRedisTemplate redis;
    private final List<String> cacheKeys = new ArrayList<>();
    private final List<UUID> recoveryRequests = new ArrayList<>();
    private final List<UUID> replayArchives = new ArrayList<>();
    private boolean verified;

    /**
     * 注入独占测试上下文的生产端口与数据库客户端。
     * @param context 本类独享的Spring上下文
     * @param jdbc 独占数据库断言
     * @param unitOfWork 实际事务边界
     * @param outbox 同事务事件追加
     * @param consumer 实际消费凭据入口
     * @param reconciliation 对账存储端口
     * @param reconciliationService 事务外有界批次入口
     * @param originalRecovery 原事件恢复端口
     * @param relay 发送完成端口
     * @param redis 仅清理本测试随机缓存键
     */
    @Autowired
    OutboxReconciliationIntegrationTest(ApplicationContext context, JdbcTemplate jdbc, UnitOfWork unitOfWork,
            OutboxRepository outbox, OutboxConsumer consumer, OutboxReconciliationRepository reconciliation,
            OutboxRelayRepository relay, StringRedisTemplate redis, OutboxReconciliationApplicationService reconciliationService,
            OutboxOriginalRecoveryRepository originalRecovery) {
        application = (ConfigurableApplicationContext) context;
        this.jdbc = jdbc; this.unitOfWork = unitOfWork; this.outbox = outbox; this.consumer = consumer;
        this.reconciliation = reconciliation; this.relay = relay; this.redis = redis;
        this.reconciliationService = reconciliationService;
        this.originalRecovery = originalRecovery;
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
        for (var id : recoveryRequests) jdbc.update("DELETE FROM outbox_original_recovery_request WHERE request_id=?", id.toString());
        for (var id : replayArchives) jdbc.update("DELETE FROM outbox_replay_delivery WHERE dead_id=?", id.toString());
        for (var id : replayArchives) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id=?", id.toString());
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

    /** 独占库验证恢复回执迁移、请求幂等和约束，写入后按本测试请求ID清理。 */
    @Test
    void validatesOriginalRecoveryReceiptSchemaAndIdempotency() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='202610270004' AND success=1", Integer.class));
        assertEquals(List.of("event_id", "created_at", "request_id"), jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
                    AND TABLE_NAME='outbox_original_recovery_request' AND INDEX_NAME='idx_original_recovery_event'
                ORDER BY SEQ_IN_INDEX
                """, String.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()
                    AND TABLE_NAME='outbox_original_recovery_request' AND REFERENCED_TABLE_NAME IS NOT NULL
                """, Integer.class));

        String first = UUID.randomUUID().toString(); String second = UUID.randomUUID().toString();
        String rolledBack = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString(); String operator = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        String insert = """
                INSERT INTO outbox_original_recovery_request
                    (request_id,event_id,operator_id,registration_token,outcome,previous_failures,trace_id)
                VALUES (?,?,?,?,?,?,?)
                """;
        try {
            var before = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
            assertEquals(1, jdbc.update(insert, first, eventId, operator, token, "RECOVERED", 7, "recovery-trace"));
            var after = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
            var created = jdbc.queryForObject("SELECT created_at FROM outbox_original_recovery_request WHERE request_id=?", LocalDateTime.class, first);
            assertFalse(created.isBefore(before)); assertFalse(created.isAfter(after));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, first, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), "REGISTERING", null, "other-trace"));
            assertEquals(eventId, jdbc.queryForObject("SELECT event_id FROM outbox_original_recovery_request WHERE request_id=?", String.class, first));
            assertEquals(operator, jdbc.queryForObject("SELECT operator_id FROM outbox_original_recovery_request WHERE request_id=?", String.class, first));
            assertEquals(1, jdbc.update(insert, second, eventId, operator, UUID.randomUUID().toString(), "ALREADY_CONSUMED", null, "other-trace"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_original_recovery_request WHERE event_id=?", Integer.class, eventId));
            assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
                jdbc.update(insert, rolledBack, eventId, operator, UUID.randomUUID().toString(), "REGISTERING", null, "rollback-trace");
                throw new IllegalStateException("模拟工作单元回滚");
            }));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_original_recovery_request WHERE request_id=?", Integer.class, rolledBack));

            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET outcome='UNKNOWN' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET outcome='RECOVERED ' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET previous_failures=NULL WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET previous_failures=-1 WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET previous_failures=1 WHERE request_id=?", second));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET request_id='invalid' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET event_id='invalid' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET operator_id='invalid' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET registration_token='invalid' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET trace_id='bad trace' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET trace_id='recovery-trace ' WHERE request_id=?", first));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE outbox_original_recovery_request SET trace_id=? WHERE request_id=?", "valid\n", first));
            assertEquals("RECOVERED", jdbc.queryForObject("SELECT outcome FROM outbox_original_recovery_request WHERE request_id=?", String.class, first));
            assertEquals(7, jdbc.queryForObject("SELECT previous_failures FROM outbox_original_recovery_request WHERE request_id=?", Integer.class, first));
        } finally {
            for (String id : List.of(first, second, rolledBack)) {
                jdbc.update("DELETE FROM outbox_original_recovery_request WHERE request_id=?", id);
            }
        }
    }

    /** 失败头恢复保留原信封和累计次数；重复请求返回首次回执，异身份不可复用请求。 */
    @Test
    void recoversOriginalHeadAndReplaysOnlyTheSameRequestIdentity() {
        var event = unpublishedEvents(1).getFirst();
        markFailed(event, 7);
        var request = recoveryRequest(event);
        var receipt = unitOfWork.execute(() -> originalRecovery.recover(request));
        assertEquals(OutboxOriginalRecoveryRepository.Outcome.RECOVERED, receipt.outcome());
        assertEquals(7, receipt.previousFailures().orElseThrow());
        assertEquals(request.requestId(), receipt.requestId()); assertEquals(request.operatorId(), receipt.operatorId());
        assertEquals("PENDING", eventRow(event).status()); assertEquals(7, eventRow(event).retryCount());
        var original = jdbc.queryForMap("SELECT event_id,event_type,aggregate_id,event_sequence,payload,trace_id FROM outbox_event WHERE event_id=?", event.message().eventId());
        assertEquals(event.message().eventId(), original.get("event_id")); assertEquals(event.message().eventType(), original.get("event_type"));
        assertEquals(event.message().aggregateId(), original.get("aggregate_id")); assertEquals(event.sequence(), ((Number) original.get("event_sequence")).longValue());
        assertEquals(event.message().payload(), original.get("payload")); assertEquals(event.message().traceId(), original.get("trace_id"));
        var repeated = new OutboxOriginalRecoveryRepository.Request(request.requestId(), request.eventId(), request.operatorId(), "later-trace");
        assertEquals(receipt, unitOfWork.execute(() -> originalRecovery.recover(repeated)));
        var otherEvent = unpublishedEvents(1).getFirst();
        var changedEvent = new OutboxOriginalRecoveryRepository.Request(request.requestId(), UUID.fromString(otherEvent.message().eventId()), request.operatorId(), "other-trace");
        var changedActor = new OutboxOriginalRecoveryRepository.Request(request.requestId(), request.eventId(), UUID.randomUUID(), "other-trace");
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(changedEvent)));
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(changedActor)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_original_recovery_request WHERE request_id=?", Integer.class, request.requestId().toString()));
    }

    /** 非聚合头、待发布或已发送但无消费证据不能恢复，也不留下登记回执。 */
    @Test
    void refusesNonHeadAndIneligibleOriginalStates() {
        var events = unpublishedEvents(2); markFailed(events.get(1), 2);
        var nonHead = recoveryRequest(events.get(1));
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(nonHead)));
        var pending = recoveryRequest(events.getFirst());
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(pending)));
        jdbc.update("UPDATE outbox_event SET status='SENT' WHERE event_id=?", events.getFirst().message().eventId());
        var sent = recoveryRequest(events.getFirst());
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(sent)));
        jdbc.update("UPDATE outbox_event SET status='CONSUMED',consumed_from_status='PENDING' WHERE event_id=?", events.getFirst().message().eventId());
        var unsupportedConsumed = recoveryRequest(events.getFirst());
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(unsupportedConsumed)));
        for (var request : List.of(nonHead, pending, sent, unsupportedConsumed)) assertEquals(0, recoveryCount(request));
        assertEquals("FAILED", eventRow(events.get(1)).status());
    }

    /** 回执和恢复状态随外层写工作单元一起回滚，随后同一请求可以重新执行。 */
    @Test
    void rollsBackOriginalRecoveryReceiptAndStateTogether() {
        var event = unpublishedEvents(1).getFirst(); markFailed(event, 4);
        var request = recoveryRequest(event);
        assertThrows(IllegalStateException.class, () -> originalRecovery.recover(request));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertEquals(OutboxOriginalRecoveryRepository.Outcome.RECOVERED, originalRecovery.recover(request).outcome());
            throw new IllegalStateException("模拟恢复后回滚");
        }));
        assertEquals("FAILED", eventRow(event).status()); assertEquals(4, eventRow(event).retryCount());
        assertEquals(0, recoveryCount(request));
        assertEquals(OutboxOriginalRecoveryRepository.Outcome.RECOVERED, unitOfWork.execute(() -> originalRecovery.recover(request)).outcome());
    }

    /** 已消费凭据与原始信封匹配才返回已消费，损坏凭据不得冒充成功。 */
    @Test
    void verifiesConsumptionEvidenceBeforeReportingAlreadyConsumed() {
        var event = consumed();
        var request = recoveryRequest(event);
        var result = unitOfWork.execute(() -> originalRecovery.recover(request));
        assertEquals(OutboxOriginalRecoveryRepository.Outcome.ALREADY_CONSUMED, result.outcome());
        assertTrue(result.previousFailures().isEmpty()); assertEquals("CONSUMED", eventRow(event).status());
        assertEquals(result, unitOfWork.execute(() -> originalRecovery.recover(request)));

        var corrupt = consumed();
        jdbc.update("UPDATE outbox_consumption SET payload='{\"tampered\":true}' WHERE event_id=?", corrupt.message().eventId());
        var invalid = recoveryRequest(corrupt);
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(invalid)));
        assertEquals(0, recoveryCount(invalid));
    }

    /** 凭据各字段或消费进度与原事件不一致时不登记已消费回执。 */
    @Test
    void refusesEveryMismatchedConsumptionEvidenceFieldAndLaggingProgress() {
        var changes = List.of(
                "UPDATE outbox_consumption SET aggregate_id=CONCAT(aggregate_id,'x') WHERE event_id=?",
                "UPDATE outbox_consumption SET event_sequence=2 WHERE event_id=?",
                "UPDATE outbox_consumption SET event_type='ERP.probe.created' WHERE event_id=?",
                "UPDATE outbox_consumption SET occurred_at=TIMESTAMPADD(MICROSECOND,1,occurred_at) WHERE event_id=?",
                "UPDATE outbox_consumption SET payload='{\"tampered\":true}' WHERE event_id=?",
                "UPDATE outbox_consumption SET trace_id=CONCAT(trace_id,' ') WHERE event_id=?",
                "UPDATE outbox_consumption SET consumer_group='CACHE_INVALIDATION' WHERE event_id=?");
        for (String change : changes) {
            var event = consumed();
            assertEquals(1, jdbc.update(change, event.message().eventId()));
            var request = recoveryRequest(event);
            assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(request)));
            assertEquals(0, recoveryCount(request)); assertEquals("CONSUMED", eventRow(event).status());
        }
        var lagging = consumed();
        jdbc.update("UPDATE outbox_aggregate_progress SET consumed_sequence=0 WHERE aggregate_id=?", lagging.message().aggregateId());
        var request = recoveryRequest(lagging);
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(request)));
        assertEquals(0, recoveryCount(request)); assertEquals("CONSUMED", eventRow(lagging).status());
    }

    /** 活动重放意图存在时不能把失败原事件重新置为待发布，避免两条并发发送路径。 */
    @Test
    void refusesOriginalRecoveryWhileReplayIntentIsActive() {
        var event = unpublishedEvents(1).getFirst(); markFailed(event, 3);
        UUID archive = insertReplayArchive(event); replayArchives.add(archive);
        String intent = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO outbox_replay_delivery (delivery_id,dead_id,event_id,aggregate_id,event_sequence,consumer_group,status)
                VALUES (?,?,?,?,?,'ERP_AUDIT','PENDING')
                """, intent, archive.toString(), event.message().eventId(), event.message().aggregateId(), event.sequence());
        for (String status : List.of("PENDING", "PROCESSING", "SENT")) {
            if (status.equals("PROCESSING")) jdbc.update("""
                    UPDATE outbox_replay_delivery SET status='PROCESSING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6))
                    WHERE delivery_id=?
                    """, UUID.randomUUID().toString(), intent);
            if (status.equals("SENT")) jdbc.update("UPDATE outbox_replay_delivery SET status='SENT',lease_token=NULL,lease_until=NULL WHERE delivery_id=?", intent);
            var request = recoveryRequest(event);
            assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(request)));
            assertEquals(0, recoveryCount(request)); assertEquals("FAILED", eventRow(event).status());
        }
    }

    /**
     * 活动重放行被另一事务持有时立即拒绝恢复，不能把锁忙当成不存在重放。
     * @throws Exception 并发任务失败或协调超时
     */
    @Test
    void refusesRecoveryWhenActiveReplayRowIsLocked() throws Exception {
        var event = unpublishedEvents(1).getFirst(); markFailed(event, 3);
        UUID archive = insertReplayArchive(event); replayArchives.add(archive);
        String intent = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO outbox_replay_delivery (delivery_id,dead_id,event_id,aggregate_id,event_sequence,consumer_group,status)
                VALUES (?,?,?,?,?,'ERP_AUDIT','PENDING')
                """, intent, archive.toString(), event.message().eventId(), event.message().aggregateId(), event.sequence());
        var request = recoveryRequest(event); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = workers.submit(() -> unitOfWork.execute(() -> {
                jdbc.queryForObject("SELECT delivery_id FROM outbox_replay_delivery WHERE delivery_id=? FOR UPDATE", String.class, intent);
                held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试锁等待中断"); }
                return null;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var competitor = workers.submit(() -> unitOfWork.execute(() -> originalRecovery.recover(request)));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> competitor.get(5, TimeUnit.SECONDS));
                assertInstanceOf(StratumException.class, failure.getCause());
            } finally { release.countDown(); }
            owner.get(5, TimeUnit.SECONDS);
        }
        assertEquals("FAILED", eventRow(event).status()); assertEquals(0, recoveryCount(request));
    }

    /** 恢复后的版本到达上限时饱和，失败计数和原事件身份仍保留。 */
    @Test
    void saturatesOriginalVersionOnRecovery() {
        var event = unpublishedEvents(1).getFirst(); markFailed(event, 8);
        jdbc.update("UPDATE outbox_event SET version=? WHERE event_id=?", Long.MAX_VALUE, event.message().eventId());
        var request = recoveryRequest(event);
        var receipt = unitOfWork.execute(() -> originalRecovery.recover(request));
        assertEquals(OutboxOriginalRecoveryRepository.Outcome.RECOVERED, receipt.outcome());
        assertEquals(8, receipt.previousFailures().orElseThrow()); assertEquals(8, eventRow(event).retryCount());
        assertEquals("PENDING", eventRow(event).status());
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT version FROM outbox_event WHERE event_id=?", Long.class, event.message().eventId()));
    }

    /**
     * 同请求并发时后者等待首次事务提交，并复用首次回执；事件删除后回执仍可重放。
     * @throws Exception 并发任务失败或协调超时
     */
    @Test
    void replaysSameRequestAfterConcurrentCommitAndOriginalDeletion() throws Exception {
        var event = unpublishedEvents(1).getFirst(); markFailed(event, 3);
        var request = recoveryRequest(event); var held = new CountDownLatch(1);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        OutboxOriginalRecoveryRepository.Receipt receipt;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = workers.submit(() -> unitOfWork.execute(() -> {
                var result = originalRecovery.recover(request); held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试锁等待中断"); }
                return result;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var competitor = workers.submit(() -> {
                    started.countDown();
                    return unitOfWork.execute(() -> originalRecovery.recover(request));
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> competitor.get(300, TimeUnit.MILLISECONDS));
                release.countDown();
                receipt = owner.get(5, TimeUnit.SECONDS);
                assertEquals(receipt, competitor.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertEquals(1, recoveryCount(request));
        assertEquals(1, jdbc.update("DELETE FROM outbox_event WHERE event_id=?", event.message().eventId()));
        assertEquals(receipt, unitOfWork.execute(() -> originalRecovery.recover(request)));
        assertEquals(1, recoveryCount(request));
    }

    /**
     * 并发不同请求只允许一条恢复，持锁事务释放后失败状态不会被重复恢复。
     * @throws Exception 并发任务失败或协调超时
     */
    @Test
    void coordinatesConcurrentRecoveryTransactions() throws Exception {
        var event = unpublishedEvents(1).getFirst(); markFailed(event, 5);
        var first = recoveryRequest(event); var other = recoveryRequest(event);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = workers.submit(() -> unitOfWork.execute(() -> {
                var receipt = originalRecovery.recover(first); held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试锁等待中断"); }
                return receipt;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var competitor = workers.submit(() -> unitOfWork.execute(() -> originalRecovery.recover(other)));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> competitor.get(5, TimeUnit.SECONDS));
                assertInstanceOf(StratumException.class, failure.getCause());
            } finally { release.countDown(); }
            assertEquals(OutboxOriginalRecoveryRepository.Outcome.RECOVERED, owner.get(5, TimeUnit.SECONDS).outcome());
        }
        assertEquals("PENDING", eventRow(event).status()); assertEquals(1, recoveryCount(first)); assertEquals(0, recoveryCount(other));
        assertThrows(StratumException.class, () -> unitOfWork.execute(() -> originalRecovery.recover(other)));
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

    /** 批次按扫描数而非修复数限额，错误凭据修复后由下一轮重访。 */
    @Test
    void batchLimitsScannedReceiptsAndRevisitsInvalidEvidence() {
        var invalid = consumed(); var valid = consumed();
        setPending(invalid); setPending(valid);
        jdbc.update("UPDATE outbox_consumption SET consumed_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)), payload='{\"altered\":true}' WHERE event_id=?", invalid.message().eventId());
        jdbc.update("UPDATE outbox_consumption SET consumed_at=TIMESTAMPADD(SECOND,-9,UTC_TIMESTAMP(6)) WHERE event_id=?", valid.message().eventId());

        var first = reconciliationService.runBatch(1);
        assertEquals(1, first.scanned()); assertEquals(0, first.reconciled()); assertEquals("LIMIT", first.termination().name());
        assertEquals(invalid.message().eventId(), jdbc.queryForObject("SELECT event_id FROM outbox_reconciliation_cursor WHERE cursor_id=1", String.class));
        assertEquals("PENDING", eventRow(invalid).status()); assertEquals("PENDING", eventRow(valid).status());

        var second = reconciliationService.runBatch(1);
        assertEquals(1, second.scanned()); assertEquals(1, second.reconciled()); assertEquals("LIMIT", second.termination().name());
        assertEquals("CONSUMED", eventRow(valid).status());
        var end = reconciliationService.runBatch(2);
        assertEquals(0, end.scanned()); assertEquals(0, end.reconciled()); assertEquals("CYCLE_COMPLETE", end.termination().name());

        jdbc.update("UPDATE outbox_consumption SET payload=? WHERE event_id=?", invalid.message().payload(), invalid.message().eventId());
        var revisit = reconciliationService.runBatch(3);
        assertEquals(2, revisit.scanned()); assertEquals(1, revisit.reconciled()); assertEquals("CYCLE_COMPLETE", revisit.termination().name());
        assertEquals("CONSUMED", eventRow(invalid).status());
        assertNull(jdbc.queryForObject("SELECT event_id FROM outbox_reconciliation_cursor WHERE cursor_id=1", String.class));
    }

    /**
     * 批次遇到忙聚合时继续其他项，并在下轮处理释放后的凭据。
     * @throws Exception 并发测试失败或等待超时
     */
    @Test
    void batchRevisitsBusyAggregateAfterCycle() throws Exception {
        var busy = consumed(); var valid = consumed();
        setPending(busy); setPending(valid);
        jdbc.update("UPDATE outbox_consumption SET consumed_at=TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(6)) WHERE event_id=?", busy.message().eventId());
        jdbc.update("UPDATE outbox_consumption SET consumed_at=TIMESTAMPADD(SECOND,-9,UTC_TIMESTAMP(6)) WHERE event_id=?", valid.message().eventId());
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = workers.submit(() -> unitOfWork.execute(() -> {
                jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id=? FOR UPDATE", Long.class, busy.message().aggregateId());
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试锁等待中断"); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var batch = reconciliationService.runBatch(3);
                assertEquals(2, batch.scanned()); assertEquals(1, batch.reconciled()); assertEquals("CYCLE_COMPLETE", batch.termination().name());
                assertEquals("PENDING", eventRow(busy).status()); assertEquals("CONSUMED", eventRow(valid).status());
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        var revisit = reconciliationService.runBatch(3);
        assertEquals(2, revisit.scanned()); assertEquals(1, revisit.reconciled()); assertEquals("CYCLE_COMPLETE", revisit.termination().name());
        assertEquals("CONSUMED", eventRow(busy).status());
    }

    /** 批次拒绝外层事务，事务外调用后游标和事件状态真实提交。 */
    @Test
    void batchRequiresOutsideTransactionAndPersistsChanges() {
        var event = consumed(); setPending(event);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> reconciliationService.runBatch(1)));
        assertEquals("PENDING", eventRow(event).status());
        assertNull(jdbc.queryForObject("SELECT event_id FROM outbox_reconciliation_cursor WHERE cursor_id=1", String.class));
        var batch = reconciliationService.runBatch(1);
        assertEquals(1, batch.scanned()); assertEquals(1, batch.reconciled()); assertEquals("LIMIT", batch.termination().name());
        assertEquals("CONSUMED", eventRow(event).status());
        assertEquals(event.message().eventId(), jdbc.queryForObject("SELECT event_id FROM outbox_reconciliation_cursor WHERE cursor_id=1", String.class));
    }

    /** 临时故障超过失败阈值后仍按数据库时间封顶退避，原身份和聚合顺序保持不变。 */
    @Test
    void retriesTransientPublicationFailuresBeyondLimitWithCappedDelay() {
        var events = unpublishedEvents(2);
        var policy = new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2));
        var request = new OutboxRelayRepository.ClaimRequest(1, Duration.ofSeconds(30));
        var outcomes = List.of(OutboxPublisher.PublishResult.TIMED_OUT, OutboxPublisher.PublishResult.UNAVAILABLE,
                OutboxPublisher.PublishResult.REJECTED);
        for (int i = 0; i < outcomes.size(); i++) {
            var claim = unitOfWork.execute(() -> relay.claim(request)).getFirst();
            assertEquals(events.getFirst(), claim.delivery());
            assertEquals(i, claim.retryCount());
            var before = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
            assertTrue(unitOfWork.execute(() -> relay.finish(claim, outcomes.get(claim.retryCount()), policy)));
            var after = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
            var retryAt = jdbc.queryForObject("SELECT next_retry_at FROM outbox_event WHERE event_id=?", LocalDateTime.class, events.getFirst().message().eventId());
            long delay = i == 0 ? 1 : 2;
            assertFalse(retryAt.isBefore(before.plusSeconds(delay)));
            assertFalse(retryAt.isAfter(after.plusSeconds(delay)));
            assertEquals("PENDING", eventRow(events.getFirst()).status());
            assertEquals(i + 1, eventRow(events.getFirst()).retryCount());
            assertEquals("PENDING", eventRow(events.get(1)).status());
            jdbc.update("UPDATE outbox_event SET next_retry_at='2100-01-01 00:00:00' WHERE event_id=?", events.getFirst().message().eventId());
            assertTrue(unitOfWork.execute(() -> relay.claim(request)).isEmpty());
            jdbc.update("UPDATE outbox_event SET next_retry_at='2000-01-01 00:00:00' WHERE event_id=?", events.getFirst().message().eventId());
        }
        var retry = unitOfWork.execute(() -> relay.claim(request)).getFirst();
        assertEquals(events.getFirst(), retry.delivery());
        assertTrue(unitOfWork.execute(() -> relay.finish(retry, OutboxPublisher.PublishResult.CONFIRMED, policy)));
        assertEquals("SENT", eventRow(events.getFirst()).status());
        assertTrue(unitOfWork.execute(() -> relay.claim(request)).isEmpty());
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(events.getFirst(), ERP_AUDIT));
        assertEquals(events.get(1), unitOfWork.execute(() -> relay.claim(request)).getFirst().delivery());
    }

    /** 不可路由或非法信封达到阈值后保留失败头，另一聚合仍可领取。 */
    @Test
    void stopsDefinitivePublicationFailuresAtThresholdWithoutSkippingHead() {
        var policy = new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2));
        var request = new OutboxRelayRepository.ClaimRequest(1, Duration.ofSeconds(30));
        for (var result : List.of(OutboxPublisher.PublishResult.RETURNED, OutboxPublisher.PublishResult.INVALID_ENVELOPE)) {
            var events = unpublishedEvents(2);
            var first = unitOfWork.execute(() -> relay.claim(request)).getFirst();
            assertEquals(events.getFirst(), first.delivery());
            assertTrue(unitOfWork.execute(() -> relay.finish(first, result, policy)));
            assertEquals("PENDING", eventRow(events.getFirst()).status());
            jdbc.update("UPDATE outbox_event SET next_retry_at='2000-01-01 00:00:00' WHERE event_id=?", events.getFirst().message().eventId());
            var second = unitOfWork.execute(() -> relay.claim(request)).getFirst();
            assertEquals(events.getFirst(), second.delivery());
            assertTrue(unitOfWork.execute(() -> relay.finish(second, result, policy)));
            assertEquals("FAILED", eventRow(events.getFirst()).status());
            assertEquals(2, eventRow(events.getFirst()).retryCount());
            assertEquals("PENDING", eventRow(events.get(1)).status());
            assertTrue(unitOfWork.execute(() -> relay.claim(request)).isEmpty());
        }
        var other = unpublishedEvents(1).getFirst();
        assertEquals(other, unitOfWork.execute(() -> relay.claim(request)).getFirst().delivery());
    }

    /** 暂时故障也计入累计失败次数，后续明确故障按累计阈值转入FAILED。 */
    @Test
    void usesCumulativeFailureCountWhenDefinitiveFailureFollowsTransientFailures() {
        var event = unpublishedEvents(1).getFirst();
        var policy = new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2));
        var request = new OutboxRelayRepository.ClaimRequest(1, Duration.ofSeconds(30));
        for (var result : List.of(OutboxPublisher.PublishResult.UNAVAILABLE, OutboxPublisher.PublishResult.TIMED_OUT)) {
            var claim = unitOfWork.execute(() -> relay.claim(request)).getFirst();
            assertTrue(unitOfWork.execute(() -> relay.finish(claim, result, policy)));
            assertEquals("PENDING", eventRow(event).status());
            jdbc.update("UPDATE outbox_event SET next_retry_at='2000-01-01 00:00:00' WHERE event_id=?", event.message().eventId());
        }
        assertEquals(2, eventRow(event).retryCount());
        var claim = unitOfWork.execute(() -> relay.claim(request)).getFirst();
        assertEquals(event, claim.delivery());
        assertTrue(unitOfWork.execute(() -> relay.finish(claim, OutboxPublisher.PublishResult.RETURNED, policy)));
        assertEquals("FAILED", eventRow(event).status());
        assertEquals(3, eventRow(event).retryCount());
        assertTrue(unitOfWork.execute(() -> relay.claim(request)).isEmpty());
    }

    /** 失败次数及版本达到存储上限时仍能接管和封顶重试，过期令牌不可覆盖新领取。 */
    @Test
    void saturatesCountersAndFencesReplacedLeaseForTransientFailure() {
        var event = unpublishedEvents(1).getFirst();
        var request = new OutboxRelayRepository.ClaimRequest(1, Duration.ofSeconds(30));
        jdbc.update("UPDATE outbox_event SET retry_count=?, version=? WHERE event_id=?", Integer.MAX_VALUE, Long.MAX_VALUE, event.message().eventId());
        var old = unitOfWork.execute(() -> relay.claim(request)).getFirst();
        jdbc.update("UPDATE outbox_event SET lease_until='2000-01-01 00:00:00' WHERE event_id=?", event.message().eventId());
        var current = unitOfWork.execute(() -> relay.claim(request)).getFirst();
        assertNotEquals(old.token(), current.token());
        assertFalse(unitOfWork.execute(() -> relay.finish(old, OutboxPublisher.PublishResult.CONFIRMED, retryPolicy())));
        assertTrue(unitOfWork.execute(() -> relay.finish(current, OutboxPublisher.PublishResult.UNAVAILABLE, retryPolicy())));
        assertEquals("PENDING", eventRow(event).status());
        assertEquals(Integer.MAX_VALUE, eventRow(event).retryCount());
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT version FROM outbox_event WHERE event_id=?", Long.class, event.message().eventId()));
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
     * 创建本测试的原事件恢复请求并登记清理身份。
     * @param event 原事件
     * @return 随机请求与服务端操作者
     */
    private OutboxOriginalRecoveryRepository.Request recoveryRequest(Delivery event) {
        UUID id = UUID.randomUUID(); recoveryRequests.add(id);
        return new OutboxOriginalRecoveryRepository.Request(id, UUID.fromString(event.message().eventId()), UUID.randomUUID(), "recovery-test");
    }

    /**
     * 查询本测试请求是否持久化。
     * @param request 原请求
     * @return 回执行数
     */
    private int recoveryCount(OutboxOriginalRecoveryRepository.Request request) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_original_recovery_request WHERE request_id=?", Integer.class, request.requestId().toString());
    }

    /**
     * 在独占库中构造符合已有约束的失败原事件。
     * @param event 测试事件
     * @param failures 累计失败次数
     */
    private void markFailed(Delivery event, int failures) {
        jdbc.update("UPDATE outbox_event SET status='FAILED',retry_count=? WHERE event_id=?", failures, event.message().eventId());
    }

    /**
     * 创建本测试用归档行，支持构造活动重放意图。
     * @param event 本独占库事件
     * @return 归档身份
     */
    private UUID insertReplayArchive(Delivery event) {
        UUID archive = UUID.randomUUID();
        String fingerprint = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO outbox_dead_letter (dead_id,consumer_group,fingerprint,body,metadata_json,event_id,aggregate_id,
                    event_sequence,event_type,occurred_at,payload,trace_id,category,status,failure_count)
                SELECT ?,'ERP_AUDIT',?,X'7B7D','{}',event_id,aggregate_id,event_sequence,event_type,occurred_at,payload,trace_id,
                    'RETRIES_EXHAUSTED','DEAD',3 FROM outbox_event WHERE event_id=?
                """, archive.toString(), fingerprint, event.message().eventId());
        return archive;
    }

    /**
     * 在独占库中同事务追加同一聚合的未消费事件。
     * @param count 事件数量
     * @return 按数据库顺序构造的原始事件
     */
    private List<Delivery> unpublishedEvents(int count) {
        String aggregate = UUID.randomUUID().toString();
        var events = new ArrayList<Delivery>();
        for (int i = 0; i < count; i++) {
            var message = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                    Instant.parse("2026-10-26T10:00:00.123456Z"), "{}", "relay-isolated-trace");
            events.add(new Delivery(message, i + 1L));
        }
        unitOfWork.execute(() -> { events.forEach(event -> outbox.append(event.message())); return null; });
        return events;
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
