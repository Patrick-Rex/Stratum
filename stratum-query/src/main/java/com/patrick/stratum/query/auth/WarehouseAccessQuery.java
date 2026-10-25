package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;

/** 内部目标仓库访问查询，只检查当前账号范围，不提供HTTP入口或替代业务目标存在性检查。 */
public final class WarehouseAccessQuery implements Query<Boolean> {
    /** 固定当前账号和目标仓库，不开放任意客户端过滤表达式。 */
    public enum Field { USER_ID, WAREHOUSE_ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of(
            "userId", new QuerySpec.Field<>(Field.USER_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false),
            "warehouseId", new QuerySpec.Field<>(Field.WAREHOUSE_ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存唯一目标和当前主体的查询规范。
     * @param spec 固定分页及条件
     */
    private WarehouseAccessQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 创建一条布尔结果查询，操作者必须与userId相同。
     * @param userId 服务器已验证账号UUID
     * @param warehouseId 待校验目标仓库UUID
     * @return 内部固定查询
     * @apiNote 示例：{@code WarehouseAccessQuery.create(identity.userId(), orderWarehouseId)}交给QueryGateway。
     */
    public static WarehouseAccessQuery create(UUID userId, UUID warehouseId) {
        return new WarehouseAccessQuery(QuerySpec.create(1, 1, List.of(), List.of(
                new QuerySpec.FilterInput("userId", QuerySpec.Operator.EQ, Objects.requireNonNull(userId), null),
                new QuerySpec.FilterInput("warehouseId", QuerySpec.Operator.EQ, Objects.requireNonNull(warehouseId), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }
}
