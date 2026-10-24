package com.patrick.stratum.starter.integration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthRevocations;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.TokenCodec.Claims;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import com.patrick.stratum.infrastructure.auth.RedisAuthRevocationsAdapterImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;

/** 真实Redis验证撤销绑定、其他会话隔离、刷新竞争以及精确版本上界。 */
class AuthRevocationsIntegrationTest extends MariaDbIntegrationTest {
    private final AuthSessions sessions;
    private final AuthRevocations revocations;
    private final StringRedisTemplate redis;
    private final UUID userId = UUID.randomUUID();
    private final Set<String> keys = new HashSet<>();

    /**
     * 装配真实会话与撤销端口。
     * @param sessions 既有会话操作
     * @param revocations 原子撤销端口
     * @param redis 测试键断言客户端
     */
    @Autowired
    AuthRevocationsIntegrationTest(AuthSessions sessions, AuthRevocations revocations, StringRedisTemplate redis) {
        this.sessions = sessions; this.revocations = revocations; this.redis = redis;
    }

    /** 仅清理本用例登记的随机键。 */
    @AfterEach
    void clean() { if (!keys.isEmpty()) redis.delete(keys); }

    /** 注销当前会话后Access和Refresh不可再用，同账号另一会话仍有效。 */
    @Test
    void revokesOnlyTheOwnedSession() {
        var first = create(0); var second = create(0);
        revocations.revokeSession(userId, first.sessionId(), 0);
        unauthorized(() -> sessions.requireActive(access(first)));
        unauthorized(() -> sessions.rotate(first, replacement(first)));
        sessions.requireActive(access(second));
        sessions.rotate(second, replacement(second));
        assertEquals(0, sessions.userVersion(userId.toString()));
        assertTrue(redis.getExpire("auth:rt:" + first.tokenId()) > 0);
        assertFalse(redis.hasKey("auth:sess:" + first.sessionId()));
    }

    /** 用户、会话或版本不匹配不得影响正确会话，也不能递增用户版本。 */
    @Test
    void rejectsMismatchedIdentityWithoutMutation() {
        var first = create(0);
        unauthorized(() -> revocations.revokeSession(UUID.randomUUID(), first.sessionId(), 0));
        unauthorized(() -> revocations.revokeSession(userId, first.sessionId(), 1));
        unauthorized(() -> revocations.revokeAll(userId, UUID.randomUUID(), 0));
        unauthorized(() -> revocations.revokeAll(userId, first.sessionId(), 1));
        sessions.requireActive(access(first));
        assertEquals(0, sessions.userVersion(userId.toString()));
        assertThrows(IllegalArgumentException.class, () -> revocations.revokeSession(userId, first.sessionId(), -1));
    }

    /** 全部注销立即拒绝所有旧会话，正确凭据重新登录可使用新版本。 */
    @Test
    void revokesEveryOldSessionAndAllowsNewVersion() {
        var first = create(0); var second = create(0);
        assertEquals(1, revocations.revokeAll(userId, first.sessionId(), 0));
        for (var old : List.of(first, second)) {
            unauthorized(() -> sessions.requireActive(access(old)));
            unauthorized(() -> sessions.rotate(old, replacement(old)));
        }
        unauthorized(() -> revocations.revokeAll(userId, first.sessionId(), 0));
        var next = create(1);
        sessions.requireActive(access(next));
        assertEquals(-1, redis.getExpire(userKey()));
    }

