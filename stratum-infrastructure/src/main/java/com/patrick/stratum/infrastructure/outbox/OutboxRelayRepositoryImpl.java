package com.patrick.stratum.infrastructure.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

/** Relay技术状态适配，按聚合进度行锁定头事件，所有写入参与已有Application工作单元。 */
@Repository
@RequiredArgsConstructor
public class OutboxRelayRepositoryImpl implements OutboxRelayRepository {
    private static final String READY = """
            ((e.status = 'PENDING' AND e.next_retry_at <= UTC_TIMESTAMP(6))
            OR (e.status = 'PROCESSING' AND e.lease_until <= UTC_TIMESTAMP(6)))
            """;
    private final JdbcTemplate jdbc;

    /** {@inheritDoc} */
    @Override
    public List<Claim> claim(ClaimRequest request) {
        requireWriteUnit();
        Objects.requireNonNull(request, "领取请求不能为空");
        try {
            var candidates = jdbc.queryForList("""
                    SELECT p.aggregate_id
                    FROM outbox_aggregate_progress p
                    JOIN outbox_event e ON e.aggregate_id = p.aggregate_id
                      AND e.event_sequence = CASE WHEN p.consumed_sequence < p.last_sequence
                          THEN p.consumed_sequence + 1 ELSE NULL END
                    WHERE
                    """ + READY + " ORDER BY e.next_retry_at, e.event_id LIMIT ?", String.class, request.batchSize() * 4);
            var claims = new ArrayList<Claim>(request.batchSize());
            for (String aggregate : candidates) {
                if (claims.size() == request.batchSize()) break;
                var progress = jdbc.query("""
                        SELECT last_sequence, consumed_sequence FROM outbox_aggregate_progress
                        WHERE aggregate_id = ? FOR UPDATE SKIP LOCKED
                        """, (row, number) -> new Progress(row.getLong("last_sequence"), row.getLong("consumed_sequence")), aggregate);
                if (progress.isEmpty() || progress.getFirst().consumed() >= progress.getFirst().allocated()) continue;
                long sequence = progress.getFirst().consumed() + 1;
                var heads = jdbc.query("""
                        SELECT e.*, UTC_TIMESTAMP(6) AS database_now FROM outbox_event e
                        WHERE e.aggregate_id = ? AND e.event_sequence = ? AND
                        """ + READY + " FOR UPDATE SKIP LOCKED", OutboxRelayRepositoryImpl::mapHead, aggregate, sequence);
                if (heads.isEmpty()) continue;
                var head = heads.getFirst();
                UUID token = UUID.randomUUID();
                LocalDateTime expires = head.databaseNow().plusNanos(request.leaseDuration().toNanos());
                int changed = jdbc.update("""
                        UPDATE outbox_event SET status = 'PROCESSING', lease_token = ?, lease_until = ?, version = version + 1
                        WHERE event_id = ?
                        """, token.toString(), expires, head.delivery().message().eventId());
                if (changed != 1) throw new StratumException(CommonErrorCode.CONFLICT);
                claims.add(new Claim(head.delivery(), token, expires.toInstant(ZoneOffset.UTC), head.retryCount()));
            }
            return List.copyOf(claims);
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean finish(Claim claim, OutboxPublisher.PublishResult result, RetryPolicy retryPolicy) {
        requireWriteUnit();
        Objects.requireNonNull(claim, "领取证据不能为空");
        Objects.requireNonNull(result, "发送结果不能为空");
        Objects.requireNonNull(retryPolicy, "重试策略不能为空");
        boolean confirmed = result == OutboxPublisher.PublishResult.CONFIRMED;
        int failures = confirmed ? claim.retryCount() : (int) Math.min(Integer.MAX_VALUE, (long) claim.retryCount() + 1);
        String target = confirmed ? "SENT" : failures >= retryPolicy.maxFailures() ? "FAILED" : "PENDING";
        long delayMicros = target.equals("PENDING") ? retryPolicy.delayForFailure(failures).toNanos() / 1000 : 0;
        var delivery = claim.delivery();
        try {
            var locked = jdbc.queryForList("""
                    SELECT event_id FROM outbox_event
                    WHERE event_id = ? AND status = 'PROCESSING' AND lease_token = ?
                    FOR UPDATE SKIP LOCKED
                    """, String.class, delivery.message().eventId(), claim.token().toString());
            if (locked.isEmpty()) return false;
            return jdbc.update("""
                    UPDATE outbox_event SET status = ?, retry_count = ?,
                        next_retry_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                        lease_token = NULL, lease_until = NULL, version = version + 1
                    WHERE event_id = ? AND aggregate_id = ? AND event_sequence = ?
                      AND status = 'PROCESSING' AND lease_token = ? AND retry_count = ?
                      AND lease_until > UTC_TIMESTAMP(6)
                    """, target, failures, delayMicros, delivery.message().eventId(), delivery.message().aggregateId(),
                    delivery.sequence(), claim.token().toString(), claim.retryCount()) == 1;
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /**
     * 从已锁定头事件恢复原始投递与数据库时间，不重新分配事件身份。
     * @param row 当前结果行
     * @param number 行号
     * @return 有界事件数据及数据库时刻
     * @throws SQLException JDBC字段读取失败
     */
    private static Head mapHead(ResultSet row, int number) throws SQLException {
        var message = new OutboxMessage(row.getString("event_id"), row.getString("event_type"),
                row.getString("aggregate_id"), row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                row.getString("payload"), row.getString("trace_id"));
        return new Head(new OutboxPublisher.Delivery(message, row.getLong("event_sequence")),
                row.getInt("retry_count"), row.getObject("database_now", LocalDateTime.class));
    }

    /** 缺少Application写事务时拒绝所有领取和状态更新。 */
    private static void requireWriteUnit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Relay存储必须参与Application写工作单元");
        }
    }

    /**
     * 沿用项目快照/串行化冲突口径，不在仓储中重试旧事务。
     * @param failure JDBC异常
     * @return 40900冲突或原异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("事件领取状态已变更"), failure);
            }
        }
        return failure;
    }

    /**
     * 进度锁定快照，非业务读模型。
     * @param allocated 最大分配序号
     * @param consumed 已消费序号
     */
    private record Progress(long allocated, long consumed) { }

    /**
     * 仅在有界领取内存活的头事件快照。
     * @param delivery 原持久化信封
     * @param retryCount 当前失败次数
     * @param databaseNow 数据库UTC时刻
     */
    private record Head(OutboxPublisher.Delivery delivery, int retryCount, LocalDateTime databaseNow) { }
}
