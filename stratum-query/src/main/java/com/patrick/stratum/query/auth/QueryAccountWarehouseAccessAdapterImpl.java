package com.patrick.stratum.query.auth;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.AccountWarehouseAccess;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将目标仓库访问端口适配到固定查询及可信主体上下文，不缓存授权或枚举仓库集合。 */
@Component
@RequiredArgsConstructor
public class QueryAccountWarehouseAccessAdapterImpl implements AccountWarehouseAccess {
    private final QueryGateway gateway;

    /** {@inheritDoc} */
    @Override public boolean permits(UUID userId, UUID warehouseId, String traceId) {
        var query = WarehouseAccessQuery.create(userId, warehouseId);
        var context = new QueryContext.Snapshot(traceId, Optional.of(userId.toString()), Set.of());
        return gateway.execute(query, context).getItems().stream().findFirst().orElse(false);
    }
}
