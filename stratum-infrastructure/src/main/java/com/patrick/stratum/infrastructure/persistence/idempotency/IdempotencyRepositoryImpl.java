package com.patrick.stratum.infrastructure.persistence.idempotency;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.idempotency.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 幂等回执适配器：使用现有事务连接与数据库复合主键，不创建独立事务。 */
@Repository
@RequiredArgsConstructor
public class IdempotencyRepositoryImpl implements IdempotencyRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public Optional<IdempotencyReceipt> find(IdempotencyReceipt.Key key) {
        return Optional.ofNullable(query.queryable(IdempotencyRecord.class)
                .where(row -> { row.scope().eq(key.scope()); row.requestKey().eq(key.value()); }).singleOrNull())
                .map(row -> new IdempotencyReceipt(key, row.getRequestDigest(), row.getResultJson()));
    }

    /** {@inheritDoc} */
    @Override
    public void append(IdempotencyReceipt receipt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("幂等回执必须在Application写工作单元内保存");
        }
        var row = new IdempotencyRecord();
        row.setScope(receipt.key().scope());
        row.setRequestKey(receipt.key().value());
        row.setRequestDigest(receipt.requestDigest());
        row.setResultJson(receipt.resultJson());
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            if (query.insertable(row).executeRows() != 1) {
                throw new IllegalStateException("幂等回执未写入");
            }
        } catch (EasyQueryException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062
                        || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                    throw new StratumException(CommonErrorCode.CONFLICT,
                            List.of("幂等请求发生竞争，请使用原键重试"), failure);
                }
            }
            throw failure;
        }
    }
}
