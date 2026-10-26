package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 在已有Application工作单元内核验信封、消费去重与持久化顺序，不开启事务。 */
@Repository
@RequiredArgsConstructor
public class OutboxConsumptionRepositoryImpl implements OutboxConsumptionRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    /** {@inheritDoc} */
    @Override
    public Result begin(Delivery delivery, Group group) {
        requireWrite();
        try { return beginLocked(delivery, group); }
        catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 在原工作单元获取当前读锁及消费资格，不在旧快照内重试。
     * @param delivery 原事件
     * @param group 匹配消费组
     * @return 当前持久化资格
     */
    private Result beginLocked(Delivery delivery, Group group) {
        Objects.requireNonNull(delivery, "消费事件不能为空");
        Objects.requireNonNull(group, "消费组不能为空");
        var event = delivery.message();
        if (!group.accepts(event.eventType())) throw new IllegalArgumentException("事件与消费组不匹配");
        var progress = jdbc.query("""
                SELECT consumed_sequence FROM outbox_aggregate_progress
                WHERE aggregate_id = ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getLong(1), event.aggregateId());
        if (progress.isEmpty()) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                    Integer.class, event.aggregateId());
            if (count == null || count == 0) throw new IllegalArgumentException("消费聚合不存在");
            return Result.WAITING;
        }
        var originals = jdbc.query("""
                SELECT event_type, aggregate_id, occurred_at, payload, trace_id, event_sequence
                FROM outbox_event WHERE event_id = ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Delivery(new OutboxMessage(event.eventId(), rs.getString(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(4),
                        rs.getString(5)), rs.getLong(6)), event.eventId());
        if (originals.isEmpty()) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
                    Integer.class, event.eventId());
            if (count == null || count == 0) throw new IllegalArgumentException("消费事件不存在");
            return Result.WAITING;
        }
        verify(delivery, originals.getFirst());
        var receipts = jdbc.query("""
                SELECT event_id FROM outbox_consumption WHERE event_id = ? AND consumer_group = ? FOR UPDATE
                """, (rs, row) -> rs.getString(1), event.eventId(), group.name());
        if (!receipts.isEmpty()) return Result.DUPLICATE;
        long consumed = progress.getFirst();
        if (consumed == Long.MAX_VALUE || delivery.sequence() != consumed + 1) return Result.WAITING;
        return Result.READY;
    }

    /** {@inheritDoc} */
    @Override
    public void complete(Delivery delivery, Group group) {
        if (begin(delivery, group) != Result.READY) throw new IllegalStateException("消费完成必须持有未消费的聚合头");
        try {
            int inserted = jdbc.update("""
                    INSERT INTO outbox_consumption
                    (event_id, consumer_group, aggregate_id, event_sequence, event_type, occurred_at, payload, trace_id)
                    SELECT event_id, ?, aggregate_id, event_sequence, event_type, occurred_at, payload, trace_id
                    FROM outbox_event WHERE event_id = ?
                    """, group.name(), delivery.message().eventId());
            int advanced = jdbc.update("""
                    UPDATE outbox_aggregate_progress SET consumed_sequence = ?
                    WHERE aggregate_id = ? AND consumed_sequence = ?
                    """, delivery.sequence(), delivery.message().aggregateId(), delivery.sequence() - 1);
            if (inserted != 1 || advanced != 1) throw new IllegalStateException("消费凭据或顺序更新失败");
            jdbc.update("""
                    UPDATE outbox_dead_letter d JOIN outbox_replay_delivery r ON r.dead_id = d.dead_id
                    SET d.status = 'RESOLVED'
                    WHERE r.event_id = ? AND r.consumer_group = ? AND r.status <> 'RESOLVED'
                        AND d.category = 'RETRIES_EXHAUSTED'
                    """, delivery.message().eventId(), group.name());
            jdbc.update("""
                    UPDATE outbox_replay_delivery SET status = 'RESOLVED', resolved_at = UTC_TIMESTAMP(6),
                        lease_token = NULL, lease_until = NULL, version = version + 1
                    WHERE event_id = ? AND consumer_group = ? AND status <> 'RESOLVED'
                    """, delivery.message().eventId(), group.name());
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 比对全部信封字段及无损JSON值，留痕仍复制数据库原文。
     * @param received 收到的信封
     * @param original 原始持久化信封
     * @throws IllegalArgumentException 字段不符或载荷非法
     */
    private void verify(Delivery received, Delivery original) {
        var actual = received.message(); var expected = original.message();
        try {
            var reader = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            if (received.sequence() != original.sequence() || !actual.eventType().equals(expected.eventType())
                    || !actual.aggregateId().equals(expected.aggregateId()) || !actual.occurredAt().equals(expected.occurredAt())
                    || !actual.traceId().equals(expected.traceId())
                    || !reader.readTree(actual.payload()).equals(reader.readTree(expected.payload()))) {
                throw new IllegalArgumentException("消费信封与持久化事件不符");
            }
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("消费载荷不是有效JSON");
        }
    }

    /**
     * 沿用快照/串行化冲突口径，要求调用者回滚整个工作单元后重试。
     * @param failure JDBC失败
     * @return 已识别的冲突或原异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("消费顺序状态已变更"), failure);
            }
        }
        return failure;
    }

    /**
     * 确认事务由Application建立且允许写入。
     * @throws IllegalStateException 无事务或只读事务
     */
    private static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("消费存储必须参与已有写工作单元");
        }
    }
}
