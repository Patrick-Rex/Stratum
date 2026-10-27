package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxOriginalRecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原事件恢复存储：在已有工作单元复核消费事实、顺序与重放资格并保存首次回执。 */
@Repository
@RequiredArgsConstructor
public class OutboxOriginalRecoveryRepositoryImpl implements OutboxOriginalRecoveryRepository {
    private final JdbcTemplate jdbc;

    /** {@inheritDoc} */
    @Override
    public Receipt recover(Request request) {
        requireWrite();
        Objects.requireNonNull(request, "恢复请求不能为空");
        try {
            String eventId = request.eventId().toString();
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO outbox_original_recovery_request
                        (request_id,event_id,operator_id,registration_token,outcome,trace_id)
                    VALUES (?,?,?,?,'REGISTERING',?)
                    ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)
                    """, request.requestId().toString(), eventId, request.operatorId().toString(), token, request.traceId());
            var current = find(request.requestId());
            if (current == null) throw conflict("恢复请求登记失败");
            if (!current.token().equals(token)) return checkIdentity(request, current);
            var hints = jdbc.queryForList("SELECT aggregate_id FROM outbox_event WHERE event_id=?", String.class, eventId);
            if (hints.isEmpty()) throw new StratumException(CommonErrorCode.NOT_FOUND, "原事件不存在");
            String aggregate = hints.getFirst();
            var progress = jdbc.query("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id=? FOR UPDATE NOWAIT",
                    (rs, row) -> rs.getLong(1), aggregate);
            if (progress.size() != 1) throw conflict("聚合进度不存在或正在变更");
            var events = jdbc.query("SELECT aggregate_id,event_sequence,status,retry_count FROM outbox_event WHERE event_id=? FOR UPDATE NOWAIT",
                    (rs, row) -> new Event(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4)), eventId);
            if (events.size() != 1 || !aggregate.equals(events.getFirst().aggregate())) throw conflict("原事件正在变更");
            var event = events.getFirst();
            var receipts = jdbc.query("""
                    SELECT (BINARY c.aggregate_id=BINARY e.aggregate_id AND c.event_sequence=e.event_sequence
                        AND BINARY c.event_type=BINARY e.event_type AND c.occurred_at=e.occurred_at
                        AND BINARY c.payload=BINARY e.payload AND BINARY c.trace_id=BINARY e.trace_id
                        AND ((BINARY c.consumer_group=BINARY 'ERP_AUDIT' AND BINARY e.event_type LIKE BINARY 'erp.%')
                            OR (BINARY c.consumer_group=BINARY 'CACHE_INVALIDATION' AND BINARY e.event_type=BINARY 'stratum.cache.invalidate')))
                    FROM outbox_consumption c JOIN outbox_event e ON e.event_id=c.event_id
                    WHERE c.event_id=? FOR UPDATE NOWAIT
                    """, (rs, row) -> rs.getBoolean(1), eventId);
            boolean consumed = receipts.size() == 1 && receipts.getFirst() && progress.getFirst() >= event.sequence();
            if (!receipts.isEmpty() && !consumed) throw conflict("消费凭据与原事件不一致");
            if (!consumed && (progress.getFirst() == Long.MAX_VALUE || event.sequence() != progress.getFirst() + 1
                    || !"FAILED".equals(event.status()))) throw conflict("原事件不是可恢复的失败聚合头");
            if (!consumed) {
                var active = jdbc.queryForList("""
                        SELECT delivery_id FROM outbox_replay_delivery WHERE event_id=?
                            AND status IN ('PENDING','PROCESSING','SENT') FOR UPDATE NOWAIT
                        """, String.class, eventId);
                if (!active.isEmpty()) throw conflict("原事件存在活动重放意图");
            }
            Outcome outcome = consumed ? Outcome.ALREADY_CONSUMED : Outcome.RECOVERED;
            if (!consumed && jdbc.update("""
                    UPDATE outbox_event SET status='PENDING', next_retry_at=UTC_TIMESTAMP(6), lease_token=NULL, lease_until=NULL,
                        version=CASE WHEN version<9223372036854775807 THEN version+1 ELSE version END
                    WHERE event_id=? AND status='FAILED'
                    """, eventId) != 1) throw conflict("原事件状态已变更");
            Integer previous = consumed ? null : event.failures();
            if (jdbc.update("UPDATE outbox_original_recovery_request SET outcome=?,previous_failures=? WHERE request_id=? AND registration_token=? AND outcome='REGISTERING'",
                    outcome.name(), previous, request.requestId().toString(), token) != 1) throw conflict("恢复回执登记失败");
            return new Receipt(request.requestId(), request.eventId(), request.operatorId(), request.traceId(), outcome,
                    consumed ? OptionalInt.empty() : OptionalInt.of(event.failures()));
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 当前读并锁定已登记请求，复核并发插入结果。
     * @param requestId 幂等请求标识
     * @return 首次记录或空
     */
    private Stored find(UUID requestId) {
        var rows = jdbc.query("SELECT event_id,operator_id,registration_token,outcome,previous_failures,trace_id FROM outbox_original_recovery_request WHERE request_id=? FOR UPDATE",
                (rs, row) -> new Stored(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), (Integer) rs.getObject(5), rs.getString(6)), requestId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 相同请求只能重用首次事件和操作人，返回首次trace和结果。
     * @param request 当前请求
     * @param stored 首次登记记录
     * @return 稳定历史回执
     */
    private static Receipt checkIdentity(Request request, Stored stored) {
        if (!request.eventId().equals(stored.eventId()) || !request.operatorId().equals(stored.operatorId())
                || "REGISTERING".equals(stored.outcome())) throw conflict("恢复请求身份冲突或尚未完成");
        return new Receipt(request.requestId(), stored.eventId(), stored.operatorId(), stored.trace(), Outcome.valueOf(stored.outcome()),
                stored.failures() == null ? OptionalInt.empty() : OptionalInt.of(stored.failures()));
    }

    /** 写操作必须由Application边界建立事务。 */
    private static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("原事件恢复必须参与已有写工作单元");
        }
    }

    /**
     * 将可预期的锁竞争和数据库序列化失败交给调用者整体重试。
     * @param failure 持久化异常
     * @return 项目冲突异常或原异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || sql.getErrorCode() == 1205 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("原事件恢复状态正在变更"), failure);
            }
        }
        return failure;
    }

    /**
     * 创建安全冲突，不包含正文或敏感参数。
     * @param message 固定错误描述
     * @return 冲突异常
     */
    private static StratumException conflict(String message) { return new StratumException(CommonErrorCode.CONFLICT, message); }

    /**
     * 原事件资格快照。
     * @param aggregate 聚合标识
     * @param sequence 聚合序号
     * @param status 状态
     * @param failures 累计失败次数
     */
    private record Event(String aggregate, long sequence, String status, int failures) { }
    /**
     * 首次回执存储快照。
     * @param eventId 事件
     * @param operatorId 操作人
     * @param token 登记令牌
     * @param outcome 状态
     * @param failures 原失败次数
     * @param trace 首次关联标识
     */
    private record Stored(UUID eventId, UUID operatorId, String token, String outcome, Integer failures, String trace) { }
}
