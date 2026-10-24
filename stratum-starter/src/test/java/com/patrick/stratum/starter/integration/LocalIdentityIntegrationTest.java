package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.UUID;

import com.patrick.stratum.application.auth.LocalIdentityApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实BCrypt和MariaDB凭据查询验证本地身份，不以账号初始化标记绕过验证。 */
class LocalIdentityIntegrationTest extends MariaDbIntegrationTest {
    private final LocalIdentityApplicationService identities;
    private final LocalAccountRepository accounts;
    private final AccountCredentials credentials;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final String username = "identity" + UUID.randomUUID();

    /**
     * 装配真实身份及读写路径。
     * @param identities 身份入口
     * @param accounts 夹具写仓储
     * @param credentials 查询端口
     * @param passwords BCrypt适配器
     * @param unitOfWork 写工作单元
     * @param jdbc 清理和禁用夹具客户端
     */
    @Autowired
    LocalIdentityIntegrationTest(LocalIdentityApplicationService identities, LocalAccountRepository accounts,
            AccountCredentials credentials, PasswordHasher passwords, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.identities = identities; this.accounts = accounts; this.credentials = credentials;
        this.passwords = passwords; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 只清除本测试账号。 */
    @AfterEach
    void cleanUp() { jdbc.update("DELETE FROM auth_user WHERE username = ?", username); }

    /** 成功返回领域账号ID，错误密码/大小写/未知/禁用等失败具有相同外部语义。 */
    @Test
    void verifiesIdentityAndUniformlyRejectsInvalidCredentials() {
        char[] original = UUID.randomUUID().toString().toCharArray();
        try {
            var account = create(original);
            char[] supplied = original.clone();
            assertEquals(UUID.fromString(account.getId()), identities.authenticate(username, supplied, "identity-test"));
            assertArrayEquals(new char[supplied.length], supplied);
            rejects(username, UUID.randomUUID().toString().toCharArray());
            rejects(username.toUpperCase(java.util.Locale.ROOT), original.clone());
            rejects("unknown" + UUID.randomUUID(), original.clone());
            rejects("invalid user", original.clone());
            rejects(null, original.clone());
            rejects(username, null);
            rejects(username, new char[0]);
            rejects(username, "x".repeat(73).toCharArray());
            jdbc.update("UPDATE auth_user SET enabled = 0 WHERE id = ?", account.getId());
            rejects(username, original.clone());
        } finally { Arrays.fill(original, '\0'); }
    }

    /** 已知、未知、非法和禁用用户名均经过一次散列匹配，未知路径使用随机占位散列。 */
    @Test
    void unknownAndDisabledAccountsStillPerformPasswordComparison() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            create(raw);
            int[] comparisons = {0};
            PasswordHasher counting = new PasswordHasher() {
                /** {@inheritDoc} */
                @Override public String hash(char[] password) { return passwords.hash(password); }
                /** {@inheritDoc} */
                @Override public boolean matches(char[] password, String encoded) {
                    comparisons[0]++;
                    assertTrue(PasswordHasher.supported(encoded));
                    return passwords.matches(password, encoded);
                }
            };
            var service = new LocalIdentityApplicationService(credentials, counting);
            service.authenticate(username, raw.clone(), "identity-test");
            assertThrows(StratumException.class, () -> service.authenticate("unknown" + UUID.randomUUID(), raw.clone(), "identity-test"));
            assertThrows(StratumException.class, () -> service.authenticate("invalid username", raw.clone(), "identity-test"));
            jdbc.update("UPDATE auth_user SET enabled = 0 WHERE username = ?", username);
            assertThrows(StratumException.class, () -> service.authenticate(username, raw.clone(), "identity-test"));
            assertEquals(4, comparisons[0]);
        } finally { Arrays.fill(raw, '\0'); }
    }

    /** 查询故障不允许登录且保留内部错误分类，密码在异常路径也清零。 */
    @Test
    void storageFailureCannotAuthenticate() {
        AccountCredentials unavailable = (name, trace) -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); };
        var service = new LocalIdentityApplicationService(unavailable, passwords);
        char[] supplied = UUID.randomUUID().toString().toCharArray();
        var failure = assertThrows(StratumException.class, () -> service.authenticate(username, supplied, "identity-test"));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertArrayEquals(new char[supplied.length], supplied);
        assertNull(failure.getCause());
    }

    /** 认证读取仍受QueryGateway事务限制，不能沿写仓储读取凭据。 */
    @Test
    void refusesCredentialQueryInTransaction() {
        char[] supplied = UUID.randomUUID().toString().toCharArray();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> identities.authenticate(username, supplied, "identity-test")));
        assertArrayEquals(new char[supplied.length], supplied);
    }

    /**
     * 验证认证拒绝的错误码、脱敏信息和密码清理。
     * @param name 待验证登录标识
     * @param password 由应用消费的密码
     */
    private void rejects(String name, char[] password) {
        var failure = assertThrows(StratumException.class, () -> identities.authenticate(name, password, "identity-test"));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertEquals(CommonErrorCode.UNAUTHORIZED.getDefaultMessage(), failure.getMessage());
        assertTrue(failure.getDetails().isEmpty());
        assertNull(failure.getCause());
        if (password != null) { assertArrayEquals(new char[password.length], password); }
    }

    /**
     * 创建单个随机普通账号测试夹具。
     * @param password 用于散列的密码，调用方负责清理
     * @return 已持久化账号
     */
    private LocalAccount create(char[] password) {
        var account = LocalAccount.create(username, passwords.hash(password), false);
        unitOfWork.execute(() -> { accounts.insert(account); return null; });
        return account;
    }
}
