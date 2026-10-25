package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 单条只读SQL核对当前启用账号与管理员成员关系，不加载密码、缓存授权或获取写锁。 */
@Component
@RequiredArgsConstructor
public class AdministratorQueryHandler implements QueryHandler<AdministratorQuery, Boolean> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.administrator.lookup"; }

    /** {@inheritDoc} */
    @Override public Class<AdministratorQuery> queryType() { return AdministratorQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<Boolean> handle(AdministratorQuery query, QueryContext context) {
        UUID userId = (UUID) query.spec().getFilters().getFirst().value();
        String actor = context.operatorId().orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
        if (!userId.toString().equals(actor)) throw new StratumException(CommonErrorCode.FORBIDDEN);
        try {
            long count = database.queryable(AccountCredentialView.class)
                    .innerJoin(UserRoleView.class, (user, membership) -> user.id().eq(membership.userId()))
                    .innerJoin(RoleView.class, (user, membership, role) -> membership.roleId().eq(role.id()))
                    .where((user, membership, role) -> {
                        user.id().eq(userId.toString()); user.enabled().eq(true); role.code().eq(AuthorizationRole.ADMINISTRATOR_CODE);
                    })
                    .configure(options -> options.setPrintSQL(false)).select((user, membership, role) -> membership).count();
            return new QueryResult<>(1, 1, 1, List.of(count > 0));
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }
}
