package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountRevocations;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 管理员原子替换账号角色，同事务递增账号版本并在提交前撤销旧会话。 */
@Component
@RequiredArgsConstructor
public class AccountRoleApplicationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final AuthorizationRoleRepository roles;
    private final LocalAccountRepository accounts;
    private final AccountRevocations revocations;
    private final UnitOfWork work;

    /**
     * 替换目标账号完整角色集合，保护最后一名可用管理员并保持账号固定属性及启用状态。
     * @param userId 目标账号UUID
     * @param expectedVersion 当前账号版本，与启停操作共用乐观并发保护
     * @param roleIds 完整角色集合，最多64项；空集合移除全部角色
     * @return 提交后的账号版本；相同集合且版本匹配时无副作用
     * @throws StratumException 非管理员、目标缺失、角色不存在、版本冲突、最后管理员保护或撤销失败
     * @apiNote 示例：{@code memberships.replace(userId, version, roles)}；存储失败不得返回成功或回退撤销版本。
     */
    @Transactional(propagation = Propagation.NEVER)
    public long replace(UUID userId, long expectedVersion, Set<UUID> roleIds) {
        Objects.requireNonNull(userId, "目标账号不能为空");
        Objects.requireNonNull(roleIds, "角色集合不能为空");
        if (expectedVersion < 0 || roleIds.size() > AuthorizationAdministrationRepository.MAX_ROLES_PER_ACCOUNT) {
            throw new IllegalArgumentException("账号版本或角色数量不合法");
        }
        var replacement = Set.copyOf(roleIds);
        UUID actor = identity.require().userId();
        return work.execute(() -> {
            administration.lock();
            AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
            var account = accounts.findById(userId.toString()).orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
            if (account.getVersion() != expectedVersion) throw new StratumException(CommonErrorCode.CONFLICT);
            if (administration.findRoleIds(userId).equals(replacement)) return account.getVersion();
            if (administration.isEnabledAdministrator(userId)) {
                UUID administrator = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE)
                        .map(role -> UUID.fromString(role.getId())).orElseThrow(() -> new StratumException(CommonErrorCode.INTERNAL_ERROR));
                AdministrationPolicy.requireAdministratorRemaining(administration.countEnabledAdministrators(), !replacement.contains(administrator));
            }
            administration.replaceRoleIds(userId, replacement);
            accounts.updateStatus(account);
            revocations.revoke(userId);
            return account.getVersion();
        });
    }
}
