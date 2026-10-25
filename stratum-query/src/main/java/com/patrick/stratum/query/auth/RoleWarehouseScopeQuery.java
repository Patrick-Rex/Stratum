package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.DataScopeType;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.query.Query;
import com.patrick.stratum.query.spec.QuerySpec;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** 当前管理员读取角色的独立仓库配置详情，同一语句返回版本、类型及完整指定集合。 */
public final class RoleWarehouseScopeQuery implements Query<RoleWarehouseScopeQuery.Row> {
    /** 固定角色目标，不开放额外客户端过滤条件。 */
    public enum Field { ID }
    private static final Map<String, QuerySpec.Field<Field>> FIELDS = Map.of("id",
            new QuerySpec.Field<>(Field.ID, UUID.class, Set.of(QuerySpec.Operator.EQ), false));
    private final QuerySpec<Field> spec;

    /**
     * 保存固定单目标规范。
     * @param spec 已验证规范
     */
    private RoleWarehouseScopeQuery(QuerySpec<Field> spec) { this.spec = spec; }

    /**
     * 构造管理员目标详情，未知角色为空，已存在空配置返回一条记录。
     * @param id 角色UUID
     * @return 固定一页一条详情的查询
     * @apiNote 示例：{@code RoleWarehouseScopeQuery.create(roleId)}交给QueryGateway。
     */
    public static RoleWarehouseScopeQuery create(UUID id) {
        return new RoleWarehouseScopeQuery(QuerySpec.create(1, 1, List.of(),
                List.of(new QuerySpec.FilterInput("id", QuerySpec.Operator.EQ, Objects.requireNonNull(id), null)), FIELDS));
    }

    /** {@inheritDoc} */
    @Override public QuerySpec<Field> spec() { return spec; }

    /**
     * 不可变完整配置，version独立于角色名称/API权限的版本。
     * @param id 角色UUID
     * @param version 仓库配置观察版本
     * @param scopeType ALL或CUSTOM
     * @param warehouseIds 完整指定仓库集合，ALL为空
     */
    public record Row(UUID id, long version,
            @Schema(implementation = String.class, allowableValues = {"ALL", "CUSTOM"}) DataScopeType scopeType,
            @ArraySchema(maxItems = WarehouseScope.MAX_WAREHOUSES, schema = @Schema(type = "string", format = "uuid")) Set<UUID> warehouseIds) {
        /**
         * 验证类型、容量和独立版本并复制集合。
         * @param id 角色UUID
         * @param version 非负观察版本
         * @param scopeType 可执行范围类型
         * @param warehouseIds 完整指定集合
         * @apiNote 示例：{@code new Row(roleId, 0, DataScopeType.CUSTOM, Set.of())}。
         */
        public Row {
            Objects.requireNonNull(id);
            if (version < 0) throw new IllegalArgumentException("仓库配置版本不能为负");
            warehouseIds = new WarehouseScope(scopeType, warehouseIds).warehouseIds();
        }
    }
}
