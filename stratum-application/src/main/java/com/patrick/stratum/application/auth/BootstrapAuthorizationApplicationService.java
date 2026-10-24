package com.patrick.stratum.application.auth;

import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在唯一管理协调事务内登记权限、维护管理员角色并仅首次绑定初始账号。 */
@Component
@RequiredArgsConstructor
public class BootstrapAuthorizationApplicationService {
    private final AuthorizationAdministrationRepository administration;
    private final AuthorizationRoleRepository roles;
    private final LocalAccountRepository accounts;
    private final UnitOfWork work;

    /**
     * 初始化角色授权；后续启动只同步角色目录，不恢复被移除的账号角色关系。
     * @param bootstrapUserId 已由账号初始化服务返回的账号UUID
     * @return 管理员角色UUID
     * @throws StratumException 首次目标不合法或不存在可用管理员时拒绝初始化
     * @apiNote 示例：{@code authorization.initialize(bootstrap.initialize(username, password))}，从无事务启动入口调用。
     */
    @Transactional(propagation = Propagation.NEVER)
    public UUID initialize(UUID bootstrapUserId) {
        Objects.requireNonNull(bootstrapUserId, "初始账号不能为空");
        return work.execute(() -> {
            administration.lock();
            roles.registerCatalog();
            var administrator = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(AuthorizationRole::administrator);
            if (administrator.getVersion() == null || !administrator.getPermissions().equals(PermissionCatalog.permissions())) {
                administrator.synchronizeCatalog(); roles.save(administrator);
            }
            UUID roleId = UUID.fromString(administrator.getId());
            if (!administration.isInitialized()) {
                var account = accounts.findById(bootstrapUserId.toString())
                        .filter(candidate -> candidate.isBootstrapAdministrator() && candidate.isEnabled())
                        .orElseThrow(() -> new StratumException(CommonErrorCode.CONFLICT));
                UUID userId = UUID.fromString(account.getId());
                var membership = new HashSet<>(administration.findRoleIds(userId)); membership.add(roleId);
                administration.replaceRoleIds(userId, membership);
                administration.markInitialized();
            }
            if (administration.countEnabledAdministrators() == 0) throw new StratumException(CommonErrorCode.CONFLICT);
            return roleId;
        });
    }
}
