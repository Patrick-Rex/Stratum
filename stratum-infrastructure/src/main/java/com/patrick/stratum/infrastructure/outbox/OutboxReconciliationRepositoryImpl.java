package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxReconciliationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在已有工作单元中以数据库游标串行扫描一条凭据，严格复核后收敛未确认原事件。 */
@Repository
@RequiredArgsConstructor
public class OutboxReconciliationRepositoryImpl implements OutboxReconciliationRepository {
    private final JdbcTemplate jdbc;

    /** {@inheritDoc} */
    @Override
    public ScanOutcome scanOne() {
        requireWrite();
        try {
            var rows = jdbc.query("""
                    SELECT consumed_at, event_id, consumer_group, cycle_upper_bound
                    FROM outbox_reconciliation_cursor WHERE cursor_id=1 FOR UPDATE SKIP LOCKED
                    """, (row, number) -> new Position(row.getObject(1, LocalDateTime.class), row.getString(2),
                            row.getString(3), row.getObject(4, LocalDateTime.class)));
            if (rows.isEmpty()) {
                Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_reconciliation_cursor WHERE cursor_id=1", Integer.class);
                if (present == null || present != 1) throw new IllegalStateException("对账游标不存在");
                return ScanOutcome.BUSY;
            }
            Position position = rows.getFirst();
            LocalDateTime upper = position.upper() == null
                    ? jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class) : position.upper();
            if (upper == null) throw new IllegalStateException("数据库时钟不可用");
            var next = nextReceipt(position, upper);
            if (next.isEmpty()) {
                jdbc.update("UPDATE outbox_reconciliation_cursor SET consumed_at=NULL, event_id=NULL, consumer_group=NULL, cycle_upper_bound=NULL WHERE cursor_id=1");
                return ScanOutcome.CYCLE_COMPLETE;
            }
            Receipt hint = next.getFirst();
            boolean repaired = reconcile(hint);
            if (jdbc.update("""
                    UPDATE outbox_reconciliation_cursor SET consumed_at=?, event_id=?, consumer_group=?, cycle_upper_bound=? WHERE cursor_id=1
                    """, hint.consumedAt(), hint.eventId(), hint.group(), upper) != 1) throw new IllegalStateException("对账游标更新失败");
            return repaired ? ScanOutcome.RECONCILED : ScanOutcome.SCANNED;
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 按复合索引获取一条凭据提示，不过滤业务状态，跳过项也必须推动游标。
     * @param position 上次持久位置或全空起点
     * @param upper 本轮固定数据库UTC上界
     * @return 至多一条候选
     */
    private List<Receipt> nextReceipt(Position position, LocalDateTime upper) {
        String projection = "SELECT consumed_at,event_id,consumer_group,aggregate_id,event_sequence FROM outbox_consumption ";
        String suffix = " ORDER BY consumed_at,event_id,consumer_group LIMIT 1";
        if (position.consumedAt() == null) {
            return jdbc.query(projection + "WHERE consumed_at<=?" + suffix, OutboxReconciliationRepositoryImpl::mapReceipt, upper);
        }
        return jdbc.query(projection + """
                WHERE consumed_at<=? AND (consumed_at>? OR (consumed_at=? AND event_id>?)
                    OR (consumed_at=? AND event_id=? AND consumer_group>?))
                """ + suffix, OutboxReconciliationRepositoryImpl::mapReceipt,
                upper, position.consumedAt(), position.consumedAt(), position.eventId(),
                position.consumedAt(), position.eventId(), position.group());
    }

    /**
     * 依次锁定聚合进度、原事件和消费凭据，实际库时重新核验租约及完整身份。
     * @param hint 非权威的索引扫描结果
     * @return 真实凭据支持且本次成功收敛时为true
     */
    private boolean reconcile(Receipt hint) {
        var progress = jdbc.query("""
                SELECT consumed_sequence FROM outbox_aggregate_progress
                WHERE aggregate_id=? FOR UPDATE SKIP LOCKED
                """, (row, number) -> row.getLong(1), hint.aggregateId());
        if (progress.isEmpty() || progress.getFirst() < hint.sequence()) return false;
        var events = jdbc.queryForList("""
                SELECT event_id FROM outbox_event
                WHERE event_id=? AND BINARY aggregate_id=BINARY ? AND event_sequence=?
                  AND (status IN ('PENDING','FAILED') OR (status='PROCESSING' AND lease_until<=UTC_TIMESTAMP(6)))
                FOR UPDATE SKIP LOCKED
                """, String.class, hint.eventId(), hint.aggregateId(), hint.sequence());
        if (events.isEmpty()) return false;
        var receipts = jdbc.queryForList("""
                SELECT c.event_id FROM outbox_consumption c JOIN outbox_event e ON c.event_id=e.event_id
                WHERE c.event_id=? AND c.consumer_group=? AND c.consumed_at=?
                  AND BINARY c.aggregate_id=BINARY e.aggregate_id AND c.event_sequence=e.event_sequence
                  AND BINARY c.event_type=BINARY e.event_type AND c.occurred_at=e.occurred_at
                  AND BINARY c.payload=BINARY e.payload AND BINARY c.trace_id=BINARY e.trace_id
                  AND ((BINARY c.consumer_group=BINARY 'ERP_AUDIT' AND BINARY e.event_type LIKE BINARY 'erp.%')
                    OR (BINARY c.consumer_group=BINARY 'CACHE_INVALIDATION' AND BINARY e.event_type=BINARY 'stratum.cache.invalidate'))
                FOR UPDATE SKIP LOCKED
                """, String.class, hint.eventId(), hint.group(), hint.consumedAt());
        if (receipts.isEmpty()) return false;
        return jdbc.update("""
                UPDATE outbox_event SET consumed_from_status=status, status='CONSUMED', lease_token=NULL, lease_until=NULL,
                    version=CASE WHEN version<9223372036854775807 THEN version+1 ELSE version END
                WHERE event_id=? AND (status IN ('PENDING','FAILED') OR (status='PROCESSING' AND lease_until<=UTC_TIMESTAMP(6)))
                """, hint.eventId()) == 1;
    }

    /**
     * 映射不含正文或敏感信息的单条凭据索引位置。
     * @param row 当前JDBC行
     * @param number Spring行序号
     * @return 索引提示
     * @throws SQLException JDBC字段读取失败
     */
    private static Receipt mapReceipt(java.sql.ResultSet row, int number) throws SQLException {
        return new Receipt(row.getObject(1, LocalDateTime.class), row.getString(2), row.getString(3), row.getString(4), row.getLong(5));
    }

    /**
     * 保留现有数据库快照冲突口径，要求调用方回滚整个工作单元后重试。
     * @param failure JDBC失败
     * @return 冲突或原异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("对账状态已变更"), failure);
            }
        }
        return failure;
    }

    /** 确保事务由Application建立并允许写入。 */
    private static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("对账必须参与已有写工作单元");
        }
    }

    /**
     * 数据库游标快照；起点时四项为空。
     * @param consumedAt 上次消费时间
     * @param eventId 上次事件
     * @param group 上次消费组
     * @param upper 本轮固定UTC上界
     */
    private record Position(LocalDateTime consumedAt, String eventId, String group, LocalDateTime upper) { }

    /**
     * 只用于定位的凭据提示；必须在取得全部当前锁后重新核验。
     * @param consumedAt 数据库消费时间
     * @param eventId 原事件
     * @param group 消费组
     * @param aggregateId 聚合提示
     * @param sequence 消费序号提示
     */
    private record Receipt(LocalDateTime consumedAt, String eventId, String group, String aggregateId, long sequence) { }
}
