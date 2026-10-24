package com.patrick.stratum.query.auth;

import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 认证内部凭据只读查询，不依赖写仓储、不记录凭据、不建立会话。 */
@Component
@RequiredArgsConstructor
public class AccountCredentialQueryHandler implements QueryHandler<AccountCredentialQuery, AccountCredentials.Snapshot> {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override
    public String queryCode() { return "auth.credential.lookup"; }

    /** {@inheritDoc} */
    @Override
    public Class<AccountCredentialQuery> queryType() { return AccountCredentialQuery.class; }

    /** {@inheritDoc} */
    @Override
    public QueryResult<AccountCredentials.Snapshot> handle(AccountCredentialQuery query, QueryContext context) {
        try {
            String username = (String) query.spec().getFilters().getFirst().value();
            var rows = database.queryable(AccountCredentialView.class).where(row -> row.username().eq(username))
                    .configure(options -> options.setPrintSQL(false))
                    .limit(1).toList().stream().map(row -> new AccountCredentials.Snapshot(UUID.fromString(row.getId()),
                            row.getUsername(), row.getPasswordHash(), row.getEnabled())).toList();
            return new QueryResult<>(1, 1, rows.size(), rows);
        } catch (EasyQueryException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }
}
