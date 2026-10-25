package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AccountWarehouseAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 用当前可信账号检查目标仓库，不接受外部操作者，调用方还需独立检查API权限及业务目标。 */
@Component
@RequiredArgsConstructor
public class CurrentWarehouseAuthorizationApplicationService {
    private final CurrentAuthorizationApplicationService authorization;
    private final AccountWarehouseAccess warehouses;

    /**
     * 要求当前启用账号具有目标仓库范围，每次权威读取，必须在写工作单元及幂等重放之前调用。
     * @param warehouseId 已确定的目标仓库UUID
     * @throws StratumException 当前身份失效为401、范围不允许为403，存储错误保持失败
     * @throws NullPointerException 目标仓库缺失
     * @apiNote 示例：{@code authorization.require(orderWarehouseId)}；当前仓库检查不授予订单API权限。
     */
    public void require(UUID warehouseId) {
        Objects.requireNonNull(warehouseId, "目标仓库不能为空");
        var current = authorization.current();
        String traceId = TraceIdContext.getTraceId().orElseGet(() -> UUID.randomUUID().toString());
        if (!warehouses.permits(current.userId(), warehouseId, traceId)) throw new StratumException(CommonErrorCode.FORBIDDEN);
    }
}
