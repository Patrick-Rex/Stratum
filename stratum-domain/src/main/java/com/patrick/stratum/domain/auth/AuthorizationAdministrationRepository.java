package com.patrick.stratum.domain.auth;

import java.util.Set;
import java.util.UUID;

/** 管理变更的写存储端口；仅限Application事务，协调锁贯穿授权复核、领域规则和关系修改。 */
public interface AuthorizationAdministrationRepository {
    int MAX_ROLES_PER_ACCOUNT = 64;

    /**
     * 获取数据库管理协调行排他锁，直到当前事务结束；同事务重复调用安全。
     * @apiNote 示例：{@code administration.lock()}必须先于管理状态读取。
     */
    void lock();

    /**
     * 以当前读判断启用账号是否属于管理员角色，不依赖初始化标记。
     * @param userId 可信账号UUID
     * @return 是否为可用管理员
     * @apiNote 示例：{@code administration.isEnabledAdministrator(actor)}。
     */
    boolean isEnabledAdministrator(UUID userId);

    /**
     * 以当前读统计可用管理员，供领域层保护最后一名管理员。
     * @return 启用且具有管理员角色的账号数
     * @apiNote 示例：{@code administration.countEnabledAdministrators()}；需要协调锁。
     */
    long countEnabledAdministrators();

    /**
     * 读取目标账号当前角色集合，超出上限拒绝，未知账号由用例单独校验。
     * @param userId 账号UUID
     * @return 有界不可变角色ID集合
     * @apiNote 示例：{@code administration.findRoleIds(userId)}。
     */
    Set<UUID> findRoleIds(UUID userId);

    /**
     * 同事务替换账号角色集合；调用者须先验证目标存在、角色存在及最后管理员规则。
     * @param userId 目标账号UUID
     * @param roleIds 最多64项角色ID
     * @apiNote 示例：{@code administration.replaceRoleIds(userId, Set.of(roleId))}。
     */
    void replaceRoleIds(UUID userId, Set<UUID> roleIds);

    /**
     * 读取初始管理员绑定是否已完成；重启不得恢复后来移除的绑定。
     * @return 初始化标记
     * @apiNote 示例：{@code administration.isInitialized()}。
     */
    boolean isInitialized();

    /**
     * 将首次绑定标记置为完成，与绑定同事务提交，不提供反向复位入口。
     * @apiNote 示例：{@code administration.markInitialized()}。
     */
    void markInitialized();
}
