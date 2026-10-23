package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 库存余额查询，按UUID维度精确过滤，数量支持精确或闭区间。 */
public final class StockBalanceQuery implements Query<StockBalanceQuery.Row> {
    /** 可排序过滤字段。 */
    public enum Field { ID, MATERIAL_ID, WAREHOUSE_ID, QUANTITY }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "id", new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "materialId", new QuerySpec.Field<>(Field.MATERIAL_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "warehouseId", new QuerySpec.Field<>(Field.WAREHOUSE_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), true),
            "quantity", new QuerySpec.Field<>(Field.QUANTITY, BigDecimal.class, Set.of(QuerySpec.Operator.EQ, QuerySpec.Operator.BETWEEN), true));
    private final QuerySpec<Field> spec;

    /**
     * 保存已校验条件。
     * @param spec 固定白名单查询
     */
    private StockBalanceQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造有界库存查询，拒绝非法字段、操作和参数类型。
     * @param page 页码
     * @param pageSize 每页条数
     * @param sorts 排序
     * @param filters 过滤
     * @return 不可变查询
     * @apiNote 示例：StockBalanceQuery.create(1, 20, List.of("quantity,desc"), List.of())。
     */
    public static StockBalanceQuery create(int page, int pageSize, List<String> sorts, List<QuerySpec.FilterInput> filters) {
        return new StockBalanceQuery(QuerySpec.create(page, pageSize, sorts, filters, FIELDS));
    }

    /** {@inheritDoc} */
    @Override
    public QuerySpec<Field> spec() { return spec; }

    /**
     * 库存余额不可变结果，不进行浮点转换或聚合重算。
     * @param id 余额标识
     * @param materialId 物料
     * @param warehouseId 仓库
     * @param quantity 精确余额
     * @param version 当前版本
     */
    public record Row(UUID id, UUID materialId, UUID warehouseId, BigDecimal quantity, long version) { }
}
