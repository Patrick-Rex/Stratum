package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;
import lombok.Getter;

/** 指定采购销售订单的履约历史查询，返回有界凭据分页及每批固定明细。 */
@Getter
public final class FulfillmentQuery implements Query<FulfillmentQuery.Row> {
    /** 历史过滤和排序白名单。 */
    public enum Field { ID, ORDER_VERSION, CREATED_AT }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "orderVersion", new QuerySpec.Field<>(Field.ORDER_VERSION, Long.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true),
            "createdAt", new QuerySpec.Field<>(Field.CREATED_AT, Instant.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true));
    private final TradeOrder.Kind kind;
    private final UUID orderId;
    private final QuerySpec<Field> spec;

    /**
     * 固定所属订单及类别。
     * @param kind 类别
     * @param orderId 订单
     * @param spec 已验证条件
     */
    private FulfillmentQuery(TradeOrder.Kind kind, UUID orderId, QuerySpec<Field> spec) {
        this.kind = Objects.requireNonNull(kind, "kind"); this.orderId = Objects.requireNonNull(orderId, "orderId"); this.spec = spec;
    }

    /**
     * 创建历史分页，未履约、缺失或类别不匹配均返回空历史。
     * @param kind 类别
     * @param orderId 订单
     * @param page 页码
     * @param pageSize 条数
     * @param sorts 排序
     * @param filters 过滤
     * @return 不可变请求
     * @apiNote 示例：FulfillmentQuery.create(Kind.PURCHASE, orderId, 1, 20, List.of(), List.of())。
     */
    public static FulfillmentQuery create(TradeOrder.Kind kind, UUID orderId, int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return new FulfillmentQuery(kind, orderId, QuerySpec.create(page, pageSize, sorts, filters, FIELDS));
    }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变履约历史，不跟随订单后续版本改变。
     * @param id 履约标识
     * @param orderId 来源订单
     * @param kind 类别
     * @param orderVersion 履约时订单版本
     * @param createdAt UTC时间
     * @param lines 当前批次明细
     */
    public record Row(UUID id, UUID orderId, TradeOrder.Kind kind, long orderVersion, Instant createdAt, List<Line> lines) {
        /**
         * 冻结每批有界明细。
         * @param id 履约标识
         * @param orderId 订单
         * @param kind 类别
         * @param orderVersion 当时版本
         * @param createdAt 时间
         * @param lines 明细
         * @apiNote 示例：new Row(id, orderId, kind, version, time, lines)。
         */
        public Row {
            lines = List.copyOf(lines);
            if (lines.isEmpty() || lines.size() > TradeOrder.MAX_LINES) throw new IllegalStateException("履约明细数量不符合存储约束");
        }
    }

    /**
     * 单次履约的固定数量，行号和物料来自不可修改的订单明细。
     * @param orderLineId 来源行
     * @param lineNumber 原行号
     * @param materialId 物料
     * @param quantity 本次数量
     */
    public record Line(UUID orderLineId, int lineNumber, UUID materialId, BigDecimal quantity) { }
}
