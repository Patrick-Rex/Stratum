package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.easy.query.core.proxy.columns.types.SQLStringTypeColumn;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.auth.WarehouseScopePredicate;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import com.patrick.stratum.query.trade.proxy.TradeOrderViewProxy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 只读采购销售摘要处理器，分页查询不读取明细表。 */
@Component
@RequiredArgsConstructor
public class TradeOrderQueryHandler implements QueryHandler<TradeOrderQuery, TradeOrderQuery.Row> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "erp.order.list"; }

    /** {@inheritDoc} */
    @Override
    public Class<TradeOrderQuery> queryType() { return TradeOrderQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<TradeOrderQuery.Row> handle(TradeOrderQuery query, QueryContext context) {
        String actor = WarehouseScopePredicate.requireActor(context);
        try {
            var selection = database.queryable(TradeOrderView.class).asAlias("scope_order")
                    .configure(options -> options.setPrintSQL(false)).where(row -> {
                        row.kind().eq(query.getKind().name());
                        row.expression().sql(WarehouseScopePredicate.SQL, parameters -> {
                            parameters.expression(row.warehouseId()); parameters.value(actor);
                        });
                    });
            for (var filter : query.spec().getFilters()) selection.where(row -> filter(row, filter));
            for (var sort : query.spec().getSorts()) selection.orderBy(row -> {
                boolean ascending = sort.direction() == SortDirection.ASC;
                switch (sort.field()) {
                    case ID -> row.id().orderBy(ascending); case ORDER_NUMBER -> row.orderNumber().orderBy(ascending);
                    case TOTAL_AMOUNT -> row.totalAmount().orderBy(ascending); case CREATED_AT -> row.createdAt().orderBy(ascending);
                    default -> throw new IllegalArgumentException("不允许的订单排序字段");
                }
            });
            if (query.spec().getSorts().isEmpty()) selection.orderBy(row -> row.createdAt().desc());
            if (query.spec().getSorts().stream().noneMatch(sort -> sort.field() == TradeOrderQuery.Field.ID)) selection.orderBy(row -> row.id().asc());
            var page = query.spec().getPageRequest();
            var result = selection.toPageResult(page.getPage(), page.getPageSize());
            return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), result.getData().stream().map(TradeOrderQueryHandler::toRow).toList());
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /**
     * 转换保存的摘要及UTC时间，不重新执行业务金额计算。
     * @param row ORM投影
     * @return 不可变摘要
     */
    static TradeOrderQuery.Row toRow(TradeOrderView row) {
        return new TradeOrderQuery.Row(UUID.fromString(row.getId()), TradeOrder.Kind.valueOf(row.getKind()), row.getOrderNumber(),
                UUID.fromString(row.getPartyId()), UUID.fromString(row.getWarehouseId()), row.getTotalAmount(),
                TradeOrder.Status.valueOf(row.getStatus()), row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getVersion());
    }

    /**
     * 应用参数化精确或闭区间条件。
     * @param row ORM代理
     * @param filter 已验证过滤
     */
    private void filter(TradeOrderViewProxy row, QuerySpec.Filter<TradeOrderQuery.Field> filter) {
        switch (filter.field()) {
            case TOTAL_AMOUNT -> {
                if (filter.operator() == QuerySpec.Operator.EQ) row.totalAmount().eq((BigDecimal) filter.value());
                else { row.totalAmount().ge((BigDecimal) filter.value()); row.totalAmount().le((BigDecimal) filter.upper()); }
            }
            case CREATED_AT -> {
                var lower = LocalDateTime.ofInstant((Instant) filter.value(), ZoneOffset.UTC);
                if (filter.operator() == QuerySpec.Operator.EQ) row.createdAt().eq(lower);
                else { row.createdAt().ge(lower); row.createdAt().le(LocalDateTime.ofInstant((Instant) filter.upper(), ZoneOffset.UTC)); }
            }
            default -> column(row, filter.field()).eq(filter.value().toString());
        }
    }

    /**
     * 将枚举映射为固定文本列。
     * @param row ORM代理
     * @param field 字段
     * @return 文本列
     */
    private SQLStringTypeColumn<TradeOrderViewProxy> column(TradeOrderViewProxy row, TradeOrderQuery.Field field) {
        return switch (field) {
            case ID -> row.id(); case ORDER_NUMBER -> row.orderNumber(); case PARTY_ID -> row.partyId();
            case WAREHOUSE_ID -> row.warehouseId(); case STATUS -> row.status();
            default -> throw new IllegalArgumentException("非文本字段使用类型化条件");
        };
    }
}
