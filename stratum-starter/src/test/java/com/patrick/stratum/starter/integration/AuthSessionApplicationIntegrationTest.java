package com.patrick.stratum.starter.integration;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.AuthSessionApplicationService.Tokens;
import com.patrick.stratum.application.auth.LocalIdentityApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.TokenCodec;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import com.patrick.stratum.infrastructure.auth.RsaTokenAdapterImpl;
import com.patrick.stratum.starter.auth.AuthSessionConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库身份、RSA与Redis会话组合验证，所有随机凭据和键均按用例清理。 */
class AuthSessionApplicationIntegrationTest extends MariaDbIntegrationTest {
    private static TokenCodec codec;
    private final LocalIdentityApplicationService identities;
    private final AuthSessions sessions;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Set<String> keys = new HashSet<>();
    private final String username = "session" + UUID.randomUUID();
    private final char[] password = UUID.randomUUID().toString().toCharArray();
    private LocalAccount account;
    private AuthSessionApplicationService service;

    /**
     * 注入真实端口与夹具工具。
     * @param identities 身份入口
     * @param sessions Redis会话
     * @param accounts 账号仓储
     * @param passwords BCrypt散列
     * @param unitOfWork 事务门面
     * @param jdbc 数据库清理
     * @param redis Redis清理
     */
    @Autowired
    AuthSessionApplicationIntegrationTest(LocalIdentityApplicationService identities, AuthSessions sessions,
            LocalAccountRepository accounts, PasswordHasher passwords, UnitOfWork unitOfWork, JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.identities = identities; this.sessions = sessions; this.accounts = accounts; this.passwords = passwords;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc; this.redis = redis;
    }

    /**
     * 在进程内生成真实RSA密钥，不落盘。
     * @throws Exception JCA不可用
     */
    @BeforeAll
    static void createKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        codec = new RsaTokenAdapterImpl((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(),
                "stratum-test", "stratum-api-test", Clock.systemUTC());
    }

    /** 为每个用例建立独立账号，不创建任何会话。 */
    @BeforeEach
    void setUp() {
        account = LocalAccount.create(username, passwords.hash(password), false);
        unitOfWork.execute(() -> { accounts.insert(account); return null; });
        keys.add("auth:user:" + account.getId() + ":ver");
        service = new AuthSessionApplicationService(identities, codec, sessions);
    }

    /** 清理已知测试资源，不扫描其他会话。 */
    @AfterEach
    void cleanUp() {
        Arrays.fill(password, '\0');
        redis.delete(keys);
        jdbc.update("DELETE FROM auth_user WHERE username = ?", username);
    }

    /** 登录/刷新保留同一会话与版本，旧Refresh重放失败，Access独立验证用途。 */
    @Test
    void logsInRotatesAndChecksAccess() {
        var first = track(service.login(username, password.clone(), "session-app-test"));
        assertEquals(900, first.expiresIn());
        assertEquals("AuthTokens[内容已隐藏]", first.toString());
        var original = service.authenticateAccess(first.accessToken());
        assertEquals(account.getId(), original.subject());
        var next = track(service.refresh(first.refreshToken()));
        var renewed = service.authenticateAccess(next.accessToken());
        assertEquals(original.sessionId(), renewed.sessionId());
        assertEquals(original.version(), renewed.version());
        unauthorized(() -> service.refresh(first.refreshToken()));
        unauthorized(() -> service.refresh(next.accessToken()));
        unauthorized(() -> service.authenticateAccess(next.refreshToken()));
        assertEquals(original, service.authenticateAccess(first.accessToken()));
    }

    /** 错误/禁用凭据不能初始化用户版本或创建会话。 */
    @Test
    void invalidIdentityDoesNotTouchSessions() {
        unauthorized(() -> service.login(username, UUID.randomUUID().toString().toCharArray(), "session-app-test"));
        assertFalse(redis.hasKey("auth:user:" + account.getId() + ":ver"));
        jdbc.update("UPDATE auth_user SET enabled = 0 WHERE id = ?", account.getId());
        unauthorized(() -> service.login(username, password.clone(), "session-app-test"));
        assertFalse(redis.hasKey("auth:user:" + account.getId() + ":ver"));
    }

