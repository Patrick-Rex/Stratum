package com.patrick.stratum.query.auth;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将账号权限端口适配到查询网关；调用方必须传入服务器验证的账号身份，不接受客户端自报主体。 */
@Component
@RequiredArgsConstructor
public class QueryAccountPermissionsAdapterImpl implements AccountPermissions {
    private final QueryGateway gateway;

    /** {@inheritDoc} */
    @Override
    public Optional<Snapshot> findByUserId(UUID userId, String traceId) {
        var query = AccountPermissionQuery.create(userId);
        var context = new QueryContext.Snapshot(traceId, Optional.of(userId.toString()), Set.of());
        return gateway.execute(query, context).getItems().stream().findFirst();
    }
}
