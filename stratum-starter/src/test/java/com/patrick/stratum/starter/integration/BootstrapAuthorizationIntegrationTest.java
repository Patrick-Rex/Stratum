package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.BootstrapAuthorizationApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** 验证目录与首次管理员绑定同事务、重启幂等和并发初始化，不启动HTTP服务。 */
class BootstrapAuthorizationIntegrationTest extends MariaDbIntegrationTest {
    private final BootstrapAuthorizationApplicationService bootstrap;
    private final AuthorizationAdministrationRepository administration;
    private final AuthorizationRoleRepository roles;
    private final LocalAccountRepository accounts;
    private final AccountPermissions permissions;
    private final PasswordHasher passwords;
    private final UnitOfWork work;
    private final JdbcTemplate jdbc;
    private final List<UUID> users = new ArrayList<>();
    private boolean previousInitialized;
    private UUID initial;

    /**
     * 注入生产应用用例和独立断言依赖。
     * @param bootstrap 角色初始化入口
     * @param administration 管理协调仓储
     * @param roles 角色仓储
     * @param accounts 账号仓储
     * @param permissions QueryGateway权限读取端口
     * @param passwords 密码散列器
     * @param work Application事务
     * @param jdbc 断言与夹具清理客户端
     */
    @Autowired
    BootstrapAuthorizationIntegrationTest(BootstrapAuthorizationApplicationService bootstrap, AuthorizationAdministrationRepository administration,
            AuthorizationRoleRepository roles, LocalAccountRepository accounts, AccountPermissions permissions, PasswordHasher passwords,
            UnitOfWork work, JdbcTemplate jdbc) {
        this.bootstrap = bootstrap; this.administration = administration; this.roles = roles; this.accounts = accounts;
        this.permissions = permissions; this.passwords = passwords; this.work = work; this.jdbc = jdbc;
    }

    /** 在独立测试库准备唯一初始账号，记录并恢复协调标志，禁止覆盖其他初始化账号。 */
    @BeforeEach
    void prepare() {
        assertTrue(work.execute(accounts::findBootstrapAdministrator).isEmpty());
        previousInitialized = work.execute(administration::isInitialized);
        jdbc.update("UPDATE auth_administration_guard SET initialized=0 WHERE singleton_id=1");
        initial = account(true);
    }

