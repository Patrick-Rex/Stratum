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

/** 单语句读取账号状态与去重权限，避免不同查询时刻混合授权状态；不读取凭据或缓存结果。 */
@Component
@RequiredArgsConstructor
public class AccountPermissionQueryHandler implements QueryHandler<AccountPermissionQuery, AccountPermissions.Snapshot> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.permission.lookup"; }

    /** {@inheritDoc} */
    @Override public Class<AccountPermissionQuery> queryType() { return AccountPermissionQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<AccountPermissions.Snapshot> handle(AccountPermissionQuery query, QueryContext context) {
        UUID userId = (UUID) query.spec().getFilters().getFirst().value();
        String operator = context.operatorId().orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
        if (!userId.toString().equals(operator)) throw new StratumException(CommonErrorCode.FORBIDDEN);
        try {
            var rows = database.queryable(AccountCredentialView.class)
                    .leftJoin(UserRoleView.class, (user, membership) -> user.id().eq(membership.userId()))
                    .leftJoin(RolePermissionView.class, (user, membership, grant) -> membership.roleId().eq(grant.roleId()))
                    .leftJoin(PermissionView.class, (user, membership, grant, permission) -> grant.permissionId().eq(permission.id()))
                    .where((user, membership, grant, permission) -> user.id().eq(userId.toString()))
                    .configure(options -> options.setPrintSQL(false))
                    .select(FlatRow.class, (user, membership, grant, permission) ->
                            Select.of(user.id(), user.enabled(), permission.code(), permission.type()))
                    .distinct().limit(AccountPermissions.Snapshot.MAX_PERMISSIONS + 2).toList();
            if (rows.isEmpty()) return new QueryResult<>(1, 1, 0, List.of());
            // 无权限角色最多贡献一个DISTINCT空行，额外读取两行才能准确识别超过1024项的结果。
            var permissions = rows.stream().filter(row -> row.getCode() != null)
                    .map(row -> new Permission(row.getCode(), Permission.Type.valueOf(row.getType()))).collect(Collectors.toSet());
            if (permissions.size() > AccountPermissions.Snapshot.MAX_PERMISSIONS) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            return new QueryResult<>(1, 1, 1, List.of(new AccountPermissions.Snapshot(userId, rows.getFirst().getEnabled(), permissions)));
        } catch (EasyQueryException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    /** ORM内部关联投影，只含账号状态和权限标识，不含用户名、散列或会话。 */
    @Getter
    @Setter
    public static class FlatRow {
        private String id;
        private Boolean enabled;
        private String code;
        private String type;
    }
}
