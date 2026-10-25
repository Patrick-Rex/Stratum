package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AccountOrderWarehouseAccess;
import com.patrick.stratum.domain.trade.TradeOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 根据当前可信启用账号和数据库原订单检查仓库范围，不接受客户端归属，不代替API授权。 */
@Component
@RequiredArgsConstructor
public class CurrentOrderWarehouseAuthorizationApplicationService {
    private final CurrentAuthorizationApplicationService authorization;
    private final AccountOrderWarehouseAccess orders;

    /**
     * 要求原订单对当前账号的仓库范围可访问，必须先于命令、幂等重放及写工作单元调用。
     * @param kind 固定订单类别
     * @param orderId 原订单UUID
     * @throws StratumException 失效身份401，缺失、类别不符或无范围目标404，存储故障保持失败
     * @throws NullPointerException 类别或订单缺失
     * @apiNote 示例：{@code authorization.require(TradeOrder.Kind.PURCHASE, orderId)}，调用方另行检查履约API。
     */
    public void require(TradeOrder.Kind kind, UUID orderId) {
        Objects.requireNonNull(kind, "订单类别不能为空"); Objects.requireNonNull(orderId, "订单标识不能为空");
        var current = authorization.current();
        String traceId = TraceIdContext.getTraceId().orElseGet(() -> UUID.randomUUID().toString());
        if (!orders.permits(current.userId(), kind, orderId, traceId)) throw new StratumException(CommonErrorCode.NOT_FOUND);
    }
}
