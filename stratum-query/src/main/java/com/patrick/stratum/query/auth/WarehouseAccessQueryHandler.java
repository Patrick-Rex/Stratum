package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 单条SQL检查当前目标仓库访问，参数绑定且只返回布尔值，不读取凭据或缓存范围。 */
@Component
@RequiredArgsConstructor
public class WarehouseAccessQueryHandler implements QueryHandler<WarehouseAccessQuery, Boolean> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.warehouse.access"; }

    /** {@inheritDoc} */
    @Override public Class<WarehouseAccessQuery> queryType() { return WarehouseAccessQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<Boolean> handle(WarehouseAccessQuery query, QueryContext context) {
        UUID userId = (UUID) query.spec().getFilters().get(0).value();
        UUID warehouseId = (UUID) query.spec().getFilters().get(1).value();
        String actor = context.operatorId().orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
        if (!userId.toString().equals(actor)) throw new StratumException(CommonErrorCode.FORBIDDEN);
        try {
            long count = database.queryable(AccountManagementView.class).where(user -> {
                user.id().eq(userId.toString());
                user.expression().sql(WarehouseScopePredicate.SQL, parameters -> {
                    parameters.value(warehouseId.toString()); parameters.value(userId.toString());
                });
            }).configure(options -> options.setPrintSQL(false)).count();
            return new QueryResult<>(1, 1, 1, List.of(count > 0));
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }
}
