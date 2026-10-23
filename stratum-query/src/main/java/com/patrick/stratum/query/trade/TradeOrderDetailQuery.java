package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.Getter;

/** 单条采购销售详情请求，固定类别及UUID，仍经过QuerySpec与QueryGateway。 */
@Getter
public final class TradeOrderDetailQuery implements Query<TradeOrderDetailQuery.Row> {
    private final TradeOrder.Kind kind;
    private final UUID id;
    private final QuerySpec<TradeOrderQuery.Field> spec;

    /**
     * 建立单条分页规范，不允许详情携带任意分页或附加条件。
     * @param kind 类别
     * @param id 订单标识
     */
    private TradeOrderDetailQuery(TradeOrder.Kind kind, UUID id) {
        this.kind = Objects.requireNonNull(kind, "kind"); this.id = Objects.requireNonNull(id, "id");
        this.spec = TradeOrderQuery.create(kind, 1, 1, List.of(), List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, id, null))).spec();
    }

    /**
     * 创建详情请求；未找到或类别不匹配时返回空结果。
     * @param kind 类别
     * @param id 标识
     * @return 不可变请求
     * @apiNote 示例：TradeOrderDetailQuery.create(Kind.PURCHASE, orderId)。
     */
    public static TradeOrderDetailQuery create(TradeOrder.Kind kind, UUID id) { return new TradeOrderDetailQuery(kind, id); }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<TradeOrderQuery.Field> spec() { return spec; }

    /**
     * 同一数据库语句快照中的订单及明细。
     * @param order 订单摘要
     * @param lines 按原行号排列的明细，最多100行
     */
    public record Row(TradeOrderQuery.Row order, List<Line> lines) {
        /**
         * 冻结详情集合，防止调用方改变后续序列化结果。
         * @param order 摘要
         * @param lines 明细
         * @apiNote 示例：new Row(summary, lines)。
         */
        public Row { Objects.requireNonNull(order, "order"); lines = List.copyOf(lines); }
    }

    /**
     * 不可变订单行结果，直接返回固定数量、单价与累计履约量。
     * @param id 行标识
     * @param lineNumber 行号
     * @param materialId 物料
     * @param quantity 订单数量
     * @param unitPrice 人民币单价
     * @param fulfilled 累计履约数量
     */
    public record Line(UUID id, int lineNumber, UUID materialId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal fulfilled) { }
}
