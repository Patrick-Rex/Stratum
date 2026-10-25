package com.patrick.stratum.application.auth;

import java.util.Arrays;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 管理员创建普通账号；事务外散列密码，写入前重新检查真实管理员关系。 */
@Component
@RequiredArgsConstructor
public class AccountCreationApplicationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork work;

    /**
     * 创建默认启用、无角色、无业务权限的普通账号，不接收初始化标记或自报操作者。
     * @param username 精确登录名，沿用账号领域边界
     * @param password 待消费密码数组，执行后清零；外层调用方也须保证清理
     * @return 新账号UUID，不返回凭据
     * @throws com.patrick.stratum.common.error.StratumException 非当前管理员、用户名冲突或存储失败
     * @apiNote 示例：{@code creation.create(username, password)}从无事务的管理入口调用。
     */
    @Transactional(propagation = Propagation.NEVER)
    public UUID create(String username, char[] password) {
        try {
            UUID actor = identity.require().userId();
            String validated = LocalAccount.requireUsername(username);
            work.execute(() -> { requireAdministrator(actor); return null; });
            String hash = passwords.hash(password);
            return work.execute(() -> {
                requireAdministrator(actor);
                var account = LocalAccount.create(validated, hash, false);
                accounts.insert(account);
                return UUID.fromString(account.getId());
            });
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }

    /**
     * 在当前工作单元持有管理协调锁后检查最新管理员状态。
     * @param actor 已验证服务器主体
     */
    private void requireAdministrator(UUID actor) {
        administration.lock();
        AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
    }
}
