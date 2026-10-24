package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.BootstrapAccountApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证初始化账号首次创建、重复不覆盖及并发唯一提交。 */
class BootstrapAccountIntegrationTest extends MariaDbIntegrationTest {
    private final BootstrapAccountApplicationService bootstrap;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final String username = "bootstrap" + UUID.randomUUID();

    /**
     * 装配应用入口及真实事务适配器。
     * @param bootstrap 初始化入口
     * @param accounts 写仓储
     * @param passwords BCrypt适配器
     * @param unitOfWork 事务工作单元
     * @param jdbc 测试清理客户端
     */
    @Autowired
    BootstrapAccountIntegrationTest(BootstrapAccountApplicationService bootstrap, LocalAccountRepository accounts,
            PasswordHasher passwords, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.bootstrap = bootstrap; this.accounts = accounts; this.passwords = passwords;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 仅删除本用例随机用户名创建的账号。 */
    @AfterEach
    void cleanUp() { jdbc.update("DELETE FROM auth_user WHERE username = ?", username); }

    /** 首次创建只落散列，后续缺少或改变输入都不重置账号与禁用状态。 */
    @Test
    void initializesOnceWithoutResettingExistingCredentials() {
        char[] original = UUID.randomUUID().toString().toCharArray();
        char[] supplied = original.clone();
        try {
            UUID id = bootstrap.initialize(username, supplied);
            assertArrayEquals(new char[supplied.length], supplied);
            var account = unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow();
            assertEquals(id.toString(), account.getId());
            assertTrue(passwords.matches(original, account.getPasswordHash()));
            assertEquals(0L, account.getVersion());
            jdbc.update("UPDATE auth_user SET enabled = 0 WHERE id = ?", id.toString());
            assertEquals(id, bootstrap.initialize(null, null));
            char[] replacement = UUID.randomUUID().toString().toCharArray();
            assertEquals(id, bootstrap.initialize("different", replacement));
            assertArrayEquals(new char[replacement.length], replacement);
            var after = unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow();
            assertEquals(username, after.getUsername());
            assertEquals(account.getPasswordHash(), after.getPasswordHash());
            assertFalse(after.isEnabled());
        } finally { Arrays.fill(original, '\0'); }
    }

    /** 首次缺失、非法用户名或密码不得落库，也不留下未清理密码。 */
    @Test
    void rejectsMissingOrInvalidFirstCredentials() {
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize(null, null));
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize(username, null));
        char[] supplied = UUID.randomUUID().toString().toCharArray();
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize("invalid user", supplied));
        assertArrayEquals(new char[supplied.length], supplied);
        assertTrue(unitOfWork.execute(accounts::findBootstrapAdministrator).isEmpty());
    }

    /** 已有普通账号占用用户名时不升级该账号，也不覆盖其凭据。 */
    @Test
    void refusesToPromoteExistingOrdinaryAccount() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var ordinary = LocalAccount.create(username, passwords.hash(raw), false);
            unitOfWork.execute(() -> { accounts.insert(ordinary); return null; });
            var failure = assertThrows(StratumException.class, () -> bootstrap.initialize(username, raw));
            assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
            assertNull(failure.getCause());
            assertTrue(unitOfWork.execute(accounts::findBootstrapAdministrator).isEmpty());
            assertEquals(ordinary.getPasswordHash(), unitOfWork.execute(() -> accounts.findById(ordinary.getId())).orElseThrow().getPasswordHash());
        } finally { Arrays.fill(raw, '\0'); }
    }

    /** 两次首次初始化同时抵达计算阶段，数据库最终保留一个账号及一份原始散列。 */
    @Test
    void concurrentInitializationConvergesWithoutOverwriting() throws Exception {
        var barrier = new CyclicBarrier(2);
        PasswordHasher concurrentPasswords = new PasswordHasher() {
            /** {@inheritDoc} */
            @Override public String hash(char[] password) {
                String result = passwords.hash(password);
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException("并发夹具未就绪", failure); }
                return result;
            }
            /** {@inheritDoc} */
            @Override public boolean matches(char[] password, String encoded) { return passwords.matches(password, encoded); }
        };
        var concurrent = new BootstrapAccountApplicationService(accounts, concurrentPasswords, unitOfWork);
        char[] first = UUID.randomUUID().toString().toCharArray();
        char[] second = UUID.randomUUID().toString().toCharArray();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> concurrent.initialize(username, first.clone()));
            var right = executor.submit(() -> concurrent.initialize(username, second.clone()));
            assertEquals(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            var account = unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow();
            assertTrue(passwords.matches(first, account.getPasswordHash()) ^ passwords.matches(second, account.getPasswordHash()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user WHERE username = ?", Integer.class, username));
        } finally { Arrays.fill(first, '\0'); Arrays.fill(second, '\0'); }
    }

    /** 禁止参与调用方已有事务，以保证冲突后的恢复读取处于干净事务中。 */
    @Test
    void refusesEnclosingTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> unitOfWork.execute(() -> bootstrap.initialize(null, null)));
    }
}
