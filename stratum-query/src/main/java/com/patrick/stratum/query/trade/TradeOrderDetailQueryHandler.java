package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.query.auth.WarehouseScopePredicate;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

/** 单语句关联读取订单头与明细，避免并发履约时混合不同版本的状态和数量。 */
@Component
@RequiredArgsConstructor
public class TradeOrderDetailQueryHandler implements QueryHandler<TradeOrderDetailQuery, TradeOrderDetailQuery.Row> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "erp.order.detail"; }

    /** {@inheritDoc} */
    @Override
    public Class<TradeOrderDetailQuery> queryType() { return TradeOrderDetailQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<TradeOrderDetailQuery.Row> handle(TradeOrderDetailQuery query, QueryContext context) {
        String actor = WarehouseScopePredicate.requireActor(context);
        try {
            var rows = database.queryable(TradeOrderView.class).asAlias("scope_order_detail")
                    .configure(options -> options.setPrintSQL(false))
                    .leftJoin(OrderLineView.class, (order, line) -> order.id().eq(line.orderId()))
                    .where((order, line) -> {
                        order.id().eq(query.getId().toString()); order.kind().eq(query.getKind().name());
                        order.expression().sql(WarehouseScopePredicate.SQL, parameters -> {
                            parameters.expression(order.warehouseId()); parameters.value(actor);
                        });
                    })
                    .orderBy((order, line) -> line.lineNumber().asc())
                    .select(FlatRow.class, (order, line) -> Select.of(order.FETCHER.allFields(), line.id().as("lineId"),
                            line.lineNumber(), line.materialId(), line.ordered(), line.unitPrice(), line.fulfilled()))
                    .limit(TradeOrder.MAX_LINES + 1).toList();
            if (rows.isEmpty()) return new QueryResult<>(1, 1, 0, List.of());
            if (rows.size() > TradeOrder.MAX_LINES || rows.getFirst().getLineId() == null) throw new IllegalStateException("订单明细数量不符合存储约束");
            var head = rows.getFirst();
            var order = new TradeOrderQuery.Row(UUID.fromString(head.getId()), TradeOrder.Kind.valueOf(head.getKind()), head.getOrderNumber(),
                    UUID.fromString(head.getPartyId()), UUID.fromString(head.getWarehouseId()), head.getTotalAmount(),
                    TradeOrder.Status.valueOf(head.getStatus()), head.getCreatedAt().toInstant(ZoneOffset.UTC), head.getVersion());
            var lines = rows.stream().map(row -> new TradeOrderDetailQuery.Line(UUID.fromString(row.getLineId()), row.getLineNumber(),
                    UUID.fromString(row.getMaterialId()), row.getOrdered(), row.getUnitPrice(), row.getFulfilled())).toList();
            return new QueryResult<>(1, 1, 1, List.of(new TradeOrderDetailQuery.Row(order, lines)));
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /** ORM关联结果的内部平面投影，转换后不对外返回或缓存。 */
    @Getter
    @Setter
    public static class FlatRow {
        private String id;
        private String kind;
        private String orderNumber;
        private String partyId;
        private String warehouseId;
        private BigDecimal totalAmount;
        private String status;
        private LocalDateTime createdAt;
        private Long version;
        private String lineId;
        private Integer lineNumber;
        private String materialId;
        private BigDecimal ordered;
        private BigDecimal unitPrice;
        private BigDecimal fulfilled;
    }
}
