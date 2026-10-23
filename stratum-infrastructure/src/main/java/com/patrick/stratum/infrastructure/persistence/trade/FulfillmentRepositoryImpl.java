package com.patrick.stratum.infrastructure.persistence.trade;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.trade.Fulfillment;
import com.patrick.stratum.domain.trade.FulfillmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 履约追加仓储，校验已保存订单版本并依靠复合外键保护所有明细归属。 */
@Repository
@RequiredArgsConstructor
public class FulfillmentRepositoryImpl implements FulfillmentRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public void append(Fulfillment fulfillment) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("履约凭据必须在Application工作单元中写入");
        }
        var order = query.queryable(TradeOrderRecord.class).where(row -> {
            row.id().eq(fulfillment.getOrderId().toString());
            row.version().eq(fulfillment.getOrderVersion());
        }).singleOrNull();
        if (order == null) throw new StratumException(CommonErrorCode.CONFLICT, "履约订单版本尚未保存或已变更");
        var row = new FulfillmentRecord();
        row.setId(fulfillment.getId().toString()); row.setOrderId(fulfillment.getOrderId().toString());
        row.setKind(fulfillment.getKind().name()); row.setOrderVersion(fulfillment.getOrderVersion());
        row.setCreatedAt(LocalDateTime.ofInstant(fulfillment.getCreatedAt(), ZoneOffset.UTC));
        var lines = new ArrayList<FulfillmentLineRecord>(fulfillment.getLines().size());
        for (var line : fulfillment.getLines()) {
            var mapped = new FulfillmentLineRecord();
            mapped.setPostingId(row.getId()); mapped.setOrderId(row.getOrderId());
            mapped.setOrderLineId(line.orderLineId().toString()); mapped.setQuantity(line.quantity().value());
            lines.add(mapped);
        }
        try {
            if (query.insertable(row).executeRows() != 1 || query.insertable(lines).executeRows() != lines.size()) {
                throw new StratumException(CommonErrorCode.CONFLICT);
            }
        } catch (EasyQueryException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                        || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                    throw new StratumException(CommonErrorCode.CONFLICT, List.of("履约凭据重复或订单来源发生冲突"), failure);
                }
            }
            throw failure;
        }
    }
}
