package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountRevocations;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 编排可信管理员的账号启停，在数据库提交前撤销旧令牌并保护最后一名管理员。 */
@Component
@RequiredArgsConstructor
public class AccountStatusApplicationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final LocalAccountRepository accounts;
    private final AccountRevocations revocations;
    private final UnitOfWork work;

    /**
     * 改变目标账号启用状态；操作者从服务器身份获取，不能由调用参数指定。
     * @param userId 目标账号UUID
     * @param expectedVersion 客户端读取的非负账号版本
     * @param enabled 目标启用状态
     * @return 提交后的账号版本；相同状态且版本匹配时不重复写入或撤销
     * @throws StratumException 非管理员、目标缺失、版本冲突、最后管理员保护或撤销失败
     * @apiNote 示例：{@code status.changeEnabled(userId, version, false)}；Redis失败回滚数据库，数据库失败不恢复撤销版本。
     */
    @Transactional(propagation = Propagation.NEVER)
    public long changeEnabled(UUID userId, long expectedVersion, boolean enabled) {
        Objects.requireNonNull(userId, "目标账号不能为空");
        if (expectedVersion < 0) throw new IllegalArgumentException("账号版本不能为负数");
        UUID actor = identity.require().userId();
        return work.execute(() -> {
            administration.lock();
            AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
            var account = accounts.findById(userId.toString())
                    .orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
            if (account.getVersion() != expectedVersion) throw new StratumException(CommonErrorCode.CONFLICT);
            if (account.isEnabled() == enabled) return account.getVersion();
            if (!enabled && administration.isEnabledAdministrator(userId)) {
                AdministrationPolicy.requireAdministratorRemaining(administration.countEnabledAdministrators(), true);
            }
            account.changeEnabled(enabled);
            accounts.updateStatus(account);
            revocations.revoke(userId);
            return account.getVersion();
        });
    }
}
