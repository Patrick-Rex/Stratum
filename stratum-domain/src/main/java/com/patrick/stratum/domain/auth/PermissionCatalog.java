package com.patrick.stratum.domain.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 值对象目录：后端可授予的精确API与FEAT权限，角色初始化和运行时检查共用同一标识。 */
public enum PermissionCatalog {
    MATERIAL_READ("erp.material.read", Permission.Type.API, "查询物料"),
    MATERIAL_WRITE("erp.material.write", Permission.Type.API, "维护物料"),
    CUSTOMER_READ("erp.customer.read", Permission.Type.API, "查询客户"),
    CUSTOMER_WRITE("erp.customer.write", Permission.Type.API, "维护客户"),
    SUPPLIER_READ("erp.supplier.read", Permission.Type.API, "查询供应商"),
    SUPPLIER_WRITE("erp.supplier.write", Permission.Type.API, "维护供应商"),
    WAREHOUSE_READ("erp.warehouse.read", Permission.Type.API, "查询仓库"),
    WAREHOUSE_WRITE("erp.warehouse.write", Permission.Type.API, "维护仓库"),
    PURCHASE_READ("erp.purchase.read", Permission.Type.API, "采购查询"),
    PURCHASE_CREATE("erp.purchase.create", Permission.Type.API, "采购创建"),
    PURCHASE_FULFILL("erp.purchase.fulfill", Permission.Type.API, "采购履约"),
    SALES_READ("erp.sales.read", Permission.Type.API, "销售查询"),
    SALES_CREATE("erp.sales.create", Permission.Type.API, "销售创建"),
    SALES_FULFILL("erp.sales.fulfill", Permission.Type.API, "销售履约"),
    INVENTORY_READ("erp.inventory.read", Permission.Type.API, "查询库存"),
    ACCOUNT_MANAGE("auth.account.manage", Permission.Type.API, "管理账号"),
    ROLE_MANAGE("auth.role.manage", Permission.Type.API, "管理角色"),
    MATERIAL_FEATURE("erp.material", Permission.Type.FEAT, "物料功能"),
    CUSTOMER_FEATURE("erp.customer", Permission.Type.FEAT, "客户功能"),
    SUPPLIER_FEATURE("erp.supplier", Permission.Type.FEAT, "供应商功能"),
    WAREHOUSE_FEATURE("erp.warehouse", Permission.Type.FEAT, "仓库功能"),
    PURCHASE_FEATURE("erp.purchase", Permission.Type.FEAT, "采购功能"),
    SALES_FEATURE("erp.sales", Permission.Type.FEAT, "销售功能"),
    INVENTORY_FEATURE("erp.inventory", Permission.Type.FEAT, "库存功能"),
    ADMINISTRATION_FEATURE("auth.administration", Permission.Type.FEAT, "权限管理功能");

    private static final Set<Permission> PERMISSIONS = Arrays.stream(values()).map(PermissionCatalog::permission).collect(Collectors.toUnmodifiableSet());
    private final Permission permission;
    private final String displayName;

    /**
     * 构造固定权限目录条目。
     * @param code 权限编码
     * @param type 权限类型
     * @param displayName 展示名称
     */
    PermissionCatalog(String code, Permission.Type type, String displayName) {
        this.permission = new Permission(code, type);
        this.displayName = displayName;
    }

    /**
     * 获取精确权限标识。
     * @return 类型化权限
     * @apiNote 示例：{@code PermissionCatalog.MATERIAL_READ.permission()}。
     */
    public Permission permission() { return permission; }

    /**
     * 获取权限展示名称。
     * @return 中文名称
     * @apiNote 示例：{@code PermissionCatalog.MATERIAL_READ.displayName()}。
     */
    public String displayName() { return displayName; }

    /**
     * 获取当前已登记的全部权限，不包含通配符。
     * @return 不可变目录集合
     * @apiNote 示例：{@code PermissionCatalog.permissions()}用于管理员角色同步。
     */
    public static Set<Permission> permissions() { return PERMISSIONS; }
}
