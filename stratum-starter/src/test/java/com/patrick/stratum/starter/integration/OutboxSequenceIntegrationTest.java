package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实工作单元和数据库验证持久化序号，竞争行为在后续专门节点验收。 */
class OutboxSequenceIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final List<String> aggregates = new ArrayList<>();

    /**
     * 注入真实应用事务与仓储，JDBC只用于独立断言及清理。
     * @param unitOfWork 应用工作单元
     * @param outbox 事件仓储
     * @param jdbc 断言客户端
     */
    @Autowired
    OutboxSequenceIntegrationTest(UnitOfWork unitOfWork, OutboxRepository outbox, JdbcTemplate jdbc) {
        this.unitOfWork = unitOfWork; this.outbox = outbox; this.jdbc = jdbc;
    }

    /** 先删除本用例事件，再清理对应技术进度，不触及其他聚合。 */
    @AfterEach
    void cleanup() {
        for (String aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 连续事件使用正序号，UUID、原始JSON、关联值与待消费进度保持。 */
    @Test
    void persistsSequenceAndOriginalEnvelopeAtomically() {
        String aggregate = aggregate();
        var first = message(aggregate, "{\"quantity\":99999999999999999999999999.123456789012}");
        unitOfWork.execute(() -> { outbox.append(first); outbox.append(message(aggregate, "{}")); return null; });
        assertEquals(List.of(1L, 2L), sequences(aggregate));
        var row = jdbc.queryForMap("SELECT event_id, payload, trace_id FROM outbox_event WHERE event_id = ?", first.eventId());
        assertEquals(first.eventId(), row.get("event_id"));
        assertEquals(first.payload(), row.get("payload"));
        assertEquals(first.traceId(), row.get("trace_id"));
        assertEquals(2L, lastSequence(aggregate));
        assertEquals(0L, jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, aggregate));
    }

    /** 大小写和尾随空格不同的有效标识保持独立进度，均从序号1开始。
     */
    @Test
    void isolatesCaseAndTrailingSpacesInAggregateIdentifiers() {
        String prefix = UUID.randomUUID().toString();
        var ids = List.of(prefix + "A", prefix + "a", prefix + "A ");
        aggregates.addAll(ids);
        unitOfWork.execute(() -> {
            ids.forEach(id -> outbox.append(message(id, "{}")));
            return null;
        });
        for (String id : ids) {
            assertEquals(List.of(1L), sequences(id));
            assertEquals(1L, lastSequence(id));
        }
    }

    /** 外层失败、JSON失败和重复UUID均回滚序号，下一次提交不留空洞。 */
    @Test
    void rollsBackAllocatedSequenceOnEveryFailedAppend() {
        String aggregate = aggregate();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            outbox.append(message(aggregate, "{}"));
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(0, progressCount(aggregate));
        assertThrows(RuntimeException.class, () -> unitOfWork.execute(() -> {
            outbox.append(message(aggregate, "{"));
            return null;
        }));
        assertEquals(0, progressCount(aggregate));
        var committed = message(aggregate, "{}");
        unitOfWork.execute(() -> { outbox.append(committed); return null; });
        assertThrows(RuntimeException.class, () -> unitOfWork.execute(() -> { outbox.append(committed); return null; }));
        assertEquals(1L, lastSequence(aggregate));
        unitOfWork.execute(() -> { outbox.append(message(aggregate, "{}")); return null; });
        assertEquals(List.of(1L, 2L), sequences(aggregate));
    }

    /** 缺失写事务或带只读标志时，在创建进度记录前拒绝。 */
    @Test
    void rejectsMissingAndReadOnlyTransactionBeforeAllocation() {
        String aggregate = aggregate();
        var event = message(aggregate, "{}");
        assertThrows(IllegalStateException.class, () -> outbox.append(event));
        boolean previousActive = TransactionSynchronizationManager.isActualTransactionActive();
        boolean previousReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try { assertThrows(IllegalStateException.class, () -> outbox.append(event)); }
        finally {
            TransactionSynchronizationManager.setActualTransactionActive(previousActive);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(previousReadOnly);
        }
        assertEquals(0, progressCount(aggregate));
    }

    /** 技术序号耗尽不能溢出为负数或插入事件，原进度保持。 */
    @Test
    void refusesSequenceExhaustionWithoutOverflow() {
        String aggregate = aggregate();
        unitOfWork.execute(() -> { outbox.append(message(aggregate, "{}")); return null; });
        jdbc.update("UPDATE outbox_aggregate_progress SET last_sequence = ? WHERE aggregate_id = ?", Long.MAX_VALUE, aggregate);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> { outbox.append(message(aggregate, "{}")); return null; }));
        assertEquals(Long.MAX_VALUE, lastSequence(aggregate));
        assertEquals(List.of(1L), sequences(aggregate));
    }

    /** 数据库同时保护正序号、进度上界和事件对进度的来源引用。 */
    @Test
    void enforcesDatabaseProgressAndSequenceConstraints() {
        String aggregate = aggregate();
        unitOfWork.execute(() -> { outbox.append(message(aggregate, "{}")); return null; });
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE outbox_event SET event_sequence = 0 WHERE aggregate_id = ?", aggregate));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE outbox_aggregate_progress SET consumed_sequence = 2 WHERE aggregate_id = ?", aggregate));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate));
        assertEquals(List.of(1L), sequences(aggregate));
    }

    /**
     * 执行迁移原文的DDL前置守卫，非空时用明确错误中止且不改动已有事件。
     * @throws Exception 迁移资源读取失败
     */
    @Test
    void refusesNonEmptyMigrationBeforeAnyDdl() throws Exception {
        String aggregate = aggregate();
        unitOfWork.execute(() -> { outbox.append(message(aggregate, "{}")); return null; });
        String sql = new ClassPathResource("db/migration/V202610260001__add_outbox_ordering.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String guard = sql.substring(0, sql.indexOf("CREATE TABLE"));
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                try {
                    SQLException failure = assertThrows(SQLException.class, () -> {
                        for (String command : guard.split(";")) {
                            if (!command.isBlank()) statement.execute(command.strip());
                        }
                    });
                    assertEquals("45000", failure.getSQLState());
                } finally {
                    statement.execute("DEALLOCATE PREPARE stratum_outbox_guard");
                    statement.execute("SET @stratum_outbox_guard_sql = NULL");
                }
            }
            return null;
        });
        assertEquals(List.of(1L), sequences(aggregate));
    }

    /**
     * 生成并登记本用例聚合标识。
     * @return 随机聚合标识
     */
    private String aggregate() {
        String id = UUID.randomUUID().toString();
        aggregates.add(id);
        return id;
    }

    /**
     * 创建测试信封，不修改原领域标识规则。
     * @param aggregate 聚合标识
     * @param payload 原始JSON
     * @return 随机UUID事件
     */
    private static OutboxMessage message(String aggregate, String payload) {
        return new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                Instant.now(), payload, "outbox-sequence-trace");
    }

    /**
     * 按持久序号读取实际事件顺序。
     * @param aggregate 聚合标识
     * @return 已提交序号
     */
    private List<Long> sequences(String aggregate) {
        return jdbc.queryForList("SELECT event_sequence FROM outbox_event WHERE aggregate_id = ? ORDER BY event_sequence",
                Long.class, aggregate);
    }

    /**
     * 读取实际分配进度。
     * @param aggregate 聚合标识
     * @return 已分配最大序号
     */
    private long lastSequence(String aggregate) {
        return jdbc.queryForObject("SELECT last_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, aggregate);
    }

    /**
     * 查询本聚合进度存在性。
     * @param aggregate 聚合标识
     * @return 实际记录数
     */
    private int progressCount(String aggregate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_aggregate_progress WHERE aggregate_id = ?", Integer.class, aggregate);
    }
}
