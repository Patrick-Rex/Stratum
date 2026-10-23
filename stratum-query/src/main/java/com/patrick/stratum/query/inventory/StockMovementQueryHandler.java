package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.proxy.columns.types.SQLStringTypeColumn;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.inventory.proxy.StockMovementViewProxy;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 读取库存历史流水，以UTC转换时间参数并保留来源和精确数量。 */
@Component
@RequiredArgsConstructor
public class StockMovementQueryHandler implements QueryHandler<StockMovementQuery, StockMovementQuery.Row> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "erp.stock.movement.list"; }

    /** {@inheritDoc} */
    @Override
    public Class<StockMovementQuery> queryType() { return StockMovementQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<StockMovementQuery.Row> handle(StockMovementQuery query, QueryContext context) {
        var selection = database.queryable(StockMovementView.class);
        for (var filter : query.spec().getFilters()) selection.where(row -> filter(row, filter));
        for (var sort : query.spec().getSorts()) selection.orderBy(row -> {
            boolean ascending = sort.direction() == SortDirection.ASC;
            switch (sort.field()) {
                case ID -> row.id().orderBy(ascending);
                case QUANTITY -> row.quantity().orderBy(ascending);
                case OCCURRED_AT -> row.occurredAt().orderBy(ascending);
                default -> throw new IllegalArgumentException("流水排序字段不允许");
            }
        });
        if (query.spec().getSorts().isEmpty()) selection.orderBy(row -> row.occurredAt().desc());
        if (query.spec().getSorts().stream().noneMatch(sort -> sort.field() == StockMovementQuery.Field.ID)) selection.orderBy(row -> row.id().asc());
        var page = query.spec().getPageRequest();
        var result = selection.toPageResult(page.getPage(), page.getPageSize());
        var rows = result.getData().stream().map(row -> new StockMovementQuery.Row(UUID.fromString(row.getId()),
                UUID.fromString(row.getBalanceId()), UUID.fromString(row.getOrderId()), UUID.fromString(row.getPostingId()),
                UUID.fromString(row.getOrderLineId()), StockMovement.Direction.valueOf(row.getDirection()), row.getQuantity(),
                row.getBalanceAfter(), row.getBalanceVersion(), row.getOccurredAt().toInstant(ZoneOffset.UTC))).toList();
        return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), rows);
    }

    /**
     * 将白名单条件绑定至对应列，区间使用闭区间。
     * @param row 当前代理
     * @param filter 已验证过滤
     */
    private void filter(StockMovementViewProxy row, QuerySpec.Filter<StockMovementQuery.Field> filter) {
        switch (filter.field()) {
            case QUANTITY -> {
                if (filter.operator() == QuerySpec.Operator.EQ) row.quantity().eq((BigDecimal) filter.value());
                else { row.quantity().ge((BigDecimal) filter.value()); row.quantity().le((BigDecimal) filter.upper()); }
            }
            case OCCURRED_AT -> {
                var lower = LocalDateTime.ofInstant((Instant) filter.value(), ZoneOffset.UTC);
                if (filter.operator() == QuerySpec.Operator.EQ) row.occurredAt().eq(lower);
                else { row.occurredAt().ge(lower); row.occurredAt().le(LocalDateTime.ofInstant((Instant) filter.upper(), ZoneOffset.UTC)); }
            }
            default -> column(row, filter.field()).eq(filter.value().toString());
        }
    }

    /**
     * 映射来源和方向的文本列。
     * @param row ORM代理
     * @param field 固定字段
     * @return 文本列
     */
    private SQLStringTypeColumn<StockMovementViewProxy> column(StockMovementViewProxy row, StockMovementQuery.Field field) {
        return switch (field) {
            case ID -> row.id(); case BALANCE_ID -> row.balanceId(); case ORDER_ID -> row.orderId();
            case POSTING_ID -> row.postingId(); case ORDER_LINE_ID -> row.orderLineId(); case DIRECTION -> row.direction();
            default -> throw new IllegalArgumentException("非文本字段使用类型化条件");
        };
    }
}
