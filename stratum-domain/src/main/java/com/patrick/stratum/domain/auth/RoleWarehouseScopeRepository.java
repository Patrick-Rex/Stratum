package com.patrick.stratum.domain.auth;

import java.util.Optional;

/** 角色仓库配置写端口，仅在Application工作单元及管理协调锁内使用；展示读取经QueryGateway。 */
public interface RoleWarehouseScopeRepository {
    /**
     * 当前读并锁定角色及其仓库配置，未知角色为空，配置缺失或非法应失败关闭。
     * @param roleId 角色规范UUID
     * @return 有界完整配置或空
     * @apiNote 示例：{@code scopes.findByRoleId(roleId)}，调用前需持有管理协调锁。
     */
    Optional<RoleWarehouseScope> findByRoleId(String roleId);

    /**
     * 按独立乐观版本更新完整范围，与指定仓库关系同事务保存，不改写角色权限版本。
     * @param configuration 已存在配置聚合
     * @apiNote 示例：{@code scopes.save(configuration)}；旧版本或引用错误返回409，失败必须回滚整个工作单元。
     */
    void save(RoleWarehouseScope configuration);
}
