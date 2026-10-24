package com.patrick.stratum.domain.auth;

import java.util.Optional;

/** 角色聚合写仓储端口，所有操作仅在Application写事务内使用；读模型走QueryGateway。 */
public interface AuthorizationRoleRepository {
    /**
     * 登记当前权限目录，保留既有权限ID；仅由持有管理协调锁的初始化调用。
     * @apiNote 示例：{@code roles.registerCatalog()}。
     */
    void registerCatalog();

    /**
     * 保存新角色或按版本更新名称和权限，不修改编码；关系与角色同事务提交。
     * @param role 角色聚合
     * @apiNote 示例：{@code roles.save(role)}；冲突返回409。
     */
    void save(AuthorizationRole role);

    /**
     * 为写操作锁定并读取最新角色及权限，不使用旧事务快照。
     * @param id 标准角色UUID
     * @return 角色或空
     * @apiNote 示例：{@code roles.findById(id)}，需要已持有管理协调锁。
     */
    Optional<AuthorizationRole> findById(String id);

    /**
     * 按固定编码锁定读取当前角色，初始化时用于幂等定位。
     * @param code 精确角色编码
     * @return 角色或空
     * @apiNote 示例：{@code roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE)}。
     */
    Optional<AuthorizationRole> findByCode(String code);
}