    /** 权威版本改变或会话删除后，即使签名仍正确也不能继续访问或刷新。 */
    @Test
    void rejectsRevokedAuthorityState() {
        var first = track(service.login(username, password.clone(), "session-app-test"));
        redis.opsForValue().set("auth:user:" + account.getId() + ":ver", "1");
        unauthorized(() -> service.authenticateAccess(first.accessToken()));
        unauthorized(() -> service.refresh(first.refreshToken()));
        var next = track(service.login(username, password.clone(), "session-app-test"));
        assertEquals(1, service.authenticateAccess(next.accessToken()).version());
        redis.delete("auth:sess:" + codec.verify(next.refreshToken(), Purpose.REFRESH).sessionId());
        unauthorized(() -> service.authenticateAccess(next.accessToken()));
    }

    /**
     * 同一Refresh并发使用只能返回一份成功后继。
     * @throws Exception 并发任务失败
     */
    @Test
    void concurrentRefreshReturnsOnlyOneSuccess() throws Exception {
        var first = track(service.login(username, password.clone(), "session-app-test"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 6).<Callable<Tokens>>mapToObj(index -> () -> {
                try { return service.refresh(first.refreshToken()); }
                catch (StratumException failure) { assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode()); return null; }
            }).toList();
            int successes = 0;
            for (var future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS)) {
                var result = future.get();
                if (result != null) { track(result); service.authenticateAccess(result.accessToken()); successes++; }
            }
            assertEquals(1, successes);
        }
    }

    /** 创建/旋转的存储故障向上传播，不能返回未确认的令牌；旋转失败未消费原令牌。 */
    @Test
    void failedSessionWritesDoNotReturnTokens() {
        AuthSessions failing = new AuthSessions() {
            /** {@inheritDoc} */
            @Override public long userVersion(String subject) { return sessions.userVersion(subject); }
            /** {@inheritDoc} */
            @Override public void create(TokenCodec.Claims refresh) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
            /** {@inheritDoc} */
            @Override public void requireActive(TokenCodec.Claims access) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
            /** {@inheritDoc} */
            @Override public void rotate(TokenCodec.Claims previous, TokenCodec.Claims replacement) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
        };
        var unavailable = new AuthSessionApplicationService(identities, codec, failing);
        assertEquals(CommonErrorCode.INTERNAL_ERROR, assertThrows(StratumException.class,
                () -> unavailable.login(username, password.clone(), "session-app-test")).getErrorCode());
        var first = track(service.login(username, password.clone(), "session-app-test"));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, assertThrows(StratumException.class,
                () -> unavailable.refresh(first.refreshToken())).getErrorCode());
        assertEquals(CommonErrorCode.INTERNAL_ERROR, assertThrows(StratumException.class,
                () -> unavailable.authenticateAccess(first.accessToken())).getErrorCode());
        track(service.refresh(first.refreshToken()));
    }

    /** 显式开关装配应用编排，未启用时不生成认证入口。 */
    @Test
    void configuresServiceOnlyWhenEnabled() {
        for (boolean enabled : new boolean[]{false, true}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-auth", Map.of("stratum.auth.jwt.enabled", enabled)));
                context.registerBean(LocalIdentityApplicationService.class, () -> identities);
                context.registerBean(TokenCodec.class, () -> codec);
                context.registerBean(AuthSessions.class, () -> sessions);
                context.register(AuthSessionConfiguration.class); context.refresh();
                assertEquals(enabled ? 1 : 0, context.getBeansOfType(AuthSessionApplicationService.class).size());
            }
        }
    }

    /**
     * 记录令牌对应的有限资源集合以供清理。
     * @param result 已成功返回的令牌对
     * @return 原结果
     */
    private Tokens track(Tokens result) {
        var refresh = codec.verify(result.refreshToken(), Purpose.REFRESH);
        keys.add("auth:sess:" + refresh.sessionId()); keys.add("auth:rt:" + refresh.tokenId());
        return result;
    }

    /**
     * 断言统一认证错误且不含敏感原因或明细。
     * @param action 预期拒绝的操作
     */
    private static void unauthorized(Runnable action) {
        var failure = assertThrows(StratumException.class, action::run);
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertTrue(failure.getDetails().isEmpty()); assertNull(failure.getCause());
    }
}
