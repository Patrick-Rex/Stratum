package com.patrick.stratum.domain.trade;

import com.patrick.stratum.domain.auth.PermissionCatalog;

/** 值对象权限策略：采购与销售的读取、创建及履约分别映射，动作之间不隐含授权。 */
public final class TradeOrderPermissions {
    /** 仅提供无状态领域映射。 */
    private TradeOrderPermissions() { }

    /**
     * 获取订单摘要、详情及履约历史所需的读取权限。
     * @param kind 采购或销售类别
     * @return 对应API读取条目
     * @apiNote 示例：{@code TradeOrderPermissions.read(TradeOrder.Kind.PURCHASE)}。
     */
    public static PermissionCatalog read(TradeOrder.Kind kind) {
        return switch (kind) { case PURCHASE -> PermissionCatalog.PURCHASE_READ; case SALE -> PermissionCatalog.SALES_READ; };
    }

    /**
     * 获取新订单创建所需权限，不授予履约能力。
     * @param kind 采购或销售类别
     * @return 对应API创建条目
     * @apiNote 示例：{@code TradeOrderPermissions.create(TradeOrder.Kind.SALE)}。
     */
    public static PermissionCatalog create(TradeOrder.Kind kind) {
        return switch (kind) { case PURCHASE -> PermissionCatalog.PURCHASE_CREATE; case SALE -> PermissionCatalog.SALES_CREATE; };
    }

    /**
     * 获取收货或出库所需权限，不授予订单创建能力。
     * @param kind 采购或销售类别
     * @return 对应API履约条目
     * @apiNote 示例：{@code TradeOrderPermissions.fulfill(TradeOrder.Kind.PURCHASE)}。
     */
    public static PermissionCatalog fulfill(TradeOrder.Kind kind) {
        return switch (kind) { case PURCHASE -> PermissionCatalog.PURCHASE_FULFILL; case SALE -> PermissionCatalog.SALES_FULFILL; };
    }
}
