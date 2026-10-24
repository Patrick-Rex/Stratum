package com.patrick.stratum.query.auth;

import java.util.Optional;

import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将领域凭据读取端口适配到查询网关，供Application认证编排使用。 */
@Component
@RequiredArgsConstructor
public class QueryAccountCredentialsAdapterImpl implements AccountCredentials {
    private final QueryGateway gateway;

    /** {@inheritDoc} */
    @Override
    public Optional<Snapshot> findByUsername(String username, String traceId) {
        return gateway.execute(AccountCredentialQuery.create(username), QueryContext.anonymous(traceId))
                .getItems().stream().findFirst();
    }
}
