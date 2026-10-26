package com.patrick.stratum.starter.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 以真实独立连接和有界闩锁验证聚合序号的提交顺序，不依赖线程睡眠。 */
class OutboxSequenceConcurrencyIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final List<String> aggregates = new ArrayList<>();

    /**
     * 注入无状态的实际事务和仓储。
     * @param unitOfWork 应用工作单元
     * @param outbox 真实事件仓储
     * @param jdbc 独立断言客户端
     */
    @Autowired
    OutboxSequenceConcurrencyIntegrationTest(UnitOfWork unitOfWork, OutboxRepository outbox, JdbcTemplate jdbc) {
        this.unitOfWork = unitOfWork; this.outbox = outbox; this.jdbc = jdbc;
    }

    /** 只清理本用例登记的事件及对应技术进度。 */
    @AfterEach
    void cleanup() {
        for (String aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /**
     * 新聚合的第二写者不能抢在首事务提交前分配并提交后序。
     * @throws Exception 并发执行失败或超时
     */
    @Test
    void serializesFirstAllocationAcrossConnections() throws Exception {
        verifySameAggregate(false, false);
    }

    /**
     * 首事务回滚后，等待写者从1开始，不留下已分配空洞。
     * @throws Exception 并发执行失败或超时
     */
    @Test
    void reusesRolledBackFirstSequenceForWaitingWriter() throws Exception {
        verifySameAggregate(true, false);
    }

    /**
     * 第二事务旧快照冲突回滚后，整工作单元重试从最新进度分配。
     * @throws Exception 并发执行失败或超时
     */
    @Test
    void allocatesFromLatestProgressDespiteEarlierReadSnapshot() throws Exception {
        verifySameAggregate(false, true);
    }

    /**
     * 持有一个聚合的事务不会阻塞其他聚合独立提交。
     * @throws Exception 并发执行失败或超时
     */
    @Test
    void letsDifferentAggregateCommitWhileFirstIsHeld() throws Exception {
        String firstId = aggregate(); String otherId = aggregate();
        var appended = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> unitOfWork.execute(() -> {
                outbox.append(message(firstId));
                appended.countDown();
                await(release);
                return null;
            }));
            try {
                await(appended);
                var other = executor.submit(() -> unitOfWork.execute(() -> {
                    outbox.append(message(otherId));
                    return null;
                }));
                other.get(5, TimeUnit.SECONDS);
                assertEquals(List.of(1L), sequences(otherId));
                assertTrue(sequences(firstId).isEmpty());
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1L), sequences(firstId));
        }
    }

    /**
     * 通过首事务持锁、第二事务到达与独立连接ID验证真实竞争。
     * @param rollbackFirst 首事务是否应回滚
     * @param establishSnapshot 是否先提交种子并在第二事务建立旧快照
     * @throws Exception 并发执行失败或超时
     */
    private void verifySameAggregate(boolean rollbackFirst, boolean establishSnapshot) throws Exception {
        String aggregate = aggregate();
        int seed = establishSnapshot ? 1 : 0;
        if (establishSnapshot) unitOfWork.execute(() -> { outbox.append(message(aggregate)); return null; });
        var firstEvent = message(aggregate); var secondEvent = message(aggregate);
        var appended = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var firstConnection = new AtomicLong(); var secondConnection = new AtomicLong();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> unitOfWork.execute(() -> {
                firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                outbox.append(firstEvent);
                appended.countDown();
                await(release);
                if (rollbackFirst) throw new IllegalStateException("first-writer-rollback");
                return null;
            }));
            try {
                await(appended);
                var second = executor.submit(() -> unitOfWork.execute(() -> {
                    secondConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    if (establishSnapshot) {
                        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@tx_isolation", String.class));
                        assertEquals(seed, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                                Integer.class, aggregate));
                    }
                    secondEntered.countDown();
                    outbox.append(secondEvent);
                    return null;
                }));
                await(secondEntered);
                assertNotEquals(firstConnection.get(), secondConnection.get());
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                assertEquals(seed, sequences(aggregate).size());
                release.countDown();
                if (rollbackFirst) {
                    var failure = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
                    assertEquals("first-writer-rollback", failure.getCause().getMessage());
                } else first.get(5, TimeUnit.SECONDS);
                if (establishSnapshot) {
                    var failure = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                    var conflict = assertInstanceOf(StratumException.class, failure.getCause());
                    assertEquals(40900, conflict.getErrorCode().getCode());
                    assertEquals(seed + 1L, jdbc.queryForObject(
                            "SELECT last_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, aggregate));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
                            Integer.class, secondEvent.eventId()));
                    unitOfWork.execute(() -> { outbox.append(secondEvent); return null; });
                } else second.get(5, TimeUnit.SECONDS);
                long secondSequence = seed + (rollbackFirst ? 1L : 2L);
                assertEquals(secondEvent.eventId(), jdbc.queryForObject(
                        "SELECT event_id FROM outbox_event WHERE aggregate_id = ? AND event_sequence = ?",
                        String.class, aggregate, secondSequence));
                assertEquals(secondSequence, jdbc.queryForObject(
                        "SELECT last_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, aggregate));
                if (rollbackFirst) assertEquals(List.of(1L), sequences(aggregate));
                else assertEquals(establishSnapshot ? List.of(1L, 2L, 3L) : List.of(1L, 2L), sequences(aggregate));
            } finally { release.countDown(); }
        }
    }

    /**
     * 有界等待测试协调点；中断标志保持并令工作单元失败。
     * @param latch 当前闩锁
     * @throws IllegalStateException 等待超时或被中断
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发协调超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发协调被中断", failure);
        }
    }

    /**
     * 创建并登记自己的聚合标识。
     * @return 随机标识
     */
    private String aggregate() {
        String aggregate = UUID.randomUUID().toString();
        aggregates.add(aggregate);
        return aggregate;
    }

    /**
     * 为已登记聚合构造事件。
     * @param aggregate 聚合标识
     * @return 随机UUID事件
     */
    private static OutboxMessage message(String aggregate) {
        return new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                Instant.now(), "{}", "outbox-concurrency-trace");
    }

    /**
     * 从独立调用连接读取已提交序号。
     * @param aggregate 聚合标识
     * @return 按序号排序的已提交结果
     */
    private List<Long> sequences(String aggregate) {
        return jdbc.queryForList("SELECT event_sequence FROM outbox_event WHERE aggregate_id = ? ORDER BY event_sequence",
                Long.class, aggregate);
    }
}
