package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.domain.event.OutboxStatus;

import lombok.RequiredArgsConstructor;

/** Outbox 落盘适配器：共用 Spring 事务连接，唯一事件ID拒绝覆盖或重复登记，聚合序号与事件同事务提交。 */
@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {
    private final EasyEntityQuery query;
    private final JdbcTemplate jdbc;

    /** {@inheritDoc} */
    @Override
    public void append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Outbox 必须在 Application 工作单元内写入");
        }
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var row = new OutboxRecord();
        row.setEventId(message.eventId());
        row.setEventType(message.eventType());
        row.setAggregateId(message.aggregateId());
        row.setEventSequence(allocateSequence(message.aggregateId()));
        row.setOccurredAt(LocalDateTime.ofInstant(message.occurredAt(), ZoneOffset.UTC));
        row.setPayload(message.payload());
        row.setTraceId(message.traceId());
        row.setStatus(OutboxStatus.PENDING.name());
        row.setRetryCount(0);
        row.setNextRetryAt(now);
        row.setCreatedAt(now);
        row.setVersion(0L);
        try {
            query.insertable(row).executeRows();
        } catch (EasyQueryException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    throw new StratumException(CommonErrorCode.CONFLICT, List.of("事件已登记"), failure);
                }
            }
            throw failure;
        }
    }

    /**
     * 在已有写事务中锁定聚合进度并分配下一个序号，锁持续至工作单元结束。
     * @param aggregateId 原聚合标识，不参与SQL拼接
     * @return 当前事务唯一的正序号
     * @throws IllegalStateException 序号耗尽或进度数据异常
     */
    private long allocateSequence(String aggregateId) {
        jdbc.update("INSERT INTO outbox_aggregate_progress (aggregate_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE aggregate_id = VALUES(aggregate_id)", aggregateId);
        int changed = jdbc.update("UPDATE outbox_aggregate_progress SET last_sequence = last_sequence + 1 "
                + "WHERE aggregate_id = ? AND last_sequence < ?", aggregateId, Long.MAX_VALUE);
        if (changed != 1) throw new IllegalStateException("Outbox聚合序号已经耗尽");
        Long sequence = jdbc.queryForObject("SELECT last_sequence FROM outbox_aggregate_progress "
                + "WHERE aggregate_id = ?", Long.class, aggregateId);
        if (sequence == null || sequence < 1) throw new IllegalStateException("Outbox聚合序号状态异常");
        return sequence;
    }
}
