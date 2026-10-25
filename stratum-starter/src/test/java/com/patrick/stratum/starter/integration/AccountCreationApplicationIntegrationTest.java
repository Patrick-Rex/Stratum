package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.AccountCreationApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.LocalIdentityApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实管理员创建普通账号的默认授权、凭据清理、并发与授权竞争。 */
class AccountCreationApplicationIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private AccountPermissions permissions;
    @Autowired private LocalIdentityApplicationService identities;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> usernames = new ArrayList<>();
    private UUID administrator;
    private String createdRole;

    /** 通过正式仓储准备本测试管理员，角色关系不依赖初始化账号标记。 */
    @BeforeEach
    void prepare() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var admin = LocalAccount.create(username(), passwords.hash(raw), false);
            administrator = UUID.fromString(admin.getId());
            work.execute(() -> {
                administration.lock(); roles.registerCatalog();
                var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                    var created = AuthorizationRole.administrator(); createdRole = created.getId(); roles.save(created); return created;
                });
                accounts.insert(admin); administration.replaceRoleIds(administrator, Set.of(UUID.fromString(role.getId()))); return null;
            });
        } finally { Arrays.fill(raw, '\0'); }
    }

    /** 只删除本用例登记的随机账号、角色与关系。 */
    @AfterEach
    void clean() {
        for (String username : usernames) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id IN (SELECT id FROM auth_user WHERE username=?)", username);
            jdbc.update("DELETE FROM auth_user WHERE username=?", username);
        }
        if (createdRole != null) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", createdRole);
            jdbc.update("DELETE FROM auth_role WHERE id=?", createdRole);
        }
    }

    /** 创建仅保存散列、保持无默认角色和权限，成功及身份验证后密码数组均被清零。 */
    @Test
    void createsEnabledOrdinaryAccountWithoutPermissions() {
        String username = username(); char[] raw = UUID.randomUUID().toString().toCharArray(); char[] verify = raw.clone();
        UUID id = service(administrator, passwords, work).create(username, raw);
        assertArrayEquals(new char[raw.length], raw);
        var account = work.execute(() -> accounts.findById(id.toString()).orElseThrow());
        assertTrue(account.isEnabled()); assertFalse(account.isBootstrapAdministrator()); assertEquals(0L, account.getVersion());
        assertTrue(PasswordHasher.supported(account.getPasswordHash()));
        assertTrue(work.execute(() -> administration.findRoleIds(id)).isEmpty());
        assertTrue(permissions.findByUserId(id, "creation-test").orElseThrow().permissions().isEmpty());
        assertEquals(id, identities.authenticate(username, verify, "creation-test"));
        assertArrayEquals(new char[verify.length], verify);
    }

    /** 未认证和普通账号在散列之前被拒绝，拒绝路径也清零密码。 */
    @Test
    void rejectsOrdinaryActorBeforeHashingAndClearsPassword() {
        UUID ordinary = service(administrator, passwords, work).create(username(), UUID.randomUUID().toString().toCharArray());
        PasswordHasher never = new PasswordHasher() {
            /** {@inheritDoc} */
            @Override public String hash(char[] raw) { throw new AssertionError("未授权调用不得计算散列"); }
            /** {@inheritDoc} */
            @Override public boolean matches(char[] raw, String encoded) { throw new AssertionError("本用例不执行比较"); }
        };
        String username = username(); char[] raw = UUID.randomUUID().toString().toCharArray();
        var unauthenticated = new AccountCreationApplicationService(() -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); },
                administration, accounts, never, work);
        char[] anonymous = raw.clone();
        error(CommonErrorCode.UNAUTHORIZED, () -> unauthenticated.create(username, anonymous));
        assertArrayEquals(new char[anonymous.length], anonymous);
        error(CommonErrorCode.FORBIDDEN, () -> service(ordinary, never, work).create(username, raw));
        assertArrayEquals(new char[raw.length], raw); assertMissing(username);
    }

    /** 重复用户名和输入越界不能留下新账号或覆盖旧散列。 */
    @Test
    void rejectsDuplicateAndInvalidCredentialsWithoutOverwriting() {
        String username = username(); var service = service(administrator, passwords, work);
        UUID id = service.create(username, UUID.randomUUID().toString().toCharArray());
        String original = work.execute(() -> accounts.findById(id.toString()).orElseThrow()).getPasswordHash();
        char[] duplicate = UUID.randomUUID().toString().toCharArray();
        error(CommonErrorCode.CONFLICT, () -> service.create(username, duplicate));
        assertArrayEquals(new char[duplicate.length], duplicate);
        assertEquals(original, work.execute(() -> accounts.findById(id.toString()).orElseThrow()).getPasswordHash());
        String invalidUser = username(); char[] tooLong = "x".repeat(73).toCharArray();
        assertThrows(IllegalArgumentException.class, () -> service.create(invalidUser, tooLong));
        assertArrayEquals(new char[tooLong.length], tooLong); assertMissing(invalidUser);
    }

    /** 散列计算不持写事务，其间失去管理员角色后，最终写入重新鉴权并拒绝。 */
    @Test
    void rechecksAdministratorAfterHashingOutsideTransaction() {
        PasswordHasher revoked = new PasswordHasher() {
            /** {@inheritDoc} */
            @Override public String hash(char[] raw) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                String hash = passwords.hash(raw);
                work.execute(() -> { administration.replaceRoleIds(administrator, Set.of()); return null; });
                return hash;
            }
            /** {@inheritDoc} */
            @Override public boolean matches(char[] raw, String encoded) { return passwords.matches(raw, encoded); }
        };
        String username = username(); char[] raw = UUID.randomUUID().toString().toCharArray();
        error(CommonErrorCode.FORBIDDEN, () -> service(administrator, revoked, work).create(username, raw));
        assertArrayEquals(new char[raw.length], raw); assertMissing(username);
    }

    /** 提交前故障导致账号整体回滚且不返回UUID，密码同样清零。 */
    @Test
    void rollsBackOnCommitFailureAndClearsPassword() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        UnitOfWork failing = new UnitOfWork() {
            /** {@inheritDoc} */
            @Override public <T> T execute(java.util.function.Supplier<T> action) {
                return work.execute(() -> {
                    if (calls.incrementAndGet() == 2) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            /** {@inheritDoc} */
                            @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("测试提交失败"); }
                        });
                    }
                    return action.get();
                });
            }
        };
        String username = username(); char[] raw = UUID.randomUUID().toString().toCharArray();
        assertThrows(IllegalStateException.class, () -> service(administrator, passwords, failing).create(username, raw));
        assertArrayEquals(new char[raw.length], raw); assertMissing(username);
    }

    /**
     * 并发创建同名账号只能提交一份，另一个调用返回冲突。
     * @throws Exception 并发调用失败或超时
     */
    @Test
    void concurrentDuplicateCreatesOnlyOneAccount() throws Exception {
        String username = username(); var service = service(administrator, passwords, work);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                char[] raw = UUID.randomUUID().toString().toCharArray();
                try { service.create(username, raw); return 1; }
                catch (StratumException failure) { assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 0; }
                finally { assertArrayEquals(new char[raw.length], raw); }
            })).toList();
            assertEquals(1, jobs.getFirst().get(15, TimeUnit.SECONDS) + jobs.getLast().get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user WHERE username=?", Integer.class, username));
    }

    /**
     * 组装管理用例并显式提供可信操作者。
     * @param actor 服务器操作者UUID
     * @param hasher 密码散列端口
     * @param unit 工作单元
     * @return 管理应用用例
     */
    private AccountCreationApplicationService service(UUID actor, PasswordHasher hasher, UnitOfWork unit) {
        return new AccountCreationApplicationService(() -> new CurrentIdentity.Snapshot(actor, UUID.randomUUID(), 0),
                administration, accounts, hasher, unit);
    }

    /** @return 已登记供清理的随机测试用户名 */
    private String username() { String name = "create-it-" + UUID.randomUUID(); usernames.add(name); return name; }

    /**
     * 断言失败没有留下账号。
     * @param username 本用例用户名
     */
    private void assertMissing(String username) {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user WHERE username=?", Integer.class, username));
    }

    /**
     * 校验统一错误且不保留敏感原因。
     * @param code 预期错误码
     * @param action 被拒绝操作
     */
    private static void error(CommonErrorCode code, Runnable action) {
        var failure = assertThrows(StratumException.class, action::run);
        assertEquals(code, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
