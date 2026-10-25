package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import java.util.UUID;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.easy.query.core.proxy.columns.types.SQLStringTypeColumn;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.auth.WarehouseScopePredicate;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.inventory.proxy.StockBalanceViewProxy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 库存余额只读处理器，以生成列引用执行有界分页和精确数量过滤。 */
@Component
@RequiredArgsConstructor
public class StockBalanceQueryHandler implements QueryHandler<StockBalanceQuery, StockBalanceQuery.Row> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "erp.stock.balance.list"; }

    /** {@inheritDoc} */
    @Override
    public Class<StockBalanceQuery> queryType() { return StockBalanceQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<StockBalanceQuery.Row> handle(StockBalanceQuery query, QueryContext context) {
        String actor = WarehouseScopePredicate.requireActor(context);
        try {
            var selection = database.queryable(StockBalanceView.class).asAlias("scope_balance")
                    .configure(options -> options.setPrintSQL(false));
            selection.where(row -> row.expression().sql(WarehouseScopePredicate.SQL, parameters -> {
                parameters.expression(row.warehouseId()); parameters.value(actor);
            }));
            for (var filter : query.spec().getFilters()) selection.where(row -> {
                if (filter.field() == StockBalanceQuery.Field.QUANTITY) {
                    if (filter.operator() == com.patrick.stratum.query.spec.QuerySpec.Operator.EQ) row.quantity().eq((BigDecimal) filter.value());
                    else { row.quantity().ge((BigDecimal) filter.value()); row.quantity().le((BigDecimal) filter.upper()); }
                } else column(row, filter.field()).eq(filter.value().toString());
            });
            for (var sort : query.spec().getSorts()) selection.orderBy(row -> {
                boolean ascending = sort.direction() == SortDirection.ASC;
                if (sort.field() == StockBalanceQuery.Field.QUANTITY) row.quantity().orderBy(ascending);
                else column(row, sort.field()).orderBy(ascending);
            });
            if (query.spec().getSorts().stream().noneMatch(sort -> sort.field() == StockBalanceQuery.Field.ID)) selection.orderBy(row -> row.id().asc());
            var page = query.spec().getPageRequest();
            var result = selection.toPageResult(page.getPage(), page.getPageSize());
            var rows = result.getData().stream().map(row -> new StockBalanceQuery.Row(UUID.fromString(row.getId()),
                    UUID.fromString(row.getMaterialId()), UUID.fromString(row.getWarehouseId()), row.getQuantity(), row.getVersion())).toList();
            return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), rows);
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /**
     * 仅映射固定UUID字段，数量由独立数值路径处理。
     * @param row ORM代理
     * @param field 已验证字段
     * @return 文本UUID列
     */
    private SQLStringTypeColumn<StockBalanceViewProxy> column(StockBalanceViewProxy row, StockBalanceQuery.Field field) {
        return switch (field) {
            case ID -> row.id(); case MATERIAL_ID -> row.materialId(); case WAREHOUSE_ID -> row.warehouseId();
            case QUANTITY -> throw new IllegalArgumentException("数量使用数值列");
        };
    }
}
