package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 按服务器当前身份逐次读取权威权限，供接口方法在业务查询、幂等重放与写事务前检查。 */
@Component
@RequiredArgsConstructor
public class CurrentAuthorizationApplicationService {
    private final CurrentIdentity identities;
    private final AccountPermissions permissions;

    /**
     * 读取当前已认证且仍启用账号的有界权限快照，不缓存角色或授权结论。
     * @return 当前账号的不可变权限快照
     * @throws StratumException 身份缺失、账号停用或不存在为401，权威读取失败保持失败
     * @apiNote 示例：{@code authorization.current().codes(Permission.Type.FEAT)}用于本人功能展示。
     */
    public AccountPermissions.Snapshot current() {
        var identity = identities.require();
        String traceId = TraceIdContext.getTraceId().orElseGet(() -> UUID.randomUUID().toString());
        return permissions.findByUserId(identity.userId(), traceId)
                .filter(snapshot -> snapshot.userId().equals(identity.userId()) && snapshot.enabled())
                .orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
    }

    /**
     * 要求当前账号具有后端目录指定的精确API权限；功能权限不能替代API授权。
     * @param required 服务端固定目录条目，不能从客户端输入构造
     * @return 本次通过检查的权限快照，不表示已通过数据范围校验
     * @throws IllegalArgumentException 调用方错误地使用FEAT目录条目
     * @throws StratumException 已认证但缺少权限为403，身份失效为401，读取失败不得放行
     * @apiNote 示例：{@code authorization.requireApi(PermissionCatalog.INVENTORY_READ)}。
     */
    public AccountPermissions.Snapshot requireApi(PermissionCatalog required) {
        Objects.requireNonNull(required, "权限目录条目不能为空");
        if (required.permission().type() != Permission.Type.API) throw new IllegalArgumentException("API鉴权不能使用功能权限");
        var snapshot = current();
        if (!snapshot.permits(required.permission())) throw new StratumException(CommonErrorCode.FORBIDDEN);
        return snapshot;
    }
}
