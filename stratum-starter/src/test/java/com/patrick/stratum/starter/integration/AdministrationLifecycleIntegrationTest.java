package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.auth.AccountCreationApplicationService;
import com.patrick.stratum.application.auth.AccountRoleApplicationService;
import com.patrick.stratum.application.auth.AccountStatusApplicationService;
import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.RoleManagementApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.AccountRevocations;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实HTTP登录串联管理应用，验证授权立即读取、目标撤销和重新启用的完整生命周期。 */
class AdministrationLifecycleIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @Autowired private LocalAccountRepository accounts;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private AccountRevocations revocations;
    @Autowired private AccountPermissions permissions;
    @Autowired private PasswordHasher passwords;
    @Autowired private UnitOfWork work;
    @Autowired private AuthSessionApplicationService authentication;
    @Autowired private JdbcTemplate jdbc;
    private UUID target;
    private UUID role;

    /** 删除本用例创建的关系、账号及角色；会话键由HTTP夹具逐一清理。 */
    @AfterEach
    void cleanOwnedRecords() {
        if (target != null) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", target.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", target.toString());
        }
        if (role != null) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", role.toString());
            jdbc.update("DELETE FROM auth_role WHERE id=?", role.toString());
        }
    }

    /**
     * 普通账号创建后无权限，分配角色撤销旧会话；配置立即可见，停用与重新启用不会恢复旧令牌。
     * @throws Exception HTTP登录失败
     */
    @Test
    void completeAdministrationAndAuthenticationLifecycle() throws Exception {
        var actor = authentication.authenticateAccess(accessToken());
        CurrentIdentity identity = () -> new CurrentIdentity.Snapshot(UUID.fromString(actor.subject()), actor.sessionId(), actor.version());
        var creation = new AccountCreationApplicationService(identity, administration, accounts, passwords, work);
        var management = new RoleManagementApplicationService(identity, administration, roles, work);
        var membership = new AccountRoleApplicationService(identity, administration, roles, accounts, revocations, work);
        var status = new AccountStatusApplicationService(identity, administration, accounts, revocations, work);
        String username = "managed-" + UUID.randomUUID();
        char[] secret = UUID.randomUUID().toString().toCharArray();
        try {
            char[] consumed = secret.clone();
            target = creation.create(username, consumed);
            assertArrayEquals(new char[consumed.length], consumed);
            var initial = login(username, new String(secret));
            assertTrue(snapshot().permissions().isEmpty());
            role = management.create("managed-" + UUID.randomUUID(), "库存查询员");
            var grants = Set.of(PermissionCatalog.INVENTORY_READ.permission(), PermissionCatalog.INVENTORY_FEATURE.permission());
            assertEquals(1, management.configure(role, 0, "库存只读", grants));
            assertEquals(1, membership.replace(target, 0, Set.of(role)));
            unauthorized(() -> authentication.authenticateAccess(initial.accessToken()));
            unauthorized(() -> authentication.refresh(initial.refreshToken()));
            var assigned = login(username, new String(secret));
            assertEquals(1, authentication.authenticateAccess(assigned.accessToken()).version());
            assertEquals(grants, snapshot().permissions());
            assertEquals(2, management.configure(role, 1, "暂停授权", Set.of()));
            assertTrue(snapshot().permissions().isEmpty());
            assertEquals(1, authentication.authenticateAccess(assigned.accessToken()).version());
            assertEquals(2, status.changeEnabled(target, 1, false));
            unauthorized(() -> authentication.authenticateAccess(assigned.accessToken()));
            assertEquals(3, status.changeEnabled(target, 2, true));
            unauthorized(() -> authentication.refresh(assigned.refreshToken()));
            var enabled = login(username, new String(secret));
            assertEquals(3, authentication.authenticateAccess(enabled.accessToken()).version());
            assertEquals(4, membership.replace(target, 3, Set.of()));
            unauthorized(() -> authentication.authenticateAccess(enabled.accessToken()));
            assertTrue(snapshot().permissions().isEmpty());
            assertEquals(4, authentication.authenticateAccess(login(username, new String(secret)).accessToken()).version());
        } finally { Arrays.fill(secret, '\0'); }
    }

    /**
     * 经正式QueryGateway适配器读取当前目标的权威授权。
     * @return 当前启用状态及权限快照
     */
    private AccountPermissions.Snapshot snapshot() {
        return permissions.findByUserId(target, "administration-lifecycle").orElseThrow();
    }

    /**
     * 断言令牌已被拒绝，不将敏感令牌输出至测试日志。
     * @param action 令牌认证或刷新动作
     */
    private static void unauthorized(Runnable action) {
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, action::run).getErrorCode());
    }
}
