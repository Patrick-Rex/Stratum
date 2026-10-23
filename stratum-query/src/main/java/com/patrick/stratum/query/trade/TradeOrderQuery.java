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

/** 按采购或销售类别隔离的订单摘要查询，只暴露固定字段。 */
@Getter
public final class TradeOrderQuery implements Query<TradeOrderQuery.Row> {
    /** 订单可过滤字段，ID、单号、金额和时间允许排序。 */
    public enum Field { ID, ORDER_NUMBER, PARTY_ID, WAREHOUSE_ID, STATUS, TOTAL_AMOUNT, CREATED_AT }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "orderNumber", new QuerySpec.Field<>(Field.ORDER_NUMBER, String.class, Set.of(QuerySpec.Operator.EQ), true),
            "partyId", new QuerySpec.Field<>(Field.PARTY_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "warehouseId", new QuerySpec.Field<>(Field.WAREHOUSE_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "status", new QuerySpec.Field<>(Field.STATUS, String.class, Set.of(QuerySpec.Operator.EQ), false),
            "totalAmount", new QuerySpec.Field<>(Field.TOTAL_AMOUNT, BigDecimal.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true),
            "createdAt", new QuerySpec.Field<>(Field.CREATED_AT, Instant.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true));
    private final TradeOrder.Kind kind;
    private final QuerySpec<Field> spec;

    /**
     * 保存固定类别和已验证的条件。
     * @param kind 采购或销售
     * @param spec 查询规范
     */
    private TradeOrderQuery(TradeOrder.Kind kind, QuerySpec<Field> spec) { this.kind = Objects.requireNonNull(kind, "kind"); this.spec = spec; }

    /**
     * 构造有界摘要查询，状态必须使用已定义枚举名称。
     * @param kind 类别
     * @param page 页码
     * @param pageSize 条数
     * @param sorts 排序
     * @param filters 过滤
     * @return 不可变请求
     * @apiNote 示例：TradeOrderQuery.create(Kind.PURCHASE, 1, 20, List.of("createdAt,desc"), List.of())。
     */
    public static TradeOrderQuery create(TradeOrder.Kind kind, int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        var spec = QuerySpec.create(page, pageSize, sorts, filters, FIELDS);
        for (var filter : spec.getFilters()) if (filter.field() == Field.STATUS) TradeOrder.Status.valueOf((String) filter.value());
        return new TradeOrderQuery(kind, spec);
    }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变订单摘要，不包含明细，避免列表批量加载子项。
     * @param id 订单标识
     * @param kind 类别
     * @param orderNumber 自动单号
     * @param partyId 供应商或客户
     * @param warehouseId 固定仓库
     * @param totalAmount 已保存人民币总额
     * @param status 当前状态
     * @param createdAt UTC创建时间
     * @param version 当前版本
     */
    public record Row(UUID id, TradeOrder.Kind kind, String orderNumber, UUID partyId, UUID warehouseId,
            BigDecimal totalAmount, TradeOrder.Status status, Instant createdAt, long version) { }
}
