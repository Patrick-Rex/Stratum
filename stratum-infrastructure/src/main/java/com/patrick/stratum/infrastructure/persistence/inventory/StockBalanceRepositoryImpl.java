package com.patrick.stratum.infrastructure.persistence.inventory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 库存余额仓储：精确小数落盘、唯一维度、类别外键及乐观锁共同保护写入。 */
@Repository
@RequiredArgsConstructor
public class StockBalanceRepositoryImpl implements StockBalanceRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public Optional<StockBalance> find(UUID materialId, UUID warehouseId) {
        return Optional.ofNullable(query.queryable(StockBalanceRecord.class)
                .where(row -> {
                    row.materialId().eq(materialId.toString());
                    row.warehouseId().eq(warehouseId.toString());
                })
                .singleOrNull()).map(row -> StockBalance.restore(UUID.fromString(row.getId()),
                        UUID.fromString(row.getMaterialId()), UUID.fromString(row.getWarehouseId()),
                        new Quantity(row.getQuantity()), row.getVersion()));
    }

    /** {@inheritDoc} */
    @Override
    public void save(StockBalance balance) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("库存写入必须在Application工作单元中执行");
        }
        var row = new StockBalanceRecord();
        row.setId(balance.getId().toString());
        row.setMaterialId(balance.getMaterialId().toString());
        row.setMaterialKind("MATERIAL");
        row.setWarehouseId(balance.getWarehouseId().toString());
        row.setWarehouseKind("WAREHOUSE");
        row.setQuantity(balance.getQuantity().value());
        row.setVersion(balance.getVersion());
        long next = balance.getVersion() == null ? 0 : Math.incrementExact(balance.getVersion());
        try {
            long affected;
            if (balance.getVersion() == null) {
                row.setVersion(0L);
                affected = query.insertable(row).executeRows();
            } else {
                affected = query.updatable(row).executeRows();
            }
            if (affected != 1) throw new StratumException(CommonErrorCode.CONFLICT);
            balance.markPersisted(next);
        } catch (EasyQueryException failure) {
            throw translate(failure);
        }
    }

    /**
     * 将数据库并发、唯一及引用冲突映射为安全错误。
     * @param failure ORM异常
     * @return 业务冲突或原始异常
     */
    private RuntimeException translate(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) {
            return new StratumException(CommonErrorCode.CONFLICT, List.of("库存版本已变更"), failure);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                    || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("库存维度、主数据引用或版本发生冲突"), failure);
            }
        }
        return failure;
    }
}
