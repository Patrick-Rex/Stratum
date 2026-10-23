package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.TokenCodec.Claims;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import com.patrick.stratum.infrastructure.auth.RedisAuthSessionsAdapterImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;

/** 真实Redis会话、版本和TTL集成验证；每个测试仅清理其随机键。 */
class AuthSessionsIntegrationTest extends MariaDbIntegrationTest {
    private final AuthSessions sessions;
    private final StringRedisTemplate redis;
    private final Set<String> keys = new HashSet<>();

    /**
     * 装配真实Redis与会话端口。
     * @param sessions 会话实现
     * @param redis 测试Redis客户端
     */
    @Autowired
    AuthSessionsIntegrationTest(AuthSessions sessions, StringRedisTemplate redis) {
        this.sessions = sessions;
        this.redis = redis;
    }

    /** 删除本测试随机键，不扫描或清空共享Redis。 */
    @AfterEach
    void cleanUp() { if (!keys.isEmpty()) { redis.delete(keys); } }

    /** 创建会话与白名单同寿命，用户版本永久保留且精确到Long最大值。 */
    @Test
    void createsBoundedSessionAndChecksExactVersion() {
        var refresh = refresh();
        assertEquals(0, sessions.userVersion(refresh.subject()));
        assertEquals(-1, redis.getExpire(userKey(refresh)));
        redis.opsForValue().set(userKey(refresh), Long.toString(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, sessions.userVersion(refresh.subject()));
        refresh = new Claims(refresh.subject(), refresh.tokenId(), refresh.sessionId(), Long.MAX_VALUE,
                refresh.issuedAt(), refresh.expiresAt(), refresh.purpose());
        sessions.create(refresh);
        sessions.requireActive(access(refresh));
        assertEquals(refresh.subject() + "|" + Long.MAX_VALUE, redis.opsForValue().get(sessionKey(refresh)));
        assertEquals(refresh.subject() + "|" + Long.MAX_VALUE + "|" + refresh.sessionId(),
                redis.opsForValue().get(refreshKey(refresh)));
        for (String key : List.of(sessionKey(refresh), refreshKey(refresh))) {
            Long ttl = redis.getExpire(key);
            assertNotNull(ttl);
            assertTrue(ttl > 1_209_590 && ttl <= 1_209_600);
        }
    }

    /** 版本未初始化或已变更时不创建任何会话或白名单。 */
    @Test
    void rejectsMissingOrChangedVersionWithoutWriting() {
        var refresh = refresh();
        unauthorized(() -> sessions.create(refresh));
        sessions.userVersion(refresh.subject());
        redis.opsForValue().set(userKey(refresh), "1");
        unauthorized(() -> sessions.create(refresh));
        assertFalse(redis.hasKey(sessionKey(refresh)));
        assertFalse(redis.hasKey(refreshKey(refresh)));
    }

    /**
     * 同一会话并发创建仅一个成功，其余返回冲突且不覆盖权威状态。
     * @throws Exception 并发测试执行失败
     */
    @Test
    void concurrentCreationSucceedsOnlyOnce() throws Exception {
        var refresh = refresh();
        sessions.userVersion(refresh.subject());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { sessions.create(refresh); return true; }
                    catch (StratumException failure) {
                        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var result : results) { if (result.get(5, TimeUnit.SECONDS)) { successes++; } }
            assertEquals(1, successes);
            sessions.requireActive(access(refresh));
        } finally { start.countDown(); }
    }

    /** Access每次检查Redis，跨用户、会话到期、版本缺失或撤销均立即拒绝。 */
    @Test
    void rejectsForeignExpiredMissingAndRevokedSessions() {
        var refresh = refresh();
        sessions.userVersion(refresh.subject());
        sessions.create(refresh);
        var foreign = refresh();
        sessions.userVersion(foreign.subject());
        var access = access(refresh);
        unauthorized(() -> sessions.requireActive(new Claims(foreign.subject(), access.tokenId(), access.sessionId(),
                access.version(), access.issuedAt(), access.expiresAt(), Purpose.ACCESS)));
        redis.opsForValue().set(userKey(refresh), "1");
        unauthorized(() -> sessions.requireActive(access));
        redis.delete(userKey(refresh));
        unauthorized(() -> sessions.requireActive(access));
        redis.opsForValue().set(userKey(refresh), "0");
        sessions.requireActive(access);
        redis.expire(sessionKey(refresh), Duration.ZERO);
        unauthorized(() -> sessions.requireActive(access));
    }

