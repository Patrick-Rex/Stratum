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
    private static final String REPLAY_READY = """
            ((r.status = 'PENDING' AND r.next_retry_at <= UTC_TIMESTAMP(6))
            OR (r.status = 'PROCESSING' AND r.lease_until <= UTC_TIMESTAMP(6)))
            """;
    private static final String REPLAY_SOURCE = """
            e.status IN ('SENT', 'FAILED') AND r.aggregate_id = e.aggregate_id AND r.event_sequence = e.event_sequence
            AND ((r.consumer_group = 'ERP_AUDIT' AND e.event_type LIKE 'erp.%')
                OR (r.consumer_group = 'CACHE_INVALIDATION' AND e.event_type = 'stratum.cache.invalidate'))
            """;
    private final JdbcTemplate jdbc;

    /** {@inheritDoc} */
    @Override
    public List<Claim> claim(ClaimRequest request) {
        requireWriteUnit();
        Objects.requireNonNull(request, "领取请求不能为空");
        try {
            int candidateLimit = request.batchSize() * 4;
            var candidates = jdbc.queryForList("""
                    SELECT c.aggregate_id FROM (
                    (SELECT p.aggregate_id, e.event_id, e.next_retry_at AS ready_at
                    FROM outbox_aggregate_progress p
                    JOIN outbox_event e ON e.aggregate_id = p.aggregate_id
                      AND e.event_sequence = CASE WHEN p.consumed_sequence < p.last_sequence
                          THEN p.consumed_sequence + 1 ELSE NULL END
                    WHERE
                    """ + READY + " ORDER BY e.next_retry_at, e.event_id LIMIT ?) UNION ALL ("
                    + """
                    SELECT p.aggregate_id, e.event_id, r.next_retry_at AS ready_at
                    FROM outbox_replay_delivery r JOIN outbox_event e ON e.event_id = r.event_id
                    JOIN outbox_aggregate_progress p ON p.aggregate_id = e.aggregate_id
                    WHERE e.event_sequence = CASE WHEN p.consumed_sequence < p.last_sequence
                        THEN p.consumed_sequence + 1 ELSE NULL END AND
                    """ + REPLAY_SOURCE + " AND " + REPLAY_READY
                    + " ORDER BY r.next_retry_at, r.delivery_id LIMIT ?)) c ORDER BY c.ready_at, c.event_id LIMIT ?",
                    String.class, candidateLimit, candidateLimit, candidateLimit);
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
                if (heads.isEmpty()) {
                    var replay = claimReplay(aggregate, sequence, request);
                    if (replay != null) claims.add(replay);
                    continue;
                }
                var head = heads.getFirst();
                UUID token = UUID.randomUUID();
                LocalDateTime expires = head.databaseNow().plusNanos(request.leaseDuration().toNanos());
                int changed = jdbc.update("""
                        UPDATE outbox_event SET status = 'PROCESSING', lease_token = ?, lease_until = ?,
                            version = CASE WHEN version < 9223372036854775807 THEN version + 1 ELSE version END
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
        boolean definite = result == OutboxPublisher.PublishResult.RETURNED
                || result == OutboxPublisher.PublishResult.INVALID_ENVELOPE;
        String target = confirmed ? "SENT" : definite && failures >= retryPolicy.maxFailures() ? "FAILED" : "PENDING";
        long delayMicros = target.equals("PENDING") ? retryPolicy.delayForFailure(failures).toNanos() / 1000 : 0;
        var delivery = claim.delivery();
        try {
            var locked = jdbc.queryForList("""
                    SELECT event_id FROM outbox_event
                    WHERE event_id = ? AND status = 'PROCESSING' AND lease_token = ?
                    FOR UPDATE SKIP LOCKED
                    """, String.class, delivery.message().eventId(), claim.token().toString());
            if (locked.isEmpty()) {
                String replayTarget = confirmed ? "SENT" : failures >= retryPolicy.maxFailures() ? "FAILED" : "PENDING";
                long replayDelayMicros = replayTarget.equals("PENDING") ? retryPolicy.delayForFailure(failures).toNanos() / 1000 : 0;
                return finishReplay(claim, replayTarget, failures, replayDelayMicros);
            }
            return jdbc.update("""
                    UPDATE outbox_event SET status = ?, retry_count = ?,
                        next_retry_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                        lease_token = NULL, lease_until = NULL,
                        version = CASE WHEN version < 9223372036854775807 THEN version + 1 ELSE version END
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
     * 已持有聚合进度锁时领取独立重放意图，读取本地原信封，不使用归档正文发送。
     * @param aggregate 当前聚合
     * @param sequence 只允许的头序号
     * @param request 有界租期
     * @return 实际领取或空
     * @throws org.springframework.dao.DataAccessException JDBC读取失败
     */
    private Claim claimReplay(String aggregate, long sequence, ClaimRequest request) {
        var rows = jdbc.query("""
                SELECT e.*, r.delivery_id, r.retry_count AS replay_retry_count, UTC_TIMESTAMP(6) AS database_now
                FROM outbox_event e JOIN outbox_replay_delivery r ON r.event_id = e.event_id
                WHERE e.aggregate_id = ? AND e.event_sequence = ? AND
                """ + REPLAY_SOURCE + " AND " + REPLAY_READY
                + " ORDER BY r.next_retry_at, r.delivery_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (row, number) -> new ReplayHead(mapHead(row, number), row.getString("delivery_id"), row.getInt("replay_retry_count")),
                aggregate, sequence);
        if (rows.isEmpty()) return null;
        var replay = rows.getFirst(); UUID token = UUID.randomUUID();
        LocalDateTime expires = replay.head().databaseNow().plusNanos(request.leaseDuration().toNanos());
        int changed = jdbc.update("""
                UPDATE outbox_replay_delivery SET status = 'PROCESSING', lease_token = ?, lease_until = ?, version = version + 1
                WHERE delivery_id = ?
                """, token.toString(), expires, replay.id());
        if (changed != 1) throw new StratumException(CommonErrorCode.CONFLICT);
        return new Claim(replay.head().delivery(), token, expires.toInstant(ZoneOffset.UTC), replay.failures());
    }

    /**
     * 按重放意图竞争令牌登记发送结果；忙行、旧令牌或过期均不覆盖新状态。
     * @param claim 原领取
     * @param target 本次发布结果对应状态
     * @param failures 饱和失败计数
     * @param delayMicros 有界退避微秒数
     * @return 是否完成实际意图更新
     */
    private boolean finishReplay(Claim claim, String target, int failures, long delayMicros) {
        var event = claim.delivery().message();
        var ids = jdbc.queryForList("""
                SELECT delivery_id FROM outbox_replay_delivery
                WHERE event_id = ? AND status = 'PROCESSING' AND lease_token = ? FOR UPDATE SKIP LOCKED
                """, String.class, event.eventId(), claim.token().toString());
        if (ids.isEmpty()) return false;
        return jdbc.update("""
                UPDATE outbox_replay_delivery SET status = ?, retry_count = ?,
                    next_retry_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)),
                    sent_at = CASE WHEN ? = 'SENT' THEN UTC_TIMESTAMP(6) ELSE sent_at END,
                    lease_token = NULL, lease_until = NULL, version = version + 1
                WHERE delivery_id = ? AND event_id = ? AND aggregate_id = ? AND event_sequence = ?
                    AND status = 'PROCESSING' AND lease_token = ? AND retry_count = ? AND lease_until > UTC_TIMESTAMP(6)
                """, target, failures, delayMicros, target, ids.getFirst(), event.eventId(), event.aggregateId(),
                claim.delivery().sequence(), claim.token().toString(), claim.retryCount()) == 1;
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

    /**
     * 独立意图的有界领取快照。
     * @param head 原事件和数据库时刻
     * @param id 独立意图UUID
     * @param failures 意图自身失败次数
     */
    private record ReplayHead(Head head, String id, int failures) { }
}
