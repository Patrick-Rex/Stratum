package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.AccountStatusApplicationService;
import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.LocalIdentityApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.AccountRevocations;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.TokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实登录、数据库与Redis验证启停后令牌失效以及提交窗口内的登录和刷新竞争。 */
class AccountStatusAuthenticationIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private UnitOfWork work;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private AccountRevocations revocations;
    @Autowired private AuthSessionApplicationService authentication;
    @Autowired private LocalIdentityApplicationService identities;
    @Autowired private TokenCodec tokens;
    @Autowired private AuthSessions sessions;
    @Autowired private AccountPermissions permissions;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;
    private final String username = "lifecycle-" + UUID.randomUUID();
    private final char[] password = UUID.randomUUID().toString().toCharArray();
    private UUID target;

    /** 创建独立普通账号，HTTP登录继续使用真实密码散列验证。 */
    @BeforeEach
    void prepareTarget() {
        var account = LocalAccount.create(username, passwords.hash(password), false);
        target = UUID.fromString(account.getId());
        work.execute(() -> { accounts.insert(account); return null; });
    }

    /** 仅删除本用例账号，父夹具清理登录会话；原始凭据数组清零。 */
    @AfterEach
    void cleanTarget() {
        Arrays.fill(password, '\0');
        if (target != null) jdbc.update("DELETE FROM auth_user WHERE id=?", target.toString());
    }

    /**
     * 停用拒绝已有Access、Refresh与密码登录，重新启用必须重新登录获取新版本。
     * @throws Exception 网络请求失败
     */
    @Test
    void disabledAccountCannotAuthenticateAndOldTokensNeverRevive() throws Exception {
        var old = login(username, new String(password));
        var status = status(revocations);
        status.changeEnabled(target, 0, false);
        unauthorized(() -> authentication.authenticateAccess(old.accessToken()));
        assertEquals(401, postStatus("/auth/refresh", Map.of("refreshToken", old.refreshToken())));
        assertEquals(401, postStatus("/auth/login", Map.of("username", username, "password", new String(password))));
        status.changeEnabled(target, 1, true);
        unauthorized(() -> authentication.authenticateAccess(old.accessToken()));
        assertEquals(401, postStatus("/auth/refresh", Map.of("refreshToken", old.refreshToken())));
        var fresh = login(username, new String(password));
        assertEquals(2, authentication.authenticateAccess(fresh.accessToken()).version());
    }

    /**
     * 撤销已成功但停用尚未提交时并发登录获得的新版本，也不能在再次启用后复活。
     * @throws Exception 并发或网络请求失败
     */
    @Test
    void loginDuringDisableCommitWindowCannotReviveAfterEnable() throws Exception {
        var revoked = new CountDownLatch(1); var commit = new CountDownLatch(1);
        var status = status(id -> {
            long version = revocations.revoke(id); revoked.countDown(); await(commit); return version;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var disabling = executor.submit(() -> status.changeEnabled(target, 0, false));
            Session inWindow;
            try {
                await(revoked);
                inWindow = login(username, new String(password));
                assertEquals(1, authentication.authenticateAccess(inWindow.accessToken()).version());
            } finally { commit.countDown(); }
            assertEquals(1L, disabling.get(15, TimeUnit.SECONDS));
            unauthorized(() -> authentication.authenticateAccess(inWindow.accessToken()));
            status(revocations).changeEnabled(target, 1, true);
            unauthorized(() -> authentication.authenticateAccess(inWindow.accessToken()));
            assertEquals(401, postStatus("/auth/refresh", Map.of("refreshToken", inWindow.refreshToken())));
            assertEquals(2, authentication.authenticateAccess(login(username, new String(password)).accessToken()).version());
        }
    }

    /**
     * Refresh已读取启用状态但停用先提交时，Redis版本检查仍阻止其产生可返回的后继。
     * @throws Exception 并发或网络请求失败
     */
    @Test
    void refreshWithEarlierEnabledSnapshotFailsAfterDisable() throws Exception {
        var old = login(username, new String(password));
        var observed = new CountDownLatch(1); var rotate = new CountDownLatch(1);
        AccountPermissions delayed = (id, trace) -> {
            var snapshot = permissions.findByUserId(id, trace); observed.countDown(); await(rotate); return snapshot;
        };
        var refreshing = new AuthSessionApplicationService(identities, tokens, sessions, delayed);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> {
                unauthorized(() -> refreshing.refresh(old.refreshToken())); return true;
            });
            try { await(observed); status(revocations).changeEnabled(target, 0, false); }
            finally { rotate.countDown(); }
            assertTrue(result.get(15, TimeUnit.SECONDS));
        }
        status(revocations).changeEnabled(target, 1, true);
        assertEquals(401, postStatus("/auth/refresh", Map.of("refreshToken", old.refreshToken())));
    }

    /**
     * 使用真实HTTP登录得到的管理员主体编排状态用例。
     * @param revoke 可插入同步点的撤销端口
     * @return 状态应用用例
     */
    private AccountStatusApplicationService status(AccountRevocations revoke) {
        var verified = authentication.authenticateAccess(accessToken());
        var identity = new CurrentIdentity.Snapshot(UUID.fromString(verified.subject()), verified.sessionId(), verified.version());
        return new AccountStatusApplicationService(() -> identity, administration, accounts, revoke, work);
    }

    /**
     * 调用认证HTTP入口，仅返回状态码，避免响应或凭据进入断言日志。
     * @param path 认证路径
     * @param body 敏感请求负载
     * @return HTTP状态
     * @throws Exception 网络请求失败
     */
    private int postStatus(String path, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder().build().writeValueAsString(body))).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /**
     * 等待确定的竞争窗口，超时或中断立即失败并保留中断信号。
     * @param signal 竞争信号
     */
    private static void await(CountDownLatch signal) {
        try { if (!signal.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("竞争窗口等待超时"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("竞争等待被中断"); }
    }

    /**
     * 断言统一未认证错误，不输出敏感令牌。
     * @param action 被拒绝的认证操作
     */
    private static void unauthorized(Runnable action) {
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, action::run).getErrorCode());
    }
}