    /** 白名单标识碰撞不会写入新会话，已有白名单内容不变。 */
    @Test
    void duplicateRefreshIdentifierDoesNotCreatePartialState() {
        var refresh = refresh();
        sessions.userVersion(refresh.subject());
        redis.opsForValue().set(refreshKey(refresh), "existing");
        var failure = assertThrows(StratumException.class, () -> sessions.create(refresh));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertFalse(redis.hasKey(sessionKey(refresh)));
        assertEquals("existing", redis.opsForValue().get(refreshKey(refresh)));
    }

    /** 验证端口也拒绝用途错误、非法标识、负版本和过期声明。 */
    @Test
    void rejectsInvalidClaimsBeforeRedisWrite() {
        var refresh = refresh();
        unauthorized(() -> sessions.create(access(refresh)));
        unauthorized(() -> sessions.requireActive(refresh));
        unauthorized(() -> sessions.create(null));
        unauthorized(() -> sessions.requireActive(null));
        Instant old = Instant.now().minus(Duration.ofDays(15)).truncatedTo(ChronoUnit.SECONDS);
        for (Claims invalid : List.of(
                new Claims("unsafe|user", refresh.tokenId(), refresh.sessionId(), 0,
                        refresh.issuedAt(), refresh.expiresAt(), Purpose.REFRESH),
                new Claims(refresh.subject(), refresh.tokenId(), refresh.sessionId(), -1,
                        refresh.issuedAt(), refresh.expiresAt(), Purpose.REFRESH),
                new Claims(refresh.subject(), refresh.tokenId(), refresh.sessionId(), 0,
                        old, old.plus(Purpose.REFRESH.lifetime()), Purpose.REFRESH))) {
            unauthorized(() -> sessions.create(invalid));
        }
        assertFalse(redis.hasKey(sessionKey(refresh)));
    }

    /** 非法权威版本或连接故障拒绝请求且不泄露底层异常。 */
    @Test
    void failsClosedOnCorruptionOrRedisFailure() {
        var refresh = refresh();
        for (String bad : List.of("-1", "01", "9223372036854775808", "1e9", "value")) {
            redis.opsForValue().set(userKey(refresh), bad);
            var failure = assertThrows(StratumException.class, () -> sessions.userVersion(refresh.subject()));
            assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        }
        var unavailable = new RedisAuthSessionsAdapterImpl(new FailingRedisTemplate());
        for (Runnable action : List.<Runnable>of(() -> unavailable.userVersion(refresh.subject()),
                () -> unavailable.create(refresh), () -> unavailable.requireActive(access(refresh)))) {
            var failure = assertThrows(StratumException.class, action::run);
            assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode());
            assertNull(failure.getCause());
            assertTrue(failure.getDetails().isEmpty());
        }
    }

    /**
     * 生成当前有效的Refresh声明并登记测试键。
     * @return 独立会话声明
     */
    private Claims refresh() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = new Claims("auth-test-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                now, now.plus(Purpose.REFRESH.lifetime()), Purpose.REFRESH);
        keys.addAll(List.of(userKey(claims), sessionKey(claims), refreshKey(claims)));
        return claims;
    }

    /**
     * 从会话创建当前有效的Access声明。
     * @param refresh 所属Refresh声明
     * @return Access声明
     */
    private static Claims access(Claims refresh) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new Claims(refresh.subject(), UUID.randomUUID(), refresh.sessionId(), refresh.version(),
                now, now.plus(Purpose.ACCESS.lifetime()), Purpose.ACCESS);
    }

    /**
     * 取得测试用户版本键。
     * @param claims 测试声明
     * @return 权威键
     */
    private static String userKey(Claims claims) { return "auth:user:" + claims.subject() + ":ver"; }

    /**
     * 取得测试会话键。
     * @param claims 测试声明
     * @return 会话键
     */
    private static String sessionKey(Claims claims) { return "auth:sess:" + claims.sessionId(); }

    /**
     * 取得测试白名单键。
     * @param claims 测试声明
     * @return 白名单键
     */
    private static String refreshKey(Claims claims) { return "auth:rt:" + claims.tokenId(); }

    /**
     * 断言无效身份被拒绝。
     * @param action 待执行操作
     */
    private static void unauthorized(Runnable action) {
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, action::run).getErrorCode());
    }

    /** 模拟Redis不可用，验证认证路径不会退回本地缓存或吞掉故障。 */
    private static class FailingRedisTemplate extends StringRedisTemplate {
        /**
         * 注入连接故障，不实际建立额外网络连接。
         * @param script 待执行脚本
         * @param keys 脚本键
         * @param args 脚本参数
         * @param <T> 脚本返回类型
         * @return 始终抛出连接异常
         */
        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            throw new RedisConnectionFailureException("故障注入：底层敏感连接信息");
        }
    }
}
