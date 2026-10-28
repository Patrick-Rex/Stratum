package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRelayRepository.Claim;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.outbox.OutboxRelayRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxPublisher.PublishResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证原投递与重放共享有序领取、公平候选和独立租约状态，不调用Broker。 */
class OutboxReplayRelayIntegrationTest extends MariaDbIntegrationTest {
    private static final OutboxRelayRepository.RetryPolicy POLICY =
            new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(30), Duration.ofSeconds(30));
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxRelayRepository relay;
    @Autowired private OutboxReplayRepository replay;
    @Autowired private OutboxDeadLetterRepository deadLetters;
    @Autowired private OutboxConsumptionRepository consumption;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    private final List<String> aggregates = new ArrayList<>();
    private final List<UUID> archives = new ArrayList<>();

    /** 按引用顺序清理本用例持久化夹具，不删除其他事件。 */
    @AfterEach
    void cleanup() {
        for (var id : archives) jdbc.update("DELETE FROM outbox_replay_request WHERE dead_id = ?", id.toString());
        for (var id : archives) jdbc.update("DELETE FROM outbox_replay_delivery WHERE dead_id = ?", id.toString());
        for (var id : archives) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 重放领取使用本地原信封，确认只更新独立意图，原SENT与消费前序屏障保持。 */
    @Test
    void claimsCanonicalOriginalAndConfirmsOnlyReplayIntent() {
        var events = seed(2, true); var intent = enqueue(events.getFirst()); var claims = claim(8);
        assertEquals(1, claims.size()); var lease = claims.getFirst();
        assertEquals(events.getFirst(), lease.delivery()); assertEquals("PROCESSING", intentStatus(intent));
        assertEquals("SENT", originalStatus(events.getFirst())); assertTrue(finish(lease, CONFIRMED));
        assertEquals("SENT", intentStatus(intent)); assertNotNull(jdbc.queryForObject(
                "SELECT sent_at FROM outbox_replay_delivery WHERE delivery_id = ?", java.time.LocalDateTime.class, intent.toString()));
        assertEquals(0L, progress(events.getFirst())); assertTrue(claim(8).isEmpty());
        unitOfWork.execute(() -> { consumption.complete(events.getFirst(), Group.ERP_AUDIT); return null; });
        var next = claim(8); assertEquals(1, next.size()); assertEquals(events.getLast(), next.getFirst().delivery());
    }

    /** 人工重放及其重试读取原事件的标准上下文，保持原始投递身份。 */
    @Test
    void replayAndRetryUseOriginalStandardContext() {
        var event = seed(1, true).getFirst();
        String parent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
        jdbc.update("UPDATE outbox_event SET standard_traceparent=?,standard_tracestate=? WHERE event_id=?",
                parent, "vendor=value", event.message().eventId());
        var intent = enqueue(event); var first = claim(1).getFirst();
        assertEquals(event, first.delivery()); assertEquals(parent, first.standardTrace().orElseThrow().traceparent());
        assertEquals("vendor=value", first.standardTrace().orElseThrow().tracestate());
        assertTrue(finish(first, RETURNED));
        jdbc.update("UPDATE outbox_replay_delivery SET next_retry_at='2000-01-01 00:00:00' WHERE delivery_id=?", intent.toString());
        var retry = claim(1).getFirst(); assertEquals(event, retry.delivery()); assertEquals(first.standardTrace(), retry.standardTrace());
    }

    /** 原投递与重放候选按到期时刻合并，不能让持续原投递饿死更早的人工恢复。 */
    @Test
    void mergesCandidateDeadlinesAcrossOriginalAndReplay() {
        var event = seed(1, true).getFirst(); var intent = enqueue(event); var original = seed(1, false).getFirst();
        jdbc.update("UPDATE outbox_replay_delivery SET next_retry_at = '2000-01-01 00:00:00' WHERE delivery_id = ?", intent.toString());
        var first = claim(1).getFirst(); assertEquals(event, first.delivery());
        var second = claim(1).getFirst(); assertEquals(original, second.delivery());
        assertEquals("SENT", originalStatus(event));
    }

    /** 失败按数据库时钟退避，达到上限不跳过聚合头，其他聚合仍可领取。 */
    @Test
    void persistsRetryAndFailureWithoutAdvancingHead() {
        var events = seed(2, true); var intent = enqueue(events.getFirst()); var first = claim(1).getFirst();
        assertTrue(finish(first, RETURNED)); assertEquals("PENDING", intentStatus(intent));
        assertEquals(1, jdbc.queryForObject("SELECT retry_count FROM outbox_replay_delivery WHERE delivery_id = ?", Integer.class, intent.toString()));
        assertTrue(claim(8).isEmpty());
        jdbc.update("UPDATE outbox_replay_delivery SET next_retry_at = '2000-01-01 00:00:00' WHERE delivery_id = ?", intent.toString());
        var second = claim(1).getFirst(); assertEquals(first.delivery(), second.delivery()); assertTrue(finish(second, UNAVAILABLE));
        assertEquals("FAILED", intentStatus(intent)); assertEquals("SENT", originalStatus(events.getFirst()));
        assertEquals(0L, progress(events.getFirst())); assertTrue(claim(8).isEmpty());
        var other = seed(1, false).getFirst(); assertEquals(other, claim(1).getFirst().delivery());
    }

    /** 数据库租约过期可接管，原令牌及伪造将来截止时间不能完成新领取。 */
    @Test
    void fencesExpiredAndTakenOverReplayLease() {
        var event = seed(1, true).getFirst(); var intent = enqueue(event); var first = claim(1).getFirst();
        jdbc.update("UPDATE outbox_replay_delivery SET lease_until = '2000-01-01 00:00:00' WHERE delivery_id = ?", intent.toString());
        var forgedFuture = new Claim(first.delivery(), first.token(), Instant.parse("2100-01-01T00:00:00Z"), first.retryCount());
        assertFalse(finish(forgedFuture, CONFIRMED)); var next = claim(1).getFirst();
        assertNotEquals(first.token(), next.token()); assertEquals(first.delivery(), next.delivery());
        assertFalse(finish(first, CONFIRMED)); assertTrue(finish(next, CONFIRMED));
        assertEquals("SENT", originalStatus(event));
    }

    /** 领取和最终结果都参与外层工作单元，回滚后不能残留租约或确认状态。 */
    @Test
    void rollsBackReplayClaimAndCompletion() {
        var event = seed(1, true).getFirst(); var intent = enqueue(event);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertEquals(1, relay.claim(request(1)).size()); throw new IllegalStateException("测试领取回滚");
        }));
        assertEquals("PENDING", intentStatus(intent)); var lease = claim(1).getFirst();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertTrue(relay.finish(lease, CONFIRMED, POLICY)); throw new IllegalStateException("测试完成回滚");
        }));
        assertEquals("PROCESSING", intentStatus(intent)); assertTrue(finish(lease, CONFIRMED));
    }

    /** 两实例独立事务只能各领取一个聚合，首实例持锁时竞争实例跳过该聚合。 */
    @Test
    void skipsClaimedAggregateAcrossRepositoryInstances() throws Exception {
        var first = seed(1, true).getFirst(); var other = seed(1, true).getFirst(); enqueue(first); enqueue(other);
        var second = new OutboxRelayRepositoryImpl(jdbc); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> unitOfWork.execute(() -> {
                var owned = relay.claim(request(1)); held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return owned.getFirst();
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var competing = executor.submit(() -> unitOfWork.execute(() -> second.claim(request(8)))).get(3, TimeUnit.SECONDS);
                assertEquals(1, competing.size()); release.countDown();
                var owned = owner.get(5, TimeUnit.SECONDS); assertNotEquals(owned.delivery().message().eventId(), competing.getFirst().delivery().message().eventId());
                assertTrue(claim(8).isEmpty());
            } finally { release.countDown(); }
        }
    }

    /** 忙意图立即返回false；伪造计数不能完成，极大失败计数也不会溢出。 */
    @Test
    void rejectsBusyOrAlteredEvidenceAndSaturatesFailureCount() throws Exception {
        var event = seed(1, true).getFirst(); var intent = enqueue(event); var lease = claim(1).getFirst();
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> unitOfWork.execute(() -> {
                jdbc.queryForObject("SELECT delivery_id FROM outbox_replay_delivery WHERE delivery_id = ? FOR UPDATE", String.class, intent.toString()); held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return null;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertFalse(executor.submit(() -> finish(lease, CONFIRMED)).get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            owner.get(5, TimeUnit.SECONDS);
        }
        assertFalse(finish(new Claim(lease.delivery(), lease.token(), lease.expiresAt(), 1), CONFIRMED));
        jdbc.update("UPDATE outbox_replay_delivery SET lease_until = '2000-01-01 00:00:00', retry_count = ? WHERE delivery_id = ?", Integer.MAX_VALUE, intent.toString());
        var maximum = claim(1).getFirst(); assertEquals(Integer.MAX_VALUE, maximum.retryCount()); assertTrue(finish(maximum, REJECTED));
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT retry_count FROM outbox_replay_delivery WHERE delivery_id = ?", Integer.class, intent.toString()));
        assertEquals("FAILED", intentStatus(intent));
    }

    /** 重放状态字段损坏时不得按不相符的聚合/序号或订阅发送原事件。 */
    @Test
    void refusesMismatchedReplaySourceColumns() {
        var event = seed(1, true).getFirst(); var intent = enqueue(event);
        jdbc.update("UPDATE outbox_replay_delivery SET event_sequence = 2 WHERE delivery_id = ?", intent.toString()); assertTrue(claim(8).isEmpty());
        jdbc.update("UPDATE outbox_replay_delivery SET event_sequence = 1, consumer_group = 'CACHE_INVALIDATION' WHERE delivery_id = ?", intent.toString());
        assertTrue(claim(8).isEmpty()); assertEquals("SENT", originalStatus(event));
    }

    /**
     * 用真实存储创建本地原事件，结束状态只为数据库测试夹具。
     * @param count 原事件数量
     * @param sent 是否将首事件构造成SENT
     * @return 原投递序列
     */
    private List<Delivery> seed(int count, boolean sent) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate); var events = new ArrayList<Delivery>();
        unitOfWork.execute(() -> {
            for (int index = 1; index <= count; index++) {
                var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                        Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":999999999999999999.123456789012}", "replay-relay-it");
                outbox.append(event); events.add(new Delivery(event, index));
            } return null;
        });
        if (sent) jdbc.update("UPDATE outbox_event SET status = 'SENT' WHERE event_id = ?", events.getFirst().message().eventId());
        return List.copyOf(events);
    }

    /**
     * 原子创建归档及重放意图，不直接投递MQ。
     * @param delivery 原事件
     * @return 独立意图UUID
     */
    private UUID enqueue(Delivery delivery) {
        var event = delivery.message();
        byte[] body = json.writeValueAsBytes(Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateId", event.aggregateId(),
                "occurredAt", event.occurredAt().toString(), "traceId", event.traceId(), "sequence", delivery.sequence(),
                "payload", json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(event.payload())));
        var letter = new Letter(UUID.randomUUID(), Group.ERP_AUDIT, body, "{}", Optional.of(delivery), Category.RETRIES_EXHAUSTED, 6);
        return unitOfWork.execute(() -> {
            var archive = deadLetters.append(letter); archives.add(archive);
            return replay.enqueue(new OutboxReplayRepository.Request(UUID.randomUUID(), archive, "replay-relay-request")).deliveryId().orElseThrow();
        });
    }

    /**
     * 构造有界领取参数。
     * @param count 上限
     * @return 30秒租期请求
     */
    private OutboxRelayRepository.ClaimRequest request(int count) { return new OutboxRelayRepository.ClaimRequest(count, Duration.ofSeconds(30)); }

    /**
     * 通过真实应用工作单元领取。
     * @param count 上限
     * @return 持久化租约
     */
    private List<Claim> claim(int count) { return unitOfWork.execute(() -> relay.claim(request(count))); }

    /**
     * 登记受控发送结果，仅验证数据库完成条件。
     * @param claim 原租约
     * @param result 测试结果
     * @return 更新结果
     */
    private boolean finish(Claim claim, com.patrick.stratum.domain.event.OutboxPublisher.PublishResult result) {
        return unitOfWork.execute(() -> relay.finish(claim, result, POLICY));
    }

    /**
     * 读取独立意图状态。
     * @param intent 意图UUID
     * @return 当前状态
     */
    private String intentStatus(UUID intent) { return jdbc.queryForObject("SELECT status FROM outbox_replay_delivery WHERE delivery_id = ?", String.class, intent.toString()); }

    /**
     * 读取原Outbox状态。
     * @param event 原事件
     * @return 原状态
     */
    private String originalStatus(Delivery event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, event.message().eventId()); }

    /**
     * 读取已消费进度。
     * @param event 原事件
     * @return 当前序号
     */
    private long progress(Delivery event) { return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, event.message().aggregateId()); }
}
