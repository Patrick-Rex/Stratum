package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.easy.query.core.proxy.sql.Select;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.DataScopeType;
import com.patrick.stratum.domain.auth.RoleWarehouseScope;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

/** 当前管理员资格保护下，单条SQL读取角色独立配置及有界完整集合，异常存储失败关闭。 */
@Component
@RequiredArgsConstructor
public class RoleWarehouseScopeQueryHandler implements QueryHandler<RoleWarehouseScopeQuery, RoleWarehouseScopeQuery.Row> {
    private final EasyEntityQuery database;
    private final AdministrationQueryAccess access;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.role.warehouse-scope"; }

    /** {@inheritDoc} */
    @Override public Class<RoleWarehouseScopeQuery> queryType() { return RoleWarehouseScopeQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<RoleWarehouseScopeQuery.Row> handle(RoleWarehouseScopeQuery query, QueryContext context) {
        access.require(context);
        UUID id = (UUID) query.spec().getFilters().getFirst().value();
        try {
            var rows = database.queryable(RoleView.class)
                    .leftJoin(RoleWarehouseScopeView.class, (role, scope) -> role.id().eq(scope.roleId()))
                    .leftJoin(RoleWarehouseView.class, (role, scope, warehouse) -> role.id().eq(warehouse.roleId()))
                    .where((role, scope, warehouse) -> role.id().eq(id.toString()))
                    .configure(options -> options.setPrintSQL(false))
                    .select(FlatRow.class, (role, scope, warehouse) -> Select.of(role.id(), role.code(), scope.scopeType(), scope.version(), warehouse.warehouseId()))
                    .limit(WarehouseScope.MAX_WAREHOUSES + 1).toList();
            if (rows.isEmpty()) return new QueryResult<>(1, 1, 0, List.of());
            if (rows.size() > WarehouseScope.MAX_WAREHOUSES) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            var first = rows.getFirst();
            if (first.getVersion() == null || first.getScopeType() == null) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            var ids = rows.stream().filter(row -> row.getWarehouseId() != null)
                    .map(row -> UUID.fromString(row.getWarehouseId())).collect(Collectors.toSet());
            var scope = new WarehouseScope(DataScopeType.valueOf(first.getScopeType()), ids);
            var verified = RoleWarehouseScope.restore(id.toString(), first.getCode(), scope, first.getVersion());
            var result = new RoleWarehouseScopeQuery.Row(id, verified.getVersion(), scope.type(), scope.warehouseIds());
            return new QueryResult<>(1, 1, 1, List.of(result));
        } catch (EasyQueryException | IllegalArgumentException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /** 内部关联投影，仅包含角色固定身份、独立配置及目标UUID，不含凭据或账号信息。 */
    @Getter
    @Setter
    public static class FlatRow {
        private String id;
        private String code;
        private String scopeType;
        private Long version;
        private String warehouseId;
    }
}
