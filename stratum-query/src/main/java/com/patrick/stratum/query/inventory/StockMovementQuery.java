package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 库存流水只读请求，限制来源、方向及数量/时间条件，不接受任意SQL属性。 */
public final class StockMovementQuery implements Query<StockMovementQuery.Row> {
    /** 流水过滤字段，其中ID、数量及发生时间允许排序。 */
    public enum Field { ID, BALANCE_ID, ORDER_ID, POSTING_ID, ORDER_LINE_ID, DIRECTION, QUANTITY, OCCURRED_AT }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "balanceId", new QuerySpec.Field<>(Field.BALANCE_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "orderId", new QuerySpec.Field<>(Field.ORDER_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "postingId", new QuerySpec.Field<>(Field.POSTING_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "orderLineId", new QuerySpec.Field<>(Field.ORDER_LINE_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "direction", new QuerySpec.Field<>(Field.DIRECTION, String.class, Set.of(QuerySpec.Operator.EQ), false),
            "quantity", new QuerySpec.Field<>(Field.QUANTITY, BigDecimal.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true),
            "occurredAt", new QuerySpec.Field<>(Field.OCCURRED_AT, Instant.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true));
    private final QuerySpec<Field> spec;

    /**
     * 保存固定白名单解析结果。
     * @param spec 已校验条件
     */
    private StockMovementQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造分页请求，方向只接受已定义枚举名称。
     * @param page 页码
     * @param pageSize 条数
     * @param sorts 排序表达
     * @param filters 过滤表达
     * @return 不可变请求
     * @apiNote 示例：StockMovementQuery.create(1, 20, List.of("occurredAt,desc"), List.of())。
     */
    public static StockMovementQuery create(int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        var spec = QuerySpec.create(page, pageSize, sorts, filters, FIELDS);
        for (var filter : spec.getFilters()) {
            if (filter.field() == Field.DIRECTION) StockMovement.Direction.valueOf((String) filter.value());
        }
        return new StockMovementQuery(spec);
    }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变历史流水，不以当前库存覆盖历史快照。
     * @param id 流水标识
     * @param balanceId 库存余额标识
     * @param orderId 订单
     * @param postingId 履约
     * @param orderLineId 订单行
     * @param direction 方向
     * @param quantity 正数量
     * @param balanceAfter 变更后余额
     * @param balanceVersion 当时余额版本
     * @param occurredAt UTC时间
     */
    public record Row(UUID id, UUID balanceId, UUID orderId, UUID postingId, UUID orderLineId,
            StockMovement.Direction direction, BigDecimal quantity, BigDecimal balanceAfter, long balanceVersion, Instant occurredAt) { }
}
