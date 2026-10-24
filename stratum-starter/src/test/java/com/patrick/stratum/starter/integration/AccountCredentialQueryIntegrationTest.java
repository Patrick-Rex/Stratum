package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AccountCredentialQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实MariaDB验证认证内部查询的精确条件、事务边界与脱敏快照。 */
class AccountCredentialQueryIntegrationTest extends MariaDbIntegrationTest {
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final QueryGateway gateway;
    private final AccountCredentials credentials;
    private final List<String> ids = new ArrayList<>();

    /**
     * 装配真实读写路径；写仓储仅用于测试夹具。
     * @param accounts 账号仓储
     * @param passwords 散列器
     * @param unitOfWork 工作单元
     * @param jdbc 断言与清理客户端
     * @param gateway 查询网关
     * @param credentials 应用依赖的凭据端口
     */
    @Autowired
    AccountCredentialQueryIntegrationTest(LocalAccountRepository accounts, PasswordHasher passwords,
            UnitOfWork unitOfWork, JdbcTemplate jdbc, QueryGateway gateway, AccountCredentials credentials) {
        this.accounts = accounts; this.passwords = passwords; this.unitOfWork = unitOfWork;
        this.jdbc = jdbc; this.gateway = gateway;
        this.credentials = credentials;
    }

    /** 清除自己的随机账号。 */
    @AfterEach
    void cleanUp() { for (String id : ids) { jdbc.update("DELETE FROM auth_user WHERE id = ?", id); } }

    /** 精确用户名只返回唯一账号，禁用状态保留给后续认证判断。 */
    @Test
    void readsExactAccountAndDisabledState() {
        String suffix = UUID.randomUUID().toString();
        var upper = create("User" + suffix);
        var lower = create("user" + suffix);
        var context = QueryContext.anonymous("auth-query-test");
        var result = gateway.execute(AccountCredentialQuery.create(upper.getUsername()), context);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getPageSize());
        var snapshot = result.getItems().getFirst();
        assertEquals(upper.getId(), snapshot.id().toString());
        assertEquals(upper.getPasswordHash(), snapshot.passwordHash());
        assertTrue(snapshot.enabled());
        assertEquals("AccountCredentials[内容已隐藏]", snapshot.toString());
        jdbc.update("UPDATE auth_user SET enabled = 0 WHERE id = ?", lower.getId());
        var disabled = gateway.execute(AccountCredentialQuery.create(lower.getUsername()), context).getItems().getFirst();
        assertFalse(disabled.enabled());
        assertEquals(lower.getId(), disabled.id().toString());
    }

    /** 未知用户名返回空快照，格式非法的注入与控制输入在查询前拒绝。 */
    @Test
    void rejectsInvalidInputAndReturnsEmptyForUnknownAccount() {
        assertTrue(gateway.execute(AccountCredentialQuery.create("unknown" + UUID.randomUUID()),
                QueryContext.anonymous("auth-query-test")).getItems().isEmpty());
        for (String username : List.of("' OR '1'='1", "admin ", "name\n", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> AccountCredentialQuery.create(username));
        }
    }

    /** 内部凭据查询也不能绕过QueryGateway的活动事务拒绝。 */
    @Test
    void refusesQueryInsideWriteTransaction() {
        var account = create("transaction" + UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() ->
                gateway.execute(AccountCredentialQuery.create(account.getUsername()), QueryContext.anonymous("auth-query-test"))));
    }

    /** 领域端口与查询网关结果一致，保留未知账号、链路和事务边界。 */
    @Test
    void adaptsCredentialPortThroughGateway() {
        var account = create("port" + UUID.randomUUID());
        var result = credentials.findByUsername(account.getUsername(), "credential-port-test").orElseThrow();
        assertEquals(account.getId(), result.id().toString());
        assertEquals(account.getPasswordHash(), result.passwordHash());
        assertTrue(credentials.findByUsername("unknown" + UUID.randomUUID(), "credential-port-test").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> credentials.findByUsername(account.getUsername(), "invalid\ntrace"));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() ->
                credentials.findByUsername(account.getUsername(), "credential-port-test")));
    }

    /**
     * 创建随机密码账号，及时擦除原始数组。
     * @param username 精确登录标识
     * @return 已持久化账号
     */
    private LocalAccount create(String username) {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create(username, passwords.hash(raw), false);
            ids.add(account.getId());
            unitOfWork.execute(() -> { accounts.insert(account); return null; });
            return account;
        } finally { Arrays.fill(raw, '\0'); }
    }
}
