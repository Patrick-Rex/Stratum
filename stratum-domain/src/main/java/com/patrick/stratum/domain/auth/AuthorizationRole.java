package com.patrick.stratum.domain.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根：角色固定身份与精确权限集合；管理员角色由目录同步维护全部登记权限。 */
public final class AuthorizationRole extends AggregateRoot<String> {
    public static final String ADMINISTRATOR_CODE = "administrator";
    private final String code;
    private String name;
    private Set<Permission> permissions;

    /**
     * 构造经过校验的角色快照。
     * @param id 角色UUID
     * @param code 固定编码
     * @param name 展示名称
     * @param permissions 权限集合
     * @param version 持久化版本，新建为空
     */
    private AuthorizationRole(String id, String code, String name, Set<Permission> permissions, Long version) {
        super(id);
        this.code = new Permission(code, Permission.Type.API).code();
        this.name = requireName(name);
        this.permissions = copyPermissions(permissions);
        if (version != null && version < 0) throw new IllegalArgumentException("角色版本不合法");
        setVersion(version);
    }

    /**
     * 创建普通角色，默认没有权限，保留编码不能从普通创建入口占用。
     * @param code 固定编码
     * @param name 展示名称
     * @return 未保存角色
     * @apiNote 示例：{@code AuthorizationRole.create("purchaser", "采购员")}。
     */
    public static AuthorizationRole create(String code, String name) {
        if (ADMINISTRATOR_CODE.equals(code)) throw new IllegalArgumentException("管理员编码为系统保留");
        return new AuthorizationRole(UUID.randomUUID().toString(), code, name, Set.of(), null);
    }

    /**
     * 创建持有全部目录权限的管理员角色，仅用于可信初始化流程。
     * @return 未保存的管理员角色
     * @apiNote 示例：{@code AuthorizationRole.administrator()}；不会自动授予任何账号。
     */
    public static AuthorizationRole administrator() {
        return new AuthorizationRole(UUID.randomUUID().toString(), ADMINISTRATOR_CODE, "管理员", PermissionCatalog.permissions(), null);
    }

    /**
     * 从写仓储重建角色，保留存储中的精确权限，不自动补授权。
     * @param id 标准UUID
     * @param code 固定编码
     * @param name 展示名称
     * @param permissions 已保存权限
     * @param version 非负版本
     * @return 持久化角色
     * @apiNote 示例：{@code AuthorizationRole.restore(id, code, name, grants, 0)}。
     */
    public static AuthorizationRole restore(String id, String code, String name, Set<Permission> permissions, long version) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("角色标识不合法");
        return new AuthorizationRole(id, code, name, permissions, version);
    }

    /**
     * 修改名称，编码及身份保持不变。
     * @param name 新名称
     * @apiNote 示例：{@code role.rename("采购主管")}。
     */
    public void rename(String name) { this.name = requireName(name); }

    /**
     * 替换普通角色全部权限，只有已登记权限可以分配。
     * @param permissions 新权限集合
     * @throws IllegalArgumentException 管理员角色、未登记权限或超界集合
     * @apiNote 示例：{@code role.replacePermissions(Set.of(PermissionCatalog.PURCHASE_READ.permission()))}。
     */
    public void replacePermissions(Set<Permission> permissions) {
        if (isAdministrator()) throw new IllegalArgumentException("管理员权限由目录统一维护");
        var replacement = copyPermissions(permissions);
        if (!PermissionCatalog.permissions().containsAll(replacement)) throw new IllegalArgumentException("权限未登记");
        this.permissions = replacement;
    }

    /**
     * 为管理员角色同步当前完整目录，不能用于普通角色提升权限。
     * @apiNote 示例：{@code administrator.synchronizeCatalog()}仅由启动初始化调用。
     */
    public void synchronizeCatalog() {
        if (!isAdministrator()) throw new IllegalArgumentException("普通角色不能同步管理员权限");
        permissions = PermissionCatalog.permissions();
    }

    /**
     * 判断是否为受保护管理员角色。
     * @return 是否使用保留编码
     * @apiNote 示例：{@code role.isAdministrator()}不代表当前操作者有权限。
     */
    public boolean isAdministrator() { return ADMINISTRATOR_CODE.equals(code); }

    /**
     * 获取固定编码。
     * @return 角色编码
     * @apiNote 示例：{@code role.getCode()}。
     */
    public String getCode() { return code; }

    /**
     * 获取展示名称。
     * @return 非空名称
     * @apiNote 示例：{@code role.getName()}。
     */
    public String getName() { return name; }

    /**
     * 获取不可变权限集合。
     * @return 精确类型化权限
     * @apiNote 示例：{@code role.getPermissions()}。
     */
    public Set<Permission> getPermissions() { return permissions; }

    /**
     * 保存后单调更新版本。
     * @param version 新版本
     * @apiNote 示例：{@code role.markPersisted(0)}用于首次保存。
     */
    public void markPersisted(long version) {
        if (version < 0 || getVersion() != null && version <= getVersion()) throw new IllegalArgumentException("角色版本必须递增");
        setVersion(version);
    }

    /**
     * 校验角色名称，不裁剪用户输入。
     * @param name 展示名称
     * @return 原名称
     */
    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("角色名称不合法");
        }
        return name;
    }

    /**
     * 防御性复制并限制角色权限数量。
     * @param permissions 权限集合
     * @return 不可变副本
     */
    private static Set<Permission> copyPermissions(Set<Permission> permissions) {
        Objects.requireNonNull(permissions, "权限集合不能为空");
        if (permissions.size() > AccountPermissions.Snapshot.MAX_PERMISSIONS) throw new IllegalArgumentException("角色权限超出上限");
        return Set.copyOf(permissions);
    }
}
