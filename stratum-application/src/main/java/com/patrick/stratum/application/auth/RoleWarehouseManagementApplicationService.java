package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.DataScopeType;
import com.patrick.stratum.domain.auth.RoleWarehouseScopeRepository;
import com.patrick.stratum.domain.auth.WarehouseScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 当前管理员在独立工作单元配置角色完整仓库范围，不改变角色API权限或撤销成员会话。 */
@Component
@RequiredArgsConstructor
public class RoleWarehouseManagementApplicationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final RoleWarehouseScopeRepository scopes;
    private final UnitOfWork work;

    /**
     * 以独立配置版本整组替换普通角色范围，当前管理员资格在协调锁内核对。
     * @param roleId 目标角色UUID
     * @param expectedVersion 观察到的独立仓库版本
     * @param scopeType ALL或CUSTOM
     * @param warehouseIds 完整指定集合，ALL须为空
     * @return 本次配置版本，相同配置且版本匹配时不重复保存
     * @throws StratumException 身份失效401、非管理员403、输入400、角色缺失404、旧版本或引用冲突409，存储故障500
     * @apiNote 示例：{@code management.configure(roleId, version, DataScopeType.CUSTOM, Set.of(warehouseId))}。
     */
    @Transactional(propagation = Propagation.NEVER)
    public long configure(UUID roleId, long expectedVersion, DataScopeType scopeType, Set<UUID> warehouseIds) {
        Objects.requireNonNull(roleId, "目标角色不能为空");
        UUID actor = identity.require().userId();
        WarehouseScope replacement;
        try {
            if (expectedVersion < 0) throw new IllegalArgumentException("仓库配置版本不能为负");
            replacement = new WarehouseScope(scopeType, warehouseIds);
        } catch (IllegalArgumentException | NullPointerException invalidInput) { throw new StratumException(CommonErrorCode.BAD_REQUEST); }
        return work.execute(() -> {
            administration.lock(); AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
            var configuration = scopes.findByRoleId(roleId.toString()).orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
            if (configuration.getVersion() != expectedVersion) throw new StratumException(CommonErrorCode.CONFLICT);
            var previous = configuration.getScope();
            try { configuration.replace(replacement); }
            catch (IllegalArgumentException invalidInput) { throw new StratumException(CommonErrorCode.BAD_REQUEST); }
            if (previous.equals(replacement)) return configuration.getVersion();
            scopes.save(configuration); return configuration.getVersion();
        });
    }
}
