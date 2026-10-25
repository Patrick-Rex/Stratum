package com.patrick.stratum.domain.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 值对象：仓库访问范围，只支持全部或精确指定集合；不决定授权载体、默认分配或多范围合并规则。
 * @param type ALL或CUSTOM，其他通用类型尚无仓库归属定义
 * @param warehouseIds 指定仓库集合，ALL必须为空；CUSTOM为空表示没有可访问仓库
 */
public record WarehouseScope(DataScopeType type, Set<UUID> warehouseIds) {
    public static final int MAX_WAREHOUSES = 1024;

    /**
     * 验证类型与容量并复制集合，禁止把未实现的归属类型当作全量范围。
     * @param type 可执行范围类型
     * @param warehouseIds 有界仓库UUID集合
     * @throws IllegalArgumentException 类型未支持、ALL带指定集合或集合超界
     * @throws NullPointerException 缺少类型、集合或集合含空UUID
     * @apiNote 示例：{@code new WarehouseScope(DataScopeType.CUSTOM, Set.of(warehouseId))}。
     */
    public WarehouseScope {
        Objects.requireNonNull(type, "数据范围类型不能为空");
        Objects.requireNonNull(warehouseIds, "仓库范围集合不能为空");
        if (type != DataScopeType.ALL && type != DataScopeType.CUSTOM) throw new IllegalArgumentException("仓库范围暂不支持该归属类型");
        if (type == DataScopeType.ALL && !warehouseIds.isEmpty()) throw new IllegalArgumentException("全部仓库范围不能附带指定集合");
        if (warehouseIds.size() > MAX_WAREHOUSES) throw new IllegalArgumentException("指定仓库范围超过容量上限");
        warehouseIds = Set.copyOf(warehouseIds);
    }

    /**
     * 创建全部仓库范围，不枚举当前数据库中的仓库。
     * @return 全部仓库范围
     * @apiNote 示例：{@code WarehouseScope.all()}；仅能由可信授权结果决定是否使用。
     */
    public static WarehouseScope all() { return new WarehouseScope(DataScopeType.ALL, Set.of()); }

    /**
     * 创建精确指定仓库范围，空集合不授予任何仓库。
     * @param warehouseIds 允许的仓库UUID
     * @return 防御性复制后的指定范围
     * @throws IllegalArgumentException 超过容量上限
     * @throws NullPointerException 集合或其中UUID为空
     * @apiNote 示例：{@code WarehouseScope.custom(Set.of(warehouseId))}。
     */
    public static WarehouseScope custom(Set<UUID> warehouseIds) { return new WarehouseScope(DataScopeType.CUSTOM, warehouseIds); }

    /**
     * 判断目标仓库是否在当前范围内，不检查账号、API权限或仓库是否存在。
     * @param warehouseId 待访问目标仓库，不能为空
     * @return 是否包含该目标
     * @throws NullPointerException 目标仓库为空，ALL也不能放行无目标请求
     * @apiNote 示例：{@code scope.permits(orderWarehouseId)}须与API鉴权及目标存在性检查组合。
     */
    public boolean permits(UUID warehouseId) {
        Objects.requireNonNull(warehouseId, "目标仓库不能为空");
        return type == DataScopeType.ALL || warehouseIds.contains(warehouseId);
    }
}
