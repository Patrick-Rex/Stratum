package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证账号约束与写事务；所有测试只清理自己生成的ID。 */
class LocalAccountRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> ids = new ArrayList<>();

    /**
     * 装配账号仓储、真实散列器及事务设施。
     * @param accounts 写仓储
     * @param passwords 密码散列器
     * @param unitOfWork 工作单元
     * @param jdbc 仅用于断言及测试数据清理
     */
    @Autowired
    LocalAccountRepositoryIntegrationTest(LocalAccountRepository accounts, PasswordHasher passwords,
            UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.accounts = accounts; this.passwords = passwords; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 删除当前测试插入的账号，不清空账号表。 */
    @AfterEach
    void cleanUp() { for (String id : ids) { jdbc.update("DELETE FROM auth_user WHERE id = ?", id); } }

    /** 保存与重建仅含散列、保持身份版本；插入不能覆盖已持久化账号。 */
    @Test
    void storesOnlyHashAndDoesNotOverwriteAccounts() {
        var account = account("U" + UUID.randomUUID(), false);
        persist(account);
        var loaded = unitOfWork.execute(() -> accounts.findById(account.getId()).orElseThrow());
        assertEquals(account.getUsername(), loaded.getUsername());
        assertEquals(account.getPasswordHash(), loaded.getPasswordHash());
        assertEquals(0L, loaded.getVersion());
        assertTrue(loaded.isEnabled());
        assertFalse(loaded.isBootstrapAdministrator());
        assertEquals(account.getPasswordHash(), jdbc.queryForObject("SELECT password_hash FROM auth_user WHERE id = ?", String.class, account.getId()));
        assertFalse(loaded.toString().contains(loaded.getPasswordHash()));
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> persist(loaded)).getErrorCode());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_user WHERE id = ?", Long.class, account.getId()));
    }

    /** 用户名大小写精确匹配，重复用户名返回脱敏冲突且保留原密码。 */
    @Test
    void enforcesExactUsernameUniqueness() {
        String suffix = UUID.randomUUID().toString();
        var first = account("User" + suffix, false);
        var second = account("user" + suffix, false);
        persist(first); persist(second);
        var duplicate = account(first.getUsername(), false);
        var failure = assertThrows(StratumException.class, () -> persist(duplicate));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertNull(failure.getCause());
        assertTrue(failure.getDetails().isEmpty());
        assertEquals(first.getPasswordHash(), jdbc.queryForObject("SELECT password_hash FROM auth_user WHERE id = ?", String.class, first.getId()));
    }

    /** 多个普通账号允许并存，初始管理员槽只能被一个账号占用。 */
    @Test
    void allowsOnlyOneBootstrapAccount() {
        var first = account("bootstrap" + UUID.randomUUID(), true);
        persist(first);
        assertEquals(first.getId(), unitOfWork.execute(() -> accounts.findBootstrapAdministrator().orElseThrow()).getId());
        var duplicate = account("bootstrap" + UUID.randomUUID(), true);
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> persist(duplicate)).getErrorCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user WHERE bootstrap_slot = 1", Integer.class));
    }

    /** 工作单元抛出异常时账号插入回滚，事务外读写均不能绕过边界。 */
    @Test
    void rollsBackAndRejectsUseOutsideWriteTransaction() {
        var account = account("rollback" + UUID.randomUUID(), false);
        assertThrows(IllegalStateException.class, () -> accounts.insert(account));
        assertThrows(IllegalStateException.class, () -> accounts.findById(account.getId()));
        assertThrows(IllegalStateException.class, accounts::findBootstrapAdministrator);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            accounts.insert(account);
            throw new IllegalStateException("触发回滚");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user WHERE id = ?", Integer.class, account.getId()));
        assertTrue(unitOfWork.execute(() -> accounts.findById("' OR '1'='1")).isEmpty());
    }

    /** 即使绕过领域模型，数据库仍拒绝明文、非法用户名和非法初始化槽。 */
    @Test
    void databaseRejectsInvalidCredentialState() {
        var account = account("constraints" + UUID.randomUUID(), false);
        persist(account);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE auth_user SET password_hash = ? WHERE id = ?", "not-a-hash", account.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE auth_user SET username = ? WHERE id = ?", "invalid user", account.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE auth_user SET bootstrap_slot = 2 WHERE id = ?", account.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE auth_user SET enabled = 2 WHERE id = ?", account.getId()));
    }

    /**
     * 并发首次初始化时唯一约束保证仅有一个管理员账号提交。
     * @throws Exception 并发任务失败
     */
    @Test
    void concurrentBootstrapInsertionCommitsOnce() throws Exception {
        var first = account("init" + UUID.randomUUID(), true);
        var second = account("init" + UUID.randomUUID(), true);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var outcomes = List.of(first, second).stream().map(account -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { persist(account); return true; }
                catch (StratumException failure) {
                    assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
                    return false;
                }
            })).toList();
            start.countDown();
            int count = 0;
            for (var outcome : outcomes) { if (outcome.get(5, TimeUnit.SECONDS)) { count++; } }
            assertEquals(1, count);
        } finally { start.countDown(); }
    }

    /** 状态更新使用乐观版本，过期请求及伪造固定字段不能覆盖账号身份与凭据。 */
    @Test
    void updatesStatusWithoutOverwritingFixedFields() {
        var original = account("status" + UUID.randomUUID(), true);
        persist(original);
        var stale = unitOfWork.execute(() -> accounts.findById(original.getId()).orElseThrow());
        var modified = LocalAccount.restore(original.getId(), "changed", "$2b$10$" + "a".repeat(53), false, false, 0);
        unitOfWork.execute(() -> { accounts.updateStatus(modified); return null; });
        assertEquals(1L, modified.getVersion());
        var loaded = unitOfWork.execute(() -> accounts.findById(original.getId()).orElseThrow());
        assertFalse(loaded.isEnabled());
        assertTrue(loaded.isBootstrapAdministrator());
        assertEquals(original.getUsername(), loaded.getUsername());
        assertEquals(original.getPasswordHash(), loaded.getPasswordHash());
        var failure = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            accounts.updateStatus(stale); return null;
        }));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertNull(failure.getCause());
        loaded.changeEnabled(true);
        unitOfWork.execute(() -> { accounts.updateStatus(loaded); return null; });
        assertTrue(unitOfWork.execute(() -> accounts.findById(original.getId()).orElseThrow()).isEnabled());
        assertEquals(2L, loaded.getVersion());
    }

    /** 回滚保留旧状态和版本；未持久化或溢出版本不能绕过写入校验。 */
    @Test
    void rollsBackStatusAndRejectsInvalidVersions() {
        var account = account("rollbackStatus" + UUID.randomUUID(), false);
        assertThrows(IllegalStateException.class, () -> accounts.updateStatus(account));
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            accounts.updateStatus(account); return null;
        })).getErrorCode());
        persist(account);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            var loaded = accounts.findById(account.getId()).orElseThrow();
            loaded.changeEnabled(false);
            accounts.updateStatus(loaded);
            throw new IllegalStateException("测试回滚");
        }));
        var loaded = unitOfWork.execute(() -> accounts.findById(account.getId()).orElseThrow());
        assertTrue(loaded.isEnabled());
        assertEquals(0L, loaded.getVersion());
        jdbc.update("UPDATE auth_user SET version = ? WHERE id = ?", Long.MAX_VALUE, account.getId());
        var overflow = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            var maximum = accounts.findById(account.getId()).orElseThrow();
            maximum.changeEnabled(false);
            accounts.updateStatus(maximum); return null;
        }));
        assertEquals(CommonErrorCode.CONFLICT, overflow.getErrorCode());
        assertNull(overflow.getCause());
        assertTrue(unitOfWork.execute(() -> accounts.findById(account.getId()).orElseThrow()).isEnabled());
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT version FROM auth_user WHERE id = ?", Long.class, account.getId()));
    }

    /** 旧事务快照遇到并发状态提交时失败关闭，新的工作单元读到已提交状态。 */
    @Test
    void refusesStaleSnapshotAfterConcurrentStatusChange() {
        var account = account("snapshot" + UUID.randomUUID(), false);
        persist(account);
        assertEquals(1, jdbc.queryForObject("SELECT @@innodb_snapshot_isolation", Integer.class));
        var failure = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            assertTrue(jdbc.queryForObject("SELECT enabled FROM auth_user WHERE id = ?", Boolean.class, account.getId()));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> unitOfWork.execute(() -> {
                    var current = accounts.findById(account.getId()).orElseThrow();
                    current.changeEnabled(false);
                    accounts.updateStatus(current); return null;
                })).get(10, TimeUnit.SECONDS);
            } catch (Exception unexpected) { throw new AssertionError("并发状态更新失败", unexpected); }
            return accounts.findById(account.getId());
        }));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertNull(failure.getCause());
        var current = unitOfWork.execute(() -> accounts.findById(account.getId()).orElseThrow());
        assertFalse(current.isEnabled());
        assertEquals(1L, current.getVersion());
    }

    /**
     * 创建随机密码的账号夹具，原始数组在散列后立即清零。
     * @param username 用户名
     * @param bootstrap 初始化标记
     * @return 未持久化聚合
     */
    private LocalAccount account(String username, boolean bootstrap) {
        char[] password = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create(username, passwords.hash(password), bootstrap);
            ids.add(account.getId());
            return account;
        } finally { Arrays.fill(password, '\0'); }
    }

    /**
     * 在真实Application工作单元中插入账号。
     * @param account 待插入账号
     */
    private void persist(LocalAccount account) {
        unitOfWork.execute(() -> { accounts.insert(account); return null; });
    }
}
