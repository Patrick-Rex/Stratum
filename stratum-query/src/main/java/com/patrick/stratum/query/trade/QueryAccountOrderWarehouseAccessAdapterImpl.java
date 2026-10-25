package com.patrick.stratum.query.trade;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.AccountOrderWarehouseAccess;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将原订单仓库访问适配至已有受范围限制的固定摘要查询，不引入无范围读取或要求读取API。 */
@Component
@RequiredArgsConstructor
public class QueryAccountOrderWarehouseAccessAdapterImpl implements AccountOrderWarehouseAccess {
    private final QueryGateway gateway;

    /** {@inheritDoc} */
    @Override public boolean permits(UUID userId, TradeOrder.Kind kind, UUID orderId, String traceId) {
        var query = TradeOrderQuery.create(kind, 1, 1, List.of(), List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, orderId, null)));
        var context = new QueryContext.Snapshot(traceId, Optional.of(userId.toString()), Set.of());
        return !gateway.execute(query, context).getItems().isEmpty();
    }
}
