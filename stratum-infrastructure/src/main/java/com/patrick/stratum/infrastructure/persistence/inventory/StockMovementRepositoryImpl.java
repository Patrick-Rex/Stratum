package com.patrick.stratum.infrastructure.persistence.inventory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.inventory.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 库存流水追加适配器，通过唯一键阻止重复履约行和重复余额版本。 */
@Repository
@RequiredArgsConstructor
public class StockMovementRepositoryImpl implements StockMovementRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public void append(StockMovement movement) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("库存流水必须在Application工作单元中写入");
        }
        var row = new StockMovementRecord();
        row.setId(movement.getId().toString());
        row.setBalanceId(movement.getBalanceId().toString());
        row.setOrderId(movement.getSource().orderId().toString());
        row.setPostingId(movement.getSource().postingId().toString());
        row.setOrderLineId(movement.getSource().orderLineId().toString());
        row.setDirection(movement.getDirection().name());
        row.setQuantity(movement.getQuantity().value());
        row.setBalanceAfter(movement.getBalanceAfter().value());
        row.setBalanceVersion(movement.getBalanceVersion());
        row.setOccurredAt(LocalDateTime.ofInstant(movement.getOccurredAt(), ZoneOffset.UTC));
        try {
            if (query.insertable(row).executeRows() != 1) throw new StratumException(CommonErrorCode.CONFLICT);
        } catch (EasyQueryException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                        || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                    throw new StratumException(CommonErrorCode.CONFLICT, List.of("库存流水重复或引用发生冲突"), failure);
                }
            }
            throw failure;
        }
    }
}
