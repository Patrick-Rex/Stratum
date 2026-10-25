package com.patrick.stratum.domain.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根：以角色UUID标识独立的仓库配置；管理员固定ALL，普通角色从空范围开始。 */
public final class RoleWarehouseScope extends AggregateRoot<String> {
    private final String roleCode;
    private WarehouseScope scope;

    /**
     * 构造已验证的角色范围配置，不控制角色成员或API权限。
     * @param roleId 已存在角色的规范UUID
     * @param roleCode 固定角色编码
     * @param scope 仓库范围
     * @param version 非负独立配置版本
     */
    private RoleWarehouseScope(String roleId, String roleCode, WarehouseScope scope, long version) {
        super(requireRoleId(roleId));
        this.roleCode = new Permission(roleCode, Permission.Type.API).code();
        this.scope = Objects.requireNonNull(scope, "角色仓库范围不能为空");
        if (version < 0) throw new IllegalArgumentException("仓库配置版本不能为负");
        if (isAdministrator() && scope.type() != DataScopeType.ALL) throw new IllegalArgumentException("管理员仓库范围必须为全部");
        setVersion(version);
    }

    /**
     * 为新角色或既有角色迁移提供初始配置，不授予任何账号成员关系。
     * @param roleId 角色UUID
     * @param roleCode 固定角色编码
     * @return 版本0的配置，普通角色为空，管理员为ALL
     * @apiNote 示例：{@code RoleWarehouseScope.initial(roleId, "purchaser")}由角色保存流程使用。
     */
    public static RoleWarehouseScope initial(String roleId, String roleCode) {
        var scope = AuthorizationRole.ADMINISTRATOR_CODE.equals(roleCode) ? WarehouseScope.all() : WarehouseScope.custom(Set.of());
        return new RoleWarehouseScope(roleId, roleCode, scope, 0);
    }

    /**
     * 从可信写仓储重建精确范围；违反管理员不变量的存储数据不能被静默扩大。
     * @param roleId 角色UUID
     * @param roleCode 固定角色编码
     * @param scope 已存范围
     * @param version 独立配置版本
     * @return 已保存配置
     * @apiNote 示例：{@code RoleWarehouseScope.restore(roleId, code, scope, 3)}。
     */
    public static RoleWarehouseScope restore(String roleId, String roleCode, WarehouseScope scope, long version) {
        return new RoleWarehouseScope(roleId, roleCode, scope, version);
    }

    /**
     * 替换普通角色的完整仓库配置，管理员配置不能经管理入口改写。
     * @param replacement 已验证的ALL或CUSTOM范围
     * @throws IllegalArgumentException 操作管理员配置
     * @throws NullPointerException 范围缺失
     * @apiNote 示例：{@code configuration.replace(WarehouseScope.custom(Set.of(warehouseId)))}。
     */
    public void replace(WarehouseScope replacement) {
        if (isAdministrator()) throw new IllegalArgumentException("管理员仓库范围由固定规则维护");
        scope = Objects.requireNonNull(replacement, "角色仓库范围不能为空");
    }

    /**
     * 获取固定角色编码，仅用于领域规则，不作为当前操作者资格。
     * @return 角色编码
     * @apiNote 示例：{@code configuration.getRoleCode()}。
     */
    public String getRoleCode() { return roleCode; }

    /**
     * 获取不可变配置快照。
     * @return 当前仓库范围
     * @apiNote 示例：{@code configuration.getScope().warehouseIds()}。
     */
    public WarehouseScope getScope() { return scope; }

    /**
     * 更新保存成功后的独立版本，禁止相同版本或回退。
     * @param version 新版本
     * @throws IllegalArgumentException 版本不严格递增
     * @apiNote 示例：{@code configuration.markPersisted(1)}；不改变角色API权限版本。
     */
    public void markPersisted(long version) {
        if (version <= getVersion()) throw new IllegalArgumentException("仓库配置版本必须递增");
        setVersion(version);
    }

    /**
     * 判断固定管理员编码，不从该结论推断当前账号的角色关系。
     * @return 是否管理员配置
     */
    private boolean isAdministrator() { return AuthorizationRole.ADMINISTRATOR_CODE.equals(roleCode); }

    /**
     * 限制配置身份为已存在角色的完整规范UUID。
     * @param roleId 角色UUID
     * @return 原标识
     */
    private static String requireRoleId(String roleId) {
        if (roleId == null || !UUID.fromString(roleId).toString().equals(roleId)) throw new IllegalArgumentException("角色标识不合法");
        return roleId;
    }
}
