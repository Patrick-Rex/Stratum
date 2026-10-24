package com.patrick.stratum.application.auth;

import java.util.Arrays;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动的账号写用例：数据库唯一槽裁决并发，重复初始化不修改已有凭据。 */
@Component
@RequiredArgsConstructor
public class BootstrapAccountApplicationService {
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;

    /**
     * 建立唯一初始化账号；已存在时忽略输入，不重置用户名、密码或启用状态。
     * BCrypt计算在写事务外完成；唯一冲突回滚后在新工作单元读取胜者。
     * @param username 首次创建必须提供的精确登录标识
     * @param password 首次创建必须提供的密码数组，方法执行后清零；调用方仍须在外层保证清理
     * @return 初始账号UUID，不包含凭据；权限由后续RBAC配置
     * @throws IllegalArgumentException 首次输入不合法
     * @throws StratumException 存储失败或用户名已被普通账号占用
     * @apiNote 示例：{@code bootstrap.initialize(username, password)}从无事务的启动入口调用。
     */
    @Transactional(propagation = Propagation.NEVER)
    public UUID initialize(String username, char[] password) {
        try {
            var existing = unitOfWork.execute(accounts::findBootstrapAdministrator);
            if (existing.isPresent()) { return UUID.fromString(existing.orElseThrow().getId()); }
            String validatedUsername = LocalAccount.requireUsername(username);
            String hash = passwords.hash(password);
            try {
                return unitOfWork.execute(() -> {
                    var current = accounts.findBootstrapAdministrator();
                    if (current.isPresent()) { return UUID.fromString(current.orElseThrow().getId()); }
                    var account = LocalAccount.create(validatedUsername, hash, true);
                    accounts.insert(account);
                    return UUID.fromString(account.getId());
                });
            } catch (StratumException failure) {
                if (failure.getErrorCode() != CommonErrorCode.CONFLICT) { throw failure; }
                return unitOfWork.execute(accounts::findBootstrapAdministrator)
                        .map(account -> UUID.fromString(account.getId())).orElseThrow(() -> failure);
            }
        } finally {
            if (password != null) { Arrays.fill(password, '\0'); }
        }
    }
}
