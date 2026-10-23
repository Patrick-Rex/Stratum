package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import com.patrick.stratum.common.enums.SortDirection;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

/** 先分页不可变履约头，再批量读取当前页明细，避免按凭据逐条回源。 */
@Component
@RequiredArgsConstructor
public class FulfillmentQueryHandler implements QueryHandler<FulfillmentQuery, FulfillmentQuery.Row> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "erp.fulfillment.list"; }

    /** {@inheritDoc} */
    @Override
    public Class<FulfillmentQuery> queryType() { return FulfillmentQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<FulfillmentQuery.Row> handle(FulfillmentQuery query, QueryContext context) {
        var selection = database.queryable(FulfillmentView.class).where(row -> {
            row.orderId().eq(query.getOrderId().toString()); row.kind().eq(query.getKind().name());
        });
        for (var filter : query.spec().getFilters()) selection.where(row -> {
            switch (filter.field()) {
                case ID -> row.id().eq(filter.value().toString());
                case ORDER_VERSION -> {
                    if (filter.operator() == QuerySpec.Operator.EQ) row.orderVersion().eq((Long) filter.value());
                    else { row.orderVersion().ge((Long) filter.value()); row.orderVersion().le((Long) filter.upper()); }
                }
                case CREATED_AT -> {
                    var lower = LocalDateTime.ofInstant((Instant) filter.value(), ZoneOffset.UTC);
                    if (filter.operator() == QuerySpec.Operator.EQ) row.createdAt().eq(lower);
                    else { row.createdAt().ge(lower); row.createdAt().le(LocalDateTime.ofInstant((Instant) filter.upper(), ZoneOffset.UTC)); }
                }
            }
        });
        for (var sort : query.spec().getSorts()) selection.orderBy(row -> {
            boolean ascending = sort.direction() == SortDirection.ASC;
            switch (sort.field()) {
                case ID -> row.id().orderBy(ascending); case ORDER_VERSION -> row.orderVersion().orderBy(ascending); case CREATED_AT -> row.createdAt().orderBy(ascending);
            }
        });
        if (query.spec().getSorts().isEmpty()) selection.orderBy(row -> row.orderVersion().desc());
        if (query.spec().getSorts().stream().noneMatch(sort -> sort.field() == FulfillmentQuery.Field.ID)) selection.orderBy(row -> row.id().asc());
        var page = query.spec().getPageRequest();
        var result = selection.toPageResult(page.getPage(), page.getPageSize());
        if (result.getData().isEmpty()) return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), List.of());
        var ids = result.getData().stream().map(FulfillmentView::getId).toList();
        var details = readLines(query.getOrderId(), ids);
        var rows = result.getData().stream().map(row -> new FulfillmentQuery.Row(UUID.fromString(row.getId()),
                UUID.fromString(row.getOrderId()), TradeOrder.Kind.valueOf(row.getKind()), row.getOrderVersion(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC), details.getOrDefault(row.getId(), List.of()))).toList();
        return new QueryResult<>(page.getPage(), page.getPageSize(), result.getTotal(), rows);
    }

    /**
     * 一次性读取当前页的明细，SQL及结果数量均有界；只读不可变来源字段。
     * @param orderId 固定来源订单
     * @param ids 当前页履约标识，最多200个
     * @return 按履约分组的明细
     */
    private HashMap<String, List<FulfillmentQuery.Line>> readLines(UUID orderId, List<String> ids) {
        int maximum = ids.size() * TradeOrder.MAX_LINES;
        var lines = database.queryable(FulfillmentLineView.class)
                .leftJoin(OrderLineView.class, (posting, line) -> { posting.orderLineId().eq(line.id()); posting.orderId().eq(line.orderId()); })
                .where((posting, line) -> { posting.postingId().in(ids); posting.orderId().eq(orderId.toString()); })
                .orderBy((posting, line) -> { posting.postingId().asc(); line.lineNumber().asc(); })
                .select(LineProjection.class, (posting, line) -> Select.of(posting.FETCHER.allFields(), line.lineNumber(), line.materialId()))
                .limit(maximum + 1).toList();
        if (lines.size() > maximum) throw new IllegalStateException("履约明细超出当前页上限");
        var grouped = new HashMap<String, List<FulfillmentQuery.Line>>();
        for (var line : lines) grouped.computeIfAbsent(line.getPostingId(), ignored -> new ArrayList<>()).add(
                new FulfillmentQuery.Line(UUID.fromString(line.getOrderLineId()), line.getLineNumber(), UUID.fromString(line.getMaterialId()), line.getQuantity()));
        return grouped;
    }

    /** 内部关联投影，只包含履约固定数量及订单固定行信息。 */
    @Getter
    @Setter
    public static class LineProjection {
        private String postingId;
        private String orderLineId;
        private String orderId;
        private BigDecimal quantity;
        private Integer lineNumber;
        private String materialId;
    }
}
