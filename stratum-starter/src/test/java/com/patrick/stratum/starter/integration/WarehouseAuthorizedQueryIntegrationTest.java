package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.RoleWarehouseScopeRepository;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.query.QueryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 为内部查询验收创建真实普通账号及显式ALL仓库角色，不改变生产默认范围或API授权。 */
abstract class WarehouseAuthorizedQueryIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private AuthorizationRoleRepository warehouseQueryRoles;
    @Autowired private RoleWarehouseScopeRepository warehouseQueryScopes;
    @Autowired private AuthorizationAdministrationRepository warehouseQueryAdministration;
    @Autowired private LocalAccountRepository warehouseQueryAccounts;
    @Autowired private PasswordHasher warehouseQueryPasswords;
    @Autowired private UnitOfWork warehouseQueryWork;
    @Autowired private JdbcTemplate warehouseQueryJdbc;
    protected QueryContext context;
    private String warehouseQueryUserId;
    private String warehouseQueryRoleId;

    /** 使用应用工作单元原子创建账号、角色及显式ALL配置，生成只含可信主体的查询上下文。 */
    @BeforeEach
    void prepareWarehouseQueryIdentity() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("authorized-query-" + UUID.randomUUID(), warehouseQueryPasswords.hash(raw), false);
            var role = AuthorizationRole.create("authorized-query-" + UUID.randomUUID(), "查询验收");
            warehouseQueryUserId = account.getId(); warehouseQueryRoleId = role.getId();
            warehouseQueryWork.execute(() -> {
                warehouseQueryAdministration.lock(); warehouseQueryRoles.save(role);
                var scope = warehouseQueryScopes.findByRoleId(role.getId()).orElseThrow();
                scope.replace(WarehouseScope.all()); warehouseQueryScopes.save(scope);
                warehouseQueryAccounts.insert(account);
                warehouseQueryAdministration.replaceRoleIds(UUID.fromString(account.getId()), Set.of(UUID.fromString(role.getId())));
                return null;
            });
            context = new QueryContext.Snapshot(UUID.randomUUID().toString(), Optional.of(account.getId()), Set.of());
        } finally { Arrays.fill(raw, '\0'); }
    }

    /** 仅清理本用例的授权记录，仓库配置由角色外键级联清理，不触碰共享管理员。 */
    @AfterEach
    void cleanWarehouseQueryIdentity() {
        if (warehouseQueryUserId != null) {
            warehouseQueryJdbc.update("DELETE FROM auth_user_role WHERE user_id=?", warehouseQueryUserId);
            warehouseQueryJdbc.update("DELETE FROM auth_user WHERE id=?", warehouseQueryUserId);
        }
        if (warehouseQueryRoleId != null) {
            warehouseQueryJdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", warehouseQueryRoleId);
            warehouseQueryJdbc.update("DELETE FROM auth_role WHERE id=?", warehouseQueryRoleId);
        }
    }
}
