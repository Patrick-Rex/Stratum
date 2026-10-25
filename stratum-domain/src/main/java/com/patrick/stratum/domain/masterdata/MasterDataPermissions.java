package com.patrick.stratum.domain.masterdata;

import com.patrick.stratum.domain.auth.PermissionCatalog;

/** 值对象权限策略：将四类主数据的读写操作映射到精确目录条目，不引入框架或默认放行。 */
public final class MasterDataPermissions {
    /** 仅提供无状态目录映射，不创建实例。 */
    private MasterDataPermissions() { }

    /**
     * 获取列表和详情所需权限，新增类别必须显式补齐映射。
     * @param kind 主数据类别
     * @return 对应API读取权限
     * @apiNote 示例：{@code MasterDataPermissions.read(MasterData.Kind.MATERIAL)}。
     */
    public static PermissionCatalog read(MasterData.Kind kind) {
        return switch (kind) {
            case MATERIAL -> PermissionCatalog.MATERIAL_READ;
            case CUSTOMER -> PermissionCatalog.CUSTOMER_READ;
            case SUPPLIER -> PermissionCatalog.SUPPLIER_READ;
            case WAREHOUSE -> PermissionCatalog.WAREHOUSE_READ;
        };
    }

    /**
     * 获取创建、改名和删除所需权限，不隐含读取权限。
     * @param kind 主数据类别
     * @return 对应API维护权限
     * @apiNote 示例：{@code MasterDataPermissions.write(MasterData.Kind.WAREHOUSE)}。
     */
    public static PermissionCatalog write(MasterData.Kind kind) {
        return switch (kind) {
            case MATERIAL -> PermissionCatalog.MATERIAL_WRITE;
            case CUSTOMER -> PermissionCatalog.CUSTOMER_WRITE;
            case SUPPLIER -> PermissionCatalog.SUPPLIER_WRITE;
            case WAREHOUSE -> PermissionCatalog.WAREHOUSE_WRITE;
        };
    }
}
