package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.outbox.OutboxRelayRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.patrick.stratum.domain.event.OutboxPublisher.PublishResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实MariaDB核验租约领取、过期接管、失败退避和数据库时钟边界。 */
class OutboxRelayRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final OutboxRelayRepository relay;
    private final JdbcTemplate jdbc;
    private final List<String> aggregates = new ArrayList<>();
    private final OutboxRelayRepository.RetryPolicy policy =
            new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(30), Duration.ofSeconds(30));

    /**
     * 注入实际事务、追加和领取端口。
     * @param unitOfWork 应用工作单元
     * @param outbox 原始事件追加
     * @param relay 领取端口
     * @param jdbc 独立断言客户端
     */
    @Autowired
    OutboxRelayRepositoryIntegrationTest(UnitOfWork unitOfWork, OutboxRepository outbox,
            OutboxRelayRepository relay, JdbcTemplate jdbc) {
        this.unitOfWork = unitOfWork; this.outbox = outbox; this.relay = relay; this.jdbc = jdbc;
    }

    /** 仅清理本用例所属事件与聚合技术进度。 */
    @AfterEach
    void cleanup() {
        for (String aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 有界批次只返回各聚合头，确认不等于消费；推进消费证据后才可领取后序。 */
    @Test
    void claimsOnlyHeadsAndWaitsForPersistedConsumption() {
        var first = seed(2); var other = seed(2);
        var claims = claim(2);
        assertEquals(2, claims.size());
        assertThrows(UnsupportedOperationException.class, () -> claims.add(claims.getFirst()));
        assertEquals(new HashSet<>(List.of(first.getFirst().eventId(), other.getFirst().eventId())),
                new HashSet<>(claims.stream().map(c -> c.delivery().message().eventId()).toList()));
        for (var claimed : claims) {
            assertEquals(1L, claimed.delivery().sequence());
            assertEquals("PROCESSING", status(claimed));
            assertTrue(finish(claimed, CONFIRMED));
            assertEquals("SENT", status(claimed));
        }
        assertTrue(claim(2).isEmpty());
        jdbc.update("UPDATE outbox_aggregate_progress SET consumed_sequence = 1 WHERE aggregate_id = ?",
                first.getFirst().aggregateId());
        var next = claim(2);
        assertEquals(1, next.size());
        assertEquals(first.get(1).eventId(), next.getFirst().delivery().message().eventId());
        assertEquals(2L, next.getFirst().delivery().sequence());
    }

    /** 非确认结果增加失败和退避，超限后FAILED保留头阻塞，其他聚合继续。 */
    @Test
    void retriesWithDatabaseTimeAndStopsAtFailureLimit() {
        var events = seed(2);
        var first = claim(1).getFirst();
        Instant before = databaseNow();
        assertTrue(finish(first, TIMED_OUT));
        Instant after = databaseNow();
        Instant retryAt = jdbc.queryForObject("SELECT next_retry_at FROM outbox_event WHERE event_id = ?",
                LocalDateTime.class, events.getFirst().eventId()).toInstant(ZoneOffset.UTC);
        assertFalse(retryAt.isBefore(before.plusSeconds(30)));
        assertFalse(retryAt.isAfter(after.plusSeconds(30)));
        assertEquals("PENDING", status(first));
        assertEquals(1, retries(first));
        jdbc.update("UPDATE outbox_event SET next_retry_at = '2100-01-01 00:00:00' WHERE event_id = ?",
                events.getFirst().eventId());
        assertTrue(claim(1).isEmpty());
        makeReady(events.getFirst().eventId());
        var second = claim(1).getFirst();
        assertEquals(1, second.retryCount());
        assertTrue(finish(second, RETURNED));
        assertEquals("FAILED", status(second));
        assertEquals(2, retries(second));
        assertTrue(claim(1).isEmpty());
        var other = seed(1);
        assertEquals(other.getFirst().eventId(), claim(1).getFirst().delivery().message().eventId());
    }

    /** 过期不能完成；接管更换令牌，伪造未来截止时刻也不能绕过数据库时钟。 */
    @Test
    void fencesExpiredAndReplacedLeases() {
        seed(1);
        var first = claim(1).getFirst();
        jdbc.update("UPDATE outbox_event SET lease_until = '2000-01-01 00:00:00' WHERE event_id = ?",
                first.delivery().message().eventId());
        var forgedFuture = new OutboxRelayRepository.Claim(first.delivery(), first.token(), Instant.MAX, first.retryCount());
        assertFalse(finish(forgedFuture, CONFIRMED));
        var replacement = claim(1).getFirst();
        assertNotEquals(first.token(), replacement.token());
        assertFalse(finish(first, CONFIRMED));
        assertTrue(finish(replacement, CONFIRMED));
        assertFalse(finish(replacement, CONFIRMED));
    }

    /** 领取和状态更新都参与原事务，外层失败后不留下租约或错误终态。 */
    @Test
    void rollsBackClaimAndCompletionWithOuterUnit() {
        seed(1);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            relay.claim(request(1));
            throw new IllegalStateException("claim-rollback");
        }));
        var first = claim(1).getFirst();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertTrue(relay.finish(first, CONFIRMED, policy));
            throw new IllegalStateException("finish-rollback");
        }));
        assertEquals("PROCESSING", status(first));
        assertTrue(finish(first, CONFIRMED));
    }

    /** 修改令牌、序号或失败次数的领取证据不能覆盖真实持有者。 */
    @Test
    void refusesMismatchedClaimEvidence() {
        seed(1);
        var first = claim(1).getFirst();
        assertFalse(finish(new OutboxRelayRepository.Claim(first.delivery(), UUID.randomUUID(), first.expiresAt(), 0), CONFIRMED));
        var wrongSequence = new OutboxPublisher.Delivery(first.delivery().message(), 2);
        assertFalse(finish(new OutboxRelayRepository.Claim(wrongSequence, first.token(), first.expiresAt(), 0), CONFIRMED));
        assertFalse(finish(new OutboxRelayRepository.Claim(first.delivery(), first.token(), first.expiresAt(), 1), CONFIRMED));
        assertEquals("PROCESSING", status(first));
        assertTrue(finish(first, CONFIRMED));
    }

    /** 无事务与只读标志拒绝；数据库不允许PROCESSING缺少租约。 */
    @Test
    void requiresWriteUnitAndValidDatabaseLease() {
        var event = seed(1).getFirst();
        assertThrows(IllegalStateException.class, () -> relay.claim(request(1)));
        boolean previousActive = TransactionSynchronizationManager.isActualTransactionActive();
        boolean previousReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try { assertThrows(IllegalStateException.class, () -> relay.claim(request(1))); }
        finally {
            TransactionSynchronizationManager.setActualTransactionActive(previousActive);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(previousReadOnly);
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE outbox_event SET status = 'PROCESSING' WHERE event_id = ?", event.eventId()));
        var claim = claim(1).getFirst();
        assertThrows(IllegalStateException.class, () -> relay.finish(claim, CONFIRMED, policy));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE outbox_event SET lease_until = NULL WHERE event_id = ?", event.eventId()));
    }

    /** 极大失败计数保持INT上限而不溢出，失败结果仍进入FAILED。 */
    @Test
    void saturatesCorruptlyLargeFailureCountWithoutOverflow() {
        var event = seed(1).getFirst();
        jdbc.update("UPDATE outbox_event SET retry_count = ? WHERE event_id = ?", Integer.MAX_VALUE, event.eventId());
        var claim = claim(1).getFirst();
        assertTrue(finish(claim, UNAVAILABLE));
        assertEquals(Integer.MAX_VALUE, retries(claim));
        assertEquals("FAILED", status(claim));
    }

    /**
     * 首领取事务持锁时，第二仓储实例跳过它并领取其他聚合，不产生重叠。
     * @throws Exception 并发操作失败或超时
     */
    @Test
    void skipsLockedAggregateAcrossRepositoryInstances() throws Exception {
        seed(1); seed(1); seed(1);
        var firstReady = new CountDownLatch(1); var release = new CountDownLatch(1);
        var firstConnection = new AtomicLong(); var secondConnection = new AtomicLong();
        var secondRepository = new OutboxRelayRepositoryImpl(jdbc);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> unitOfWork.execute(() -> {
                firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                var claimed = relay.claim(request(1));
                assertEquals(1, claimed.size());
                firstReady.countDown();
                await(release);
                return claimed;
            }));
            try {
                await(firstReady);
                var second = executor.submit(() -> unitOfWork.execute(() -> {
                    secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    return secondRepository.claim(request(2));
                }));
                var other = second.get(3, TimeUnit.SECONDS);
                assertEquals(2, other.size());
                assertNotEquals(firstConnection.get(), secondConnection.get());
                release.countDown();
                var all = new ArrayList<>(first.get(3, TimeUnit.SECONDS));
                all.addAll(other);
                assertEquals(3, new HashSet<>(all.stream().map(c -> c.delivery().message().eventId()).toList()).size());
                assertEquals(3, new HashSet<>(all.stream().map(OutboxRelayRepository.Claim::token).toList()).size());
            } finally { release.countDown(); }
        }
    }

    /**
     * 其他事务占用事件行时，完成操作立即返回未更新，不能等待后依据旧时间误通过。
     * @throws Exception 并发操作失败或超时
     */
    @Test
    void doesNotWaitToFinalizeLockedRowUsingStaleTime() throws Exception {
        var event = seed(1).getFirst();
        var claim = claim(1).getFirst();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> unitOfWork.execute(() -> {
                jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_id = ? FOR UPDATE",
                        String.class, event.eventId());
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                await(locked);
                assertFalse(executor.submit(() -> finish(claim, CONFIRMED)).get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            holder.get(3, TimeUnit.SECONDS);
        }
        assertTrue(finish(claim, CONFIRMED));
    }

    /**
     * 为新聚合写入指定数量的有序事件。
     * @param count 事件数
     * @return 按序构造的原始信封
     */
    private List<OutboxMessage> seed(int count) {
        String aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate);
        var events = new ArrayList<OutboxMessage>();
        for (int i = 0; i < count; i++) events.add(new OutboxMessage(UUID.randomUUID().toString(),
                "erp.probe.created", aggregate, Instant.now(), "{}", "relay-repository-trace"));
        unitOfWork.execute(() -> { events.forEach(outbox::append); return null; });
        return events;
    }

    /**
     * 在实际工作单元内领取。
     * @param size 批次上限
     * @return 已提交的领取证据
     */
    private List<OutboxRelayRepository.Claim> claim(int size) { return unitOfWork.execute(() -> relay.claim(request(size))); }

    /**
     * 统一使用30秒租期，测试过期通过显式数据库探针构造。
     * @param size 批次上限
     * @return 有界请求
     */
    private static OutboxRelayRepository.ClaimRequest request(int size) {
        return new OutboxRelayRepository.ClaimRequest(size, Duration.ofSeconds(30));
    }

    /**
     * 在独立工作单元中记录实际结果。
     * @param claim 领取证据
     * @param result 发送结果
     * @return 是否更新成功
     */
    private boolean finish(OutboxRelayRepository.Claim claim, OutboxPublisher.PublishResult result) {
        return unitOfWork.execute(() -> relay.finish(claim, result, policy));
    }

    /**
     * 查询实际事件状态。
     * @param claim 对应领取
     * @return 状态原值
     */
    private String status(OutboxRelayRepository.Claim claim) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, claim.delivery().message().eventId());
    }

    /**
     * 查询实际失败次数。
     * @param claim 对应领取
     * @return 已存次数
     */
    private int retries(OutboxRelayRepository.Claim claim) {
        return jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE event_id = ?", Integer.class, claim.delivery().message().eventId());
    }

    /**
     * 将重试时间置于明确过去，不依赖线程睡眠。
     * @param eventId 本用例事件
     */
    private void makeReady(String eventId) {
        jdbc.update("UPDATE outbox_event SET next_retry_at = '2000-01-01 00:00:00' WHERE event_id = ?", eventId);
    }

    /**
     * 获取数据库UTC时刻作为退避断言边界。
     * @return 数据库时刻
     */
    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    /**
     * 有界协调并保持中断状态。
     * @param latch 闩锁
     * @throws IllegalStateException 超时或中断
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("租约并发协调超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("租约并发协调中断", failure);
        }
    }
}
