package com.patrick.stratum.domain.auth;

import java.util.UUID;

import com.patrick.stratum.domain.trade.TradeOrder;

/** 原订单仓库访问端口：按真实订单类别和ID检查启用账号当前范围，不授予API权限。 */
public interface AccountOrderWarehouseAccess {
    /**
     * 判断当前账号能否访问原订单的固定仓库，不返回订单或仓库信息，不缓存授权。
     * @param userId 服务器确认的当前账号
     * @param kind 订单类别
     * @param orderId 原订单标识
     * @param traceId 服务器链路标识
     * @return 订单存在、类别匹配且当前启用账号具备仓库范围时为true，否则false
     * @throws RuntimeException 查询或存储故障保持失败，不得转换成放行
     * @apiNote 示例：{@code access.permits(userId, TradeOrder.Kind.PURCHASE, orderId, traceId)}，须在写事务外调用。
     */
    boolean permits(UUID userId, TradeOrder.Kind kind, UUID orderId, String traceId);
}
