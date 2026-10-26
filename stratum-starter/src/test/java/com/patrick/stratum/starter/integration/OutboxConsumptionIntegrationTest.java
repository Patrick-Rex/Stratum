package com.patrick.stratum.starter.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.outbox.OutboxConsumptionRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group.*;
import static com.patrick.stratum.domain.event.OutboxConsumptionRepository.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** 在真实MariaDB上核验信封核对、唯一消费、顺序、回滚及跨实例行锁。 */
class OutboxConsumptionIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxConsumptionRepository consumption;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> aggregates = new ArrayList<>();

    /** 仅清理本用例的消费凭据、事件与技术进度。 */
    @AfterEach
    void cleanup() {
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 同事务登记原始审计信封与进度，重复消息不再取得执行资格。 */
    @Test
    void persistsOriginalAuditAndDeduplicates() {
        var events = seed(2, "erp.probe.created"); var first = events.getFirst();
        consume(first);
        assertEquals(DUPLICATE, unitOfWork.execute(() -> consumption.begin(first, ERP_AUDIT)));
        assertEquals(1L, progress(first));
        assertEquals(first.message().payload(), jdbc.queryForObject(
                "SELECT payload FROM outbox_consumption WHERE event_id = ?", String.class, first.message().eventId()));
        assertEquals(first.message().traceId(), jdbc.queryForObject(
                "SELECT trace_id FROM outbox_consumption WHERE event_id = ?", String.class, first.message().eventId()));
        consume(events.getLast()); assertEquals(2L, progress(first));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE aggregate_id = ?",
                Integer.class, first.message().aggregateId()));
    }

    /** 非头事件等待，其他聚合仍可完成；缓存订阅与ERP订阅不得互换。 */
    @Test
    void waitsForPredecessorWithoutBlockingOtherAggregates() {
        var first = seed(2, "erp.probe.created"); var cache = seed(1, "stratum.cache.invalidate").getFirst();
        assertEquals(WAITING, unitOfWork.execute(() -> consumption.begin(first.getLast(), ERP_AUDIT)));
        unitOfWork.execute(() -> {
            assertEquals(READY, consumption.begin(cache, CACHE_INVALIDATION));
            consumption.complete(cache, CACHE_INVALIDATION); return null;
        });
        assertEquals(1L, progress(cache)); assertEquals(0L, progress(first.getFirst()));
        assertThrows(IllegalArgumentException.class,
                () -> unitOfWork.execute(() -> consumption.begin(first.getFirst(), CACHE_INVALIDATION)));
    }

    /** 整个应用工作单元回滚时，凭据和顺序均不可残留。 */
    @Test
    void rollsBackReceiptAndProgressTogether() {
        var first = seed(1, "erp.probe.created").getFirst();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            consumption.complete(first, ERP_AUDIT); throw new IllegalStateException("测试回滚");
        }));
        assertEquals(0L, progress(first));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id = ?",
                Integer.class, first.message().eventId()));
        consume(first); assertEquals(1L, progress(first));
    }

    /** 逐字段拒绝伪造信封，语义相同的JSON空白不影响验证，留痕保存原文。 */
    @Test
    void rejectsAlteredEnvelopeAndPreservesPrecision() {
        var original = seed(1, "erp.probe.created").getFirst(); var m = original.message();
        var altered = List.of(
                new Delivery(m, 2),
                new Delivery(new OutboxMessage(m.eventId(), "erp.probe.updated", m.aggregateId(), m.occurredAt(), m.payload(), m.traceId()), 1),
                new Delivery(new OutboxMessage(m.eventId(), m.eventType(), m.aggregateId(), m.occurredAt().plusSeconds(1), m.payload(), m.traceId()), 1),
                new Delivery(new OutboxMessage(m.eventId(), m.eventType(), m.aggregateId(), m.occurredAt(), "{}", m.traceId()), 1),
                new Delivery(new OutboxMessage(m.eventId(), m.eventType(), m.aggregateId(), m.occurredAt(), m.payload(), "changed"), 1),
                new Delivery(new OutboxMessage(m.eventId(), m.eventType(), m.aggregateId(), m.occurredAt(), "{} {}", m.traceId()), 1));
        for (var forged : altered) assertThrows(IllegalArgumentException.class,
                () -> unitOfWork.execute(() -> consumption.begin(forged, ERP_AUDIT)));
        var reformatted = new Delivery(new OutboxMessage(m.eventId(), m.eventType(), m.aggregateId(), m.occurredAt(),
                "{ \"quantity\" : 99999999999999999999999999.123456789012 }", m.traceId()), 1);
        consume(reformatted); assertEquals(1L, progress(original));
    }

    /** 未登记的事件不能取得资格；重复完成也不能新增凭据或推进两次。 */
    @Test
    void rejectsUnknownEventAndRepeatedCompletion() {
        var first = seed(1, "erp.probe.created").getFirst(); var m = first.message();
        var unknown = new Delivery(new OutboxMessage(UUID.randomUUID().toString(), m.eventType(), m.aggregateId(),
                m.occurredAt(), m.payload(), m.traceId()), 1);
        assertThrows(IllegalArgumentException.class,
                () -> unitOfWork.execute(() -> consumption.begin(unknown, ERP_AUDIT)));
        consume(first);
        assertThrows(IllegalStateException.class,
                () -> unitOfWork.execute(() -> { consumption.complete(first, ERP_AUDIT); return null; }));
        assertEquals(1L, progress(first));
    }

    /** 无事务和只读事务均拒绝消费写入。 */
    @Test
    void requiresWritableApplicationTransaction() {
        var first = seed(1, "erp.probe.created").getFirst();
        assertThrows(IllegalStateException.class, () -> consumption.begin(first, ERP_AUDIT));
        var readOnly = new TransactionTemplate(transactionManager); readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class,
                () -> readOnly.execute(status -> consumption.begin(first, ERP_AUDIT)));
        assertEquals(0L, progress(first));
    }

    /** 两个实例独立连接：忙行立即等待，旧快照冲突回滚后新事务只能读到重复凭据。 */
    @Test
    void serializesInstancesAndUsesCurrentReadAfterOldSnapshot() throws Exception {
        var first = seed(1, "erp.probe.created").getFirst();
        var second = new OutboxConsumptionRepositoryImpl(jdbc, json);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> unitOfWork.execute(() -> {
                assertEquals(READY, consumption.begin(first, ERP_AUDIT)); locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                consumption.complete(first, ERP_AUDIT); return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var conflict = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id = ?",
                            Integer.class, first.message().eventId()));
                    assertEquals(WAITING, second.begin(first, ERP_AUDIT));
                    release.countDown();
                    try { owner.get(5, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    return second.begin(first, ERP_AUDIT);
                }));
                assertEquals(40900, conflict.getErrorCode().getCode());
            } finally { release.countDown(); }
            owner.get(5, TimeUnit.SECONDS);
            assertEquals(DUPLICATE, unitOfWork.execute(() -> second.begin(first, ERP_AUDIT)));
        }
        assertEquals(1L, progress(first));
    }

    /**
     * 追加具有固定微秒时刻的隔离事件，避免用本机纳秒推测数据库存储精度。
     * @param count 事件数量
     * @param type 主题
     * @return 持久化投递序列
     */
    private List<Delivery> seed(int count, String type) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate);
        var events = new ArrayList<Delivery>();
        unitOfWork.execute(() -> {
            for (int index = 1; index <= count; index++) {
                var message = new OutboxMessage(UUID.randomUUID().toString(), type, aggregate,
                        Instant.parse("2026-10-26T10:00:00.123456Z"),
                        "{\"quantity\":99999999999999999999999999.123456789012}", UUID.randomUUID().toString());
                outbox.append(message); events.add(new Delivery(message, index));
            }
            return null;
        }); return List.copyOf(events);
    }

    /**
     * 在应用事务中完成一次ERP留痕。
     * @param event 已登记事件
     */
    private void consume(Delivery event) {
        unitOfWork.execute(() -> {
            assertEquals(READY, consumption.begin(event, ERP_AUDIT)); consumption.complete(event, ERP_AUDIT); return null;
        });
    }

    /**
     * 独立读取当前持久化进度。
     * @param event 所属聚合事件
     * @return 已消费序号
     */
    private long progress(Delivery event) {
        return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                Long.class, event.message().aggregateId());
    }
}