    /** 大于双精度安全整数的版本精确递增，溢出不修改既有状态。 */
    @Test
    void incrementsExactLongAndFailsClosedOnOverflow() {
        keys.add(userKey()); redis.opsForValue().set(userKey(), Long.toString(Long.MAX_VALUE - 1));
        var previous = create(Long.MAX_VALUE - 1);
        assertEquals(Long.MAX_VALUE, revocations.revokeAll(userId, previous.sessionId(), Long.MAX_VALUE - 1));
        assertEquals(Long.toString(Long.MAX_VALUE), redis.opsForValue().get(userKey()));
        var maximum = create(Long.MAX_VALUE);
        var error = assertThrows(StratumException.class, () -> revocations.revokeAll(userId, maximum.sessionId(), Long.MAX_VALUE));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, error.getErrorCode()); assertNull(error.getCause());
        assertEquals(Long.toString(Long.MAX_VALUE), redis.opsForValue().get(userKey()));
        sessions.requireActive(access(maximum));
    }

    /**
     * 刷新与两种注销竞争后，任何先前或后继Refresh均不能恢复已撤销身份。
     * @throws Exception 并发任务失败
     */
    @Test
    void refreshRaceCannotRestoreRevokedIdentity() throws Exception {
        for (boolean all : List.of(false, true)) {
            var previous = create(sessions.userVersion(userId.toString()));
            var next = replacement(previous); var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var refresh = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { sessions.rotate(previous, next); }
                    catch (StratumException error) { assertEquals(CommonErrorCode.UNAUTHORIZED, error.getErrorCode()); }
                    return true;
                });
                var revoke = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    if (all) revocations.revokeAll(userId, previous.sessionId(), previous.version());
                    else revocations.revokeSession(userId, previous.sessionId(), previous.version());
                    return true;
                });
                start.countDown(); assertTrue(refresh.get(5, TimeUnit.SECONDS)); assertTrue(revoke.get(5, TimeUnit.SECONDS));
            }
            unauthorized(() -> sessions.requireActive(access(next)));
            unauthorized(() -> sessions.rotate(next, replacement(next)));
        }
    }

    /** 错误Redis类型和连接故障均返回脱敏内部错误，不伪装为注销成功。 */
    @Test
    void storageFailuresNeverReturnSuccess() {
        var first = create(0);
        redis.delete("auth:sess:" + first.sessionId()); redis.opsForList().leftPush("auth:sess:" + first.sessionId(), "wrong-type");
        for (boolean all : List.of(false, true)) {
            var failure = assertThrows(StratumException.class, () -> {
                if (all) revocations.revokeAll(userId, first.sessionId(), 0);
                else revocations.revokeSession(userId, first.sessionId(), 0);
            });
            assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
        }
        var unavailable = new RedisAuthRevocationsAdapterImpl(new FailingRedisTemplate());
        var failure = assertThrows(StratumException.class, () -> unavailable.revokeAll(userId, first.sessionId(), 0));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
        assertEquals(0, sessions.userVersion(userId.toString()));
    }

    /**
     * 创建独立真实会话，沿用已有权威版本而不覆盖。
     * @param version 预期版本
     * @return 已登记Refresh声明
     */
    private Claims create(long version) {
        keys.add(userKey()); assertEquals(version, sessions.userVersion(userId.toString()));
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = new Claims(userId.toString(), UUID.randomUUID(), UUID.randomUUID(), version,
                now, now.plus(Purpose.REFRESH.lifetime()), Purpose.REFRESH);
        keys.add("auth:sess:" + claims.sessionId()); keys.add("auth:rt:" + claims.tokenId());
        sessions.create(claims); return claims;
    }

    /**
     * 创建同一会话后继并登记测试键。
     * @param previous 原始Refresh
     * @return 后继声明
     */
    private Claims replacement(Claims previous) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = new Claims(previous.subject(), UUID.randomUUID(), previous.sessionId(), previous.version(),
                now, now.plus(Purpose.REFRESH.lifetime()), Purpose.REFRESH);
        keys.add("auth:rt:" + claims.tokenId()); return claims;
    }

    /**
     * 构造用于会话校验的Access声明。
     * @param refresh 所属会话
     * @return Access声明
     */
    private static Claims access(Claims refresh) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new Claims(refresh.subject(), UUID.randomUUID(), refresh.sessionId(), refresh.version(), now,
                now.plus(Purpose.ACCESS.lifetime()), Purpose.ACCESS);
    }

    /** @return 本测试账号的权威版本键 */
    private String userKey() { return "auth:user:" + userId + ":ver"; }

    /**
     * 断言身份无效。
     * @param action 预期拒绝的操作
     */
    private static void unauthorized(Runnable action) {
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, action::run).getErrorCode());
    }

    /** 模拟存储不可用，不建立额外连接。 */
    private static class FailingRedisTemplate extends StringRedisTemplate {
        /**
         * 注入敏感底层故障以核对原因链脱敏。
         * @param script 原子脚本
         * @param keys 键列表
         * @param args 参数
         * @param <T> 返回类型
         * @return 始终抛出异常
         */
        @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            throw new RedisConnectionFailureException("不可暴露的连接信息");
        }
    }
}
