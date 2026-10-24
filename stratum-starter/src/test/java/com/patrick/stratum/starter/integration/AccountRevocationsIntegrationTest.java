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
import com.patrick.stratum.domain.auth.AccountRevocations;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.TokenCodec.Claims;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import com.patrick.stratum.infrastructure.auth.RedisAccountRevocationsAdapterImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;

/** 真实Redis验证目标账号撤销、并发递增及故障不返回成功。 */
class AccountRevocationsIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private AccountRevocations revocations;
    @Autowired private AuthSessions sessions;
    @Autowired private StringRedisTemplate redis;
    private final UUID userId = UUID.randomUUID();
    private final Set<String> keys = new HashSet<>();

    /** 只清理当前测试记录的随机键。 */
    @AfterEach
    void clean() { if (!keys.isEmpty()) redis.delete(keys); }

    /** 无会话时仍创建永久撤销版本，其他账号保持原有版本。 */
    @Test
    void revokesWithoutSessionAndKeepsOtherUsers() {
        keys.add(key(userId));
        UUID other = UUID.randomUUID(); keys.add(key(other));
        assertEquals(0, sessions.userVersion(other.toString()));
        assertEquals(1, revocations.revoke(userId));
        assertEquals(2, revocations.revoke(userId));
        assertEquals(2, sessions.userVersion(userId.toString()));
        assertEquals(0, sessions.userVersion(other.toString()));
        assertEquals(-1, redis.getExpire(key(userId)));
    }

    /** 撤销后旧Access及Refresh立即无效，使用新版本可重新建立会话。 */
    @Test
    void rejectsOldAccessAndRefresh() {
        var previous = create(0);
        var access = access(previous);
        sessions.requireActive(access);
        assertEquals(1, revocations.revoke(userId));
        assertEquals(CommonErrorCode.UNAUTHORIZED,
                assertThrows(StratumException.class, () -> sessions.requireActive(access)).getErrorCode());
        var next = claims(previous.sessionId(), 0, Purpose.REFRESH);
        assertEquals(CommonErrorCode.UNAUTHORIZED,
                assertThrows(StratumException.class, () -> sessions.rotate(previous, next)).getErrorCode());
        sessions.requireActive(access(create(1)));
    }

    /** 精确递增长整数，溢出和畸形版本保持原值并返回无敏感信息的错误。 */
    @Test
    void preservesExactLongAndRejectsCorruptOrOverflowingValues() {
        keys.add(key(userId));
        redis.opsForValue().set(key(userId), Long.toString(Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, revocations.revoke(userId));
        for (String invalid : List.of(Long.toString(Long.MAX_VALUE), "-1", "01", "1.0", "", "x", "9".repeat(20))) {
            redis.opsForValue().set(key(userId), invalid);
            failure(revocations);
            assertEquals(invalid, redis.opsForValue().get(key(userId)));
        }
    }

    /**
     * 多个撤销请求各自得到唯一连续版本，不丢失任何一次递增。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentRevocationsAdvanceEveryVersion() throws Exception {
        keys.add(key(userId));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 12).mapToObj(index -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS)); return revocations.revoke(userId);
            })).toList();
            start.countDown();
            var versions = new HashSet<Long>();
            for (var task : tasks) versions.add(task.get(10, TimeUnit.SECONDS));
            assertEquals(12, versions.size());
            assertEquals(1L, versions.stream().mapToLong(Long::longValue).min().orElseThrow());
            assertEquals(12L, sessions.userVersion(userId.toString()));
        } finally { start.countDown(); }
    }

    /** 错误类型与连接故障脱敏，不能伪装成撤销成功。 */
    @Test
    void failsClosedOnStorageFailure() {
        keys.add(key(userId));
        redis.opsForList().leftPush(key(userId), "invalid-type");
        failure(revocations);
        assertEquals(List.of("invalid-type"), redis.opsForList().range(key(userId), 0, -1));
        failure(new RedisAccountRevocationsAdapterImpl(new FailingRedisTemplate()));
    }

    /**
     * 建立真实Redis会话。
     * @param version 账号版本
     * @return Refresh声明
     */
    private Claims create(long version) {
        keys.add(key(userId));
        assertEquals(version, sessions.userVersion(userId.toString()));
        var token = claims(UUID.randomUUID(), version, Purpose.REFRESH);
        keys.add("auth:sess:" + token.sessionId()); keys.add("auth:rt:" + token.tokenId());
        sessions.create(token); return token;
    }

    /**
     * 生成服务器端验证后的测试声明。
     * @param sessionId 会话UUID
     * @param version 撤销版本
     * @param purpose 令牌用途
     * @return 有效时段内的声明
     */
    private Claims claims(UUID sessionId, long version, Purpose purpose) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new Claims(userId.toString(), UUID.randomUUID(), sessionId, version, now,
                now.plus(purpose.lifetime()), purpose);
    }

    /**
     * 创建同会话Access声明。
     * @param refresh Refresh声明
     * @return Access声明
     */
    private Claims access(Claims refresh) { return claims(refresh.sessionId(), refresh.version(), Purpose.ACCESS); }

    /**
     * 生成版本键。
     * @param id 账号标识
     * @return Redis键
     */
    private static String key(UUID id) { return "auth:user:" + id + ":ver"; }

    /**
     * 断言存储失败且不携带原因或详情。
     * @param adapter 撤销端口
     */
    private void failure(AccountRevocations adapter) {
        var error = assertThrows(StratumException.class, () -> adapter.revoke(userId));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, error.getErrorCode());
        assertNull(error.getCause()); assertTrue(error.getDetails().isEmpty());
    }

    /** 无额外连接的故障客户端。 */
    private static class FailingRedisTemplate extends StringRedisTemplate {
        /** {@inheritDoc} */
        @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            throw new RedisConnectionFailureException("不可暴露的内部地址");
        }
    }
}
