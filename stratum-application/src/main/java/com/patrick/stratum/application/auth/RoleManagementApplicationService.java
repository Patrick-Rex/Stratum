package com.patrick.stratum.application.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 当前管理员创建普通角色与配置精确权限；权限变更通过权威查询即时可见。 */
@Component
@RequiredArgsConstructor
public class RoleManagementApplicationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final AuthorizationRoleRepository roles;
    private final UnitOfWork work;

    /**
     * 创建空权限普通角色，角色编码由领域验证并保持固定。
     * @param code 固定角色编码
     * @param name 展示名称
     * @return 新角色UUID
     * @throws StratumException 当前操作者非管理员、输入非法或编码冲突
     * @apiNote 示例：{@code management.create("purchaser", "采购员")}；权限随后显式配置。
     */
    @Transactional(propagation = Propagation.NEVER)
    public UUID create(String code, String name) {
        UUID actor = identity.require().userId();
        return work.execute(() -> {
            requireAdministrator(actor);
            AuthorizationRole role;
            try { role = AuthorizationRole.create(code, name); }
            catch (IllegalArgumentException invalidInput) { throw new StratumException(CommonErrorCode.BAD_REQUEST); }
            roles.save(role);
            return UUID.fromString(role.getId());
        });
    }

    /**
     * 原子修改普通角色名称和整组权限，保留管理员角色由初始化目录维护。
     * @param roleId 目标角色UUID
     * @param expectedVersion 非负预期版本
     * @param name 新名称
     * @param permissions 完整权限集合，只允许目录已登记项
     * @return 保存后的角色版本
     * @throws StratumException 当前操作者非管理员、角色缺失、输入非法或版本冲突
     * @apiNote 示例：{@code management.configure(roleId, version, name, grants)}；清空集合即移除普通角色全部授权。
     */
    @Transactional(propagation = Propagation.NEVER)
    public long configure(UUID roleId, long expectedVersion, String name, Set<Permission> permissions) {
        Objects.requireNonNull(roleId, "目标角色不能为空");
        if (expectedVersion < 0) throw new IllegalArgumentException("角色版本不能为负数");
        UUID actor = identity.require().userId();
        return work.execute(() -> {
            requireAdministrator(actor);
            var role = roles.findById(roleId.toString()).orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND));
            if (role.getVersion() != expectedVersion) throw new StratumException(CommonErrorCode.CONFLICT);
            try { role.replacePermissions(permissions); role.rename(name); }
            catch (IllegalArgumentException invalidInput) { throw new StratumException(CommonErrorCode.BAD_REQUEST); }
            roles.save(role);
            return role.getVersion();
        });
    }

    /**
     * 在协调锁保护下读取操作者最新管理员状态。
     * @param actor 服务器可信主体
     */
    private void requireAdministrator(UUID actor) {
        administration.lock();
        AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
    }
}
