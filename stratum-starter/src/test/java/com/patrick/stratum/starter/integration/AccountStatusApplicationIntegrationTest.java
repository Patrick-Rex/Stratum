package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.AccountStatusApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountRevocations;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库和Redis验证管理员启停编排、并发保护及跨存储失败结果。 */
class AccountStatusApplicationIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private AccountRevocations revocations;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private AccountStatusApplicationService configuredService;
    private final List<UUID> users = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private UUID administratorRole;
    private String createdRole;
    private UUID administrator;

    /** 使用已有目录建立独立管理员夹具，不修改初始化标记。 */
    @BeforeEach
    void prepare() {
        administratorRole = work.execute(() -> {
            administration.lock(); roles.registerCatalog();
            var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                var created = AuthorizationRole.administrator(); createdRole = created.getId(); roles.save(created); return created;
            });
            assertEquals(0, administration.countEnabledAdministrators());
            return UUID.fromString(role.getId());
        });
        administrator = account(true);
    }

    /** 删除本测试创建的账号、关系及撤销键，保留原有目录。 */
    @AfterEach
    void clean() {
        for (UUID user : users) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", user.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", user.toString());
        }
        if (createdRole != null) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", createdRole);
            jdbc.update("DELETE FROM auth_role WHERE id=?", createdRole);
        }
        if (!keys.isEmpty()) redis.delete(keys);
    }

    /** 停用和重新启用分别增加账号及撤销版本，相同状态不产生重复副作用。 */
    @Test
    void disablesAndEnablesWithRevocationOnBothTransitions() {
        UUID target = account(false);
        var service = service(administrator, revocations);
        assertEquals(0, service.changeEnabled(target, 0, true));
        assertFalse(redis.hasKey(key(target)));
        assertEquals(1, service.changeEnabled(target, 0, false));
        assertFalse(load(target).isEnabled());
        assertEquals("1", redis.opsForValue().get(key(target)));
        assertEquals(1, service.changeEnabled(target, 1, false));
        assertEquals("1", redis.opsForValue().get(key(target)));
        assertEquals(2, service.changeEnabled(target, 1, true));
        assertTrue(load(target).isEnabled());
        assertEquals("2", redis.opsForValue().get(key(target)));
    }

    /** 未认证、普通或停用管理员不能执行启停，也不能用目标ID代替操作者。 */
    @Test
    void requiresCurrentEnabledAdministrator() {
        UUID ordinary = account(false);
        var unauthenticated = new AccountStatusApplicationService(() -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); },
                administration, accounts, revocations, work);
        error(CommonErrorCode.UNAUTHORIZED, () -> unauthenticated.changeEnabled(ordinary, 0, false));
        error(CommonErrorCode.FORBIDDEN, () -> service(ordinary, revocations).changeEnabled(administrator, 0, false));
        UUID disabled = account(true);
        service(administrator, revocations).changeEnabled(disabled, 0, false);
        error(CommonErrorCode.FORBIDDEN, () -> service(disabled, revocations).changeEnabled(ordinary, 0, false));
        assertTrue(load(ordinary).isEnabled());
        assertFalse(redis.hasKey(key(ordinary)));
    }

    /** 最后管理员、过期版本和缺失目标均在写入及撤销前拒绝，应用入口不加入外层事务。 */
    @Test
    void protectsLastAdministratorAndValidatesTargetVersion() {
        var service = service(administrator, revocations);
        error(CommonErrorCode.CONFLICT, () -> service.changeEnabled(administrator, 0, false));
        UUID target = account(false);
        error(CommonErrorCode.CONFLICT, () -> service.changeEnabled(target, 1, false));
        error(CommonErrorCode.NOT_FOUND, () -> service.changeEnabled(UUID.randomUUID(), 0, false));
        assertThrows(IllegalArgumentException.class, () -> service.changeEnabled(target, -1, false));
        assertThrows(IllegalTransactionStateException.class, () -> work.execute(() -> configuredService.changeEnabled(target, 0, false)));
        assertTrue(load(administrator).isEnabled()); assertTrue(load(target).isEnabled());
        assertFalse(redis.hasKey(key(administrator))); assertFalse(redis.hasKey(key(target)));
    }

    /** 撤销故障使已执行的状态更新回滚，不能返回成功或留下状态版本变化。 */
    @Test
    void revocationFailureRollsBackDatabaseState() {
        UUID target = account(false);
        AccountRevocations failed = id -> {
            assertFalse(accounts.findById(id.toString()).orElseThrow().isEnabled());
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        };
        error(CommonErrorCode.INTERNAL_ERROR, () -> service(administrator, failed).changeEnabled(target, 0, false));
        var unchanged = load(target);
        assertTrue(unchanged.isEnabled()); assertEquals(0L, unchanged.getVersion());
        assertFalse(redis.hasKey(key(target)));
    }

    /** 数据库提交阶段失败时回滚状态，已经成功递增的撤销版本不能回退。 */
    @Test
    void commitFailureKeepsRevocationAndDoesNotReportSuccess() {
        UUID target = account(false);
        AccountRevocations failCommit = id -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** {@inheritDoc} */
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("测试提交失败"); }
            });
            return revocations.revoke(id);
        };
        assertThrows(IllegalStateException.class, () -> service(administrator, failCommit).changeEnabled(target, 0, false));
        var unchanged = load(target);
        assertTrue(unchanged.isEnabled()); assertEquals(0L, unchanged.getVersion());
        assertEquals("1", redis.opsForValue().get(key(target)));
    }

    /**
     * 两个管理员并发停用自己只允许一次提交，最后一名可用管理员必须保留。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentDisablesLeaveOneEnabledAdministrator() throws Exception {
        UUID second = account(true);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = List.of(administrator, second).stream().map(user -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { service(user, revocations).changeEnabled(user, 0, false); return 1; }
                catch (StratumException failure) { assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 0; }
            })).toList();
            start.countDown();
            assertEquals(1, jobs.getFirst().get(10, TimeUnit.SECONDS) + jobs.getLast().get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertEquals(1, work.execute(administration::countEnabledAdministrators));
        assertNotEquals(load(administrator).isEnabled(), load(second).isEnabled());
    }

    /**
     * 创建测试账号，明文密码及时清零，管理员身份仅来自角色关系。
     * @param admin 是否加入管理员角色
     * @return 独立账号UUID
     */
    private UUID account(boolean admin) {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("status-it-" + UUID.randomUUID(), passwords.hash(raw), false);
            UUID id = UUID.fromString(account.getId()); users.add(id); keys.add(key(id));
            work.execute(() -> {
                administration.lock(); accounts.insert(account);
                if (admin) administration.replaceRoleIds(id, Set.of(administratorRole));
                return null;
            });
            return id;
        } finally { Arrays.fill(raw, '\0'); }
    }

    /**
     * 为应用用例注入明确的服务器身份和真实工作单元。
     * @param actor 测试可信操作者
     * @param revocation 撤销端口，可注入故障
     * @return 应用用例
     */
    private AccountStatusApplicationService service(UUID actor, AccountRevocations revocation) {
        return new AccountStatusApplicationService(() -> new CurrentIdentity.Snapshot(actor, UUID.randomUUID(), 0),
                administration, accounts, revocation, work);
    }

    /**
     * 在新的工作单元恢复状态断言。
     * @param id 账号UUID
     * @return 最新聚合
     */
    private LocalAccount load(UUID id) { return work.execute(() -> accounts.findById(id.toString()).orElseThrow()); }

    /**
     * 生成测试账号的版本键。
     * @param id 账号UUID
     * @return 权威版本键
     */
    private static String key(UUID id) { return "auth:user:" + id + ":ver"; }

    /**
     * 核对无底层原因的统一业务错误。
     * @param code 预期错误码
     * @param action 目标操作
     */
    private static void error(CommonErrorCode code, Runnable action) {
        var failure = assertThrows(StratumException.class, action::run);
        assertEquals(code, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