    /** 清理本测试账号及其关系，恢复初始标志；共享目录和管理员角色可供后续启动幂等复用。 */
    @AfterEach
    void clean() {
        for (UUID user : users) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", user.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", user.toString());
        }
        jdbc.update("UPDATE auth_administration_guard SET initialized=? WHERE singleton_id=1", previousInitialized);
    }

    /** 首次账号通过真实角色获得目录权限，重复启动不增加角色版本或重置账号。 */
    @Test
    void grantsCatalogOnceAndKeepsRepeatedInitializationStable() {
        UUID role = bootstrap.initialize(initial);
        assertEquals(PermissionCatalog.permissions(), permissions.findByUserId(initial, "bootstrap-grants").orElseThrow().permissions());
        assertTrue(work.execute(administration::isInitialized));
        long version = work.execute(() -> roles.findById(role.toString()).orElseThrow().getVersion());
        assertEquals(role, bootstrap.initialize(initial));
        assertEquals(version, work.execute(() -> roles.findById(role.toString()).orElseThrow().getVersion()));
        assertEquals(Set.of(role), work.execute(() -> administration.findRoleIds(initial)));
        UUID ordinary = account(false);
        assertTrue(permissions.findByUserId(ordinary, "bootstrap-grants").orElseThrow().permissions().isEmpty());
    }

    /** 将管理员角色转交另一账号后，重启不恢复原初始化账号的成员身份。 */
    @Test
    void restartDoesNotRestoreRemovedBootstrapMembership() {
        UUID role = bootstrap.initialize(initial); UUID successor = account(false);
        work.execute(() -> {
            administration.replaceRoleIds(successor, Set.of(role)); administration.replaceRoleIds(initial, Set.of()); return null;
        });
        assertEquals(role, bootstrap.initialize(initial));
        assertTrue(work.execute(() -> administration.findRoleIds(initial)).isEmpty());
        assertTrue(work.execute(() -> administration.isEnabledAdministrator(successor)));
        assertTrue(permissions.findByUserId(initial, "bootstrap-restart").orElseThrow().permissions().isEmpty());
        work.execute(() -> { administration.replaceRoleIds(successor, Set.of()); return null; });
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> bootstrap.initialize(initial)).getErrorCode());
        assertTrue(work.execute(() -> administration.findRoleIds(initial)).isEmpty());
    }

    /** 目录补同步只调整管理员角色权限，不额外授予任何账号。 */
    @Test
    void repairsCatalogGrantsWithoutChangingMembers() {
        UUID role = bootstrap.initialize(initial);
        jdbc.update("DELETE FROM auth_role_permission WHERE role_id=? AND permission_id=?", role.toString(), PermissionCatalog.MATERIAL_READ.registrationId().toString());
        assertFalse(permissions.findByUserId(initial, "bootstrap-catalog").orElseThrow().permits(PermissionCatalog.MATERIAL_READ.permission()));
        bootstrap.initialize(initial);
        assertEquals(PermissionCatalog.permissions(), permissions.findByUserId(initial, "bootstrap-catalog").orElseThrow().permissions());
        assertEquals(Set.of(role), work.execute(() -> administration.findRoleIds(initial)));
    }

    /** 非初始或禁用账号不能完成首次绑定，失败不会提交初始化标记或成员关系。 */
    @Test
    void invalidInitialAccountRollsBackBindingAndMarker() {
        UUID ordinary = account(false);
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> bootstrap.initialize(ordinary)).getErrorCode());
        assertFalse(work.execute(administration::isInitialized));
        assertTrue(work.execute(() -> administration.findRoleIds(ordinary)).isEmpty());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", initial.toString());
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> bootstrap.initialize(initial)).getErrorCode());
        assertFalse(work.execute(administration::isInitialized));
        assertTrue(work.execute(() -> administration.findRoleIds(initial)).isEmpty());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> work.execute(() -> bootstrap.initialize(initial)));
    }

    /** 初始化标志已更新后发生故障，也必须回滚同一工作单元中的账号角色绑定。 */
    @Test
    void failureAfterMarkerWriteRollsBackMembershipAndMarker() {
        var failing = mock(AuthorizationAdministrationRepository.class, delegatesTo(administration));
        doAnswer(invocation -> {
            administration.markInitialized(); throw new IllegalStateException("标记后模拟故障");
        }).when(failing).markInitialized();
        var service = new BootstrapAuthorizationApplicationService(failing, roles, accounts, work);
        assertThrows(IllegalStateException.class, () -> service.initialize(initial));
        assertFalse(work.execute(administration::isInitialized));
        assertTrue(work.execute(() -> administration.findRoleIds(initial)).isEmpty());
        bootstrap.initialize(initial);
        assertTrue(work.execute(administration::isInitialized));
        assertEquals(PermissionCatalog.permissions(), permissions.findByUserId(initial, "bootstrap-retry").orElseThrow().permissions());
    }

    /** 并发启动经协调行收敛为相同角色与一次成员绑定。
     * @throws Exception 并发启动异常或超过有界等待
     */
    @Test
    void concurrentInitializersConverge() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var calls = new ArrayList<java.util.concurrent.Future<UUID>>();
            for (int index = 0; index < 3; index++) calls.add(executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("启动超时");
                return bootstrap.initialize(initial);
            }));
            start.countDown(); UUID first = calls.getFirst().get(10, TimeUnit.SECONDS);
            for (var call : calls) assertEquals(first, call.get(10, TimeUnit.SECONDS));
            assertEquals(Set.of(first), work.execute(() -> administration.findRoleIds(initial)));
        }
        assertEquals(1, work.execute(administration::countEnabledAdministrators));
    }

    /**
     * 生成并保存独立账号，不保存原始密码。
     * @param initialAccount 是否占用唯一初始化槽
     * @return 账号UUID
     */
    private UUID account(boolean initialAccount) {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("grant-it-" + UUID.randomUUID(), passwords.hash(raw), initialAccount);
            UUID id = UUID.fromString(account.getId()); users.add(id);
            work.execute(() -> { accounts.insert(account); return null; }); return id;
        } finally { Arrays.fill(raw, '\0'); }
    }
}
