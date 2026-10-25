package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.easy.query.core.proxy.sql.Select;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

/** 单语句读取角色版本和最多1024项权限关系，不读取凭据，不静默截断异常超量数据。 */
@Component
@RequiredArgsConstructor
public class RolePermissionsQueryHandler implements QueryHandler<RolePermissionsQuery, RolePermissionsQuery.Row> {
    private final EasyEntityQuery database;
    private final AdministrationQueryAccess access;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.role.permissions"; }

    /** {@inheritDoc} */
    @Override public Class<RolePermissionsQuery> queryType() { return RolePermissionsQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<RolePermissionsQuery.Row> handle(RolePermissionsQuery query, QueryContext context) {
        access.require(context);
        UUID id = (UUID) query.spec().getFilters().getFirst().value();
        try {
            var rows = database.queryable(RoleView.class)
                    .leftJoin(RolePermissionView.class, (role, grant) -> role.id().eq(grant.roleId()))
                    .leftJoin(PermissionView.class, (role, grant, permission) -> grant.permissionId().eq(permission.id()))
                    .where((role, grant, permission) -> role.id().eq(id.toString()))
                    .configure(options -> options.setPrintSQL(false))
                    .select(FlatRow.class, (role, grant, permission) -> Select.of(role.id(), role.version(), permission.code(), permission.type()))
                    .limit(AccountPermissions.Snapshot.MAX_PERMISSIONS + 1).toList();
            if (rows.isEmpty()) return new QueryResult<>(1, 1, 0, List.of());
            if (rows.size() > AccountPermissions.Snapshot.MAX_PERMISSIONS) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            var permissions = rows.stream().filter(row -> row.getCode() != null)
                    .map(row -> new Permission(row.getCode(), Permission.Type.valueOf(row.getType()))).collect(Collectors.toSet());
            return new QueryResult<>(1, 1, 1, List.of(new RolePermissionsQuery.Row(id, rows.getFirst().getVersion(), permissions)));
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /** 内部关联投影，仅含角色标识、版本及精确权限。 */
    @Getter
    @Setter
    public static class FlatRow {
        private String id;
        private Long version;
        private String code;
        private String type;
    }
}
