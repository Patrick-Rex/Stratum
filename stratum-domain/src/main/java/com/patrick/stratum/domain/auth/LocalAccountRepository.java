package com.patrick.stratum.domain.auth;

import java.util.Optional;

/** 本地账号写模型仓储端口；读取只供写工作单元使用，登录读取经QueryGateway实现。 */
public interface LocalAccountRepository {
    /**
     * 插入新账号，唯一用户名和初始化标记由数据库保障。
     * @param account 尚未持久化的账号
     * @throws IllegalStateException 不在Application写工作单元内
     * @apiNote 示例：{@code accounts.insert(account)}，不得用于覆盖已有账号密码。
     */
    void insert(LocalAccount account);

    /**
     * 在写工作单元内按ID恢复账号。
     * @param id 账号UUID
     * @return 存在时返回聚合
     * @apiNote 示例：{@code accounts.findById(id)}用于写用例校验。
     */
    Optional<LocalAccount> findById(String id);

    /**
     * 查找唯一的已初始化账号，用于重复启动时保留既有凭据。
     * @return 初始账号，未初始化时为空
     * @apiNote 示例：{@code accounts.findBootstrapAdministrator()}在工作单元内调用。
     */
    Optional<LocalAccount> findBootstrapAdministrator();
}
