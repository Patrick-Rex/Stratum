package com.patrick.stratum.infrastructure.auth;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthRevocations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis原子本人注销；不扫描键，版本通过Redis整数递增后读取字符串，避免Lua浮点截断。 */
@Component
@RequiredArgsConstructor
public class RedisAuthRevocationsAdapterImpl implements AuthRevocations {
    private static final DefaultRedisScript<Long> REVOKE_SESSION = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then return 0 end
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> REVOKE_ALL = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return nil end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then return nil end
            redis.call('INCR', KEYS[1])
            return redis.call('GET', KEYS[1])
            """, String.class);
    private final StringRedisTemplate redis;

    /** {@inheritDoc} */
    @Override
    public void revokeSession(UUID userId, UUID sessionId, long version) {
        var keys = keys(userId, sessionId, version);
        try {
            Long result = redis.execute(REVOKE_SESSION, keys, Long.toString(version), userId + "|" + version);
            if (!Long.valueOf(1).equals(result)) throw new StratumException(CommonErrorCode.UNAUTHORIZED);
        } catch (DataAccessException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    /** {@inheritDoc} */
    @Override
    public long revokeAll(UUID userId, UUID sessionId, long version) {
        var keys = keys(userId, sessionId, version);
        try {
            String result = redis.execute(REVOKE_ALL, keys, Long.toString(version), userId + "|" + version);
            if (result == null) throw new StratumException(CommonErrorCode.UNAUTHORIZED);
            long next = Long.parseLong(result);
            if (next <= version) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            return next;
        } catch (DataAccessException | NumberFormatException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 校验内部调用边界并生成固定两个键，不接受外部键名。
     * @param userId 账号标识
     * @param sessionId 会话标识
     * @param version 非负版本
     * @return 权威用户版本与会话键
     */
    private static List<String> keys(UUID userId, UUID sessionId, long version) {
        Objects.requireNonNull(userId, "账号标识不能为空");
        Objects.requireNonNull(sessionId, "会话标识不能为空");
        if (version < 0) throw new IllegalArgumentException("版本不能为负数");
        return List.of("auth:user:" + userId + ":ver", "auth:sess:" + sessionId);
    }
}
