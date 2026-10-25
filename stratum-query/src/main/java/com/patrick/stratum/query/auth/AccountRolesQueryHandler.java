package com.patrick.stratum.query.auth;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.easy.query.core.proxy.sql.Select;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

/** 单语句读取账号版本和最多64项角色关系，不读取凭据，不静默截断异常超量数据。 */
@Component
@RequiredArgsConstructor
public class AccountRolesQueryHandler implements QueryHandler<AccountRolesQuery, AccountRolesQuery.Row> {
    private final EasyEntityQuery database;
    private final AdministrationQueryAccess access;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "auth.account.roles"; }

    /** {@inheritDoc} */
    @Override public Class<AccountRolesQuery> queryType() { return AccountRolesQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<AccountRolesQuery.Row> handle(AccountRolesQuery query, QueryContext context) {
        access.require(context);
        UUID id = (UUID) query.spec().getFilters().getFirst().value();
        try {
            var rows = database.queryable(AccountManagementView.class)
                    .leftJoin(UserRoleView.class, (account, member) -> account.id().eq(member.userId()))
                    .where((account, member) -> account.id().eq(id.toString()))
                    .configure(options -> options.setPrintSQL(false))
                    .select(FlatRow.class, (account, member) -> Select.of(account.id(), account.version(), member.roleId()))
                    .limit(AuthorizationAdministrationRepository.MAX_ROLES_PER_ACCOUNT + 1).toList();
            if (rows.isEmpty()) return new QueryResult<>(1, 1, 0, List.of());
            if (rows.size() > AuthorizationAdministrationRepository.MAX_ROLES_PER_ACCOUNT) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            var roles = rows.stream().filter(row -> row.getRoleId() != null).map(row -> UUID.fromString(row.getRoleId())).collect(Collectors.toSet());
            return new QueryResult<>(1, 1, 1, List.of(new AccountRolesQuery.Row(id, rows.getFirst().getVersion(), roles)));
        } catch (EasyQueryException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /** 内部关联投影，仅含账号标识、版本及角色ID。 */
    @Getter
    @Setter
    public static class FlatRow {
        private String id;
        private Long version;
        private String roleId;
    }
}
