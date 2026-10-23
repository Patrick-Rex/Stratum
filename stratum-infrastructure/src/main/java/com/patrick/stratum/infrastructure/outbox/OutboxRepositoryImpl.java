package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.domain.event.OutboxStatus;

import lombok.RequiredArgsConstructor;

/** Outbox 落盘适配器：共用 Spring 事务连接，唯一事件 ID 拒绝覆盖或重复登记。 */
@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public void append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox 必须在 Application 工作单元内写入");
        }
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var row = new OutboxRecord();
        row.setEventId(message.eventId());
        row.setEventType(message.eventType());
        row.setAggregateId(message.aggregateId());
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
}
