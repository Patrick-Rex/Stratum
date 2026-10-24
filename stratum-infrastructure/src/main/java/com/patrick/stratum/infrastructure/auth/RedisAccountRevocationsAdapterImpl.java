package com.patrick.stratum.infrastructure.auth;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountRevocations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 目标账号原子撤销适配器；单键整数递增，不扫描会话、不经Lua浮点数转换版本。 */
@Component
@RequiredArgsConstructor
public class RedisAccountRevocationsAdapterImpl implements AccountRevocations {
    private static final DefaultRedisScript<String> REVOKE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and (string.len(current) > 19 or
                (current ~= '0' and not string.match(current, '^[1-9]%d*$'))) then return nil end
            redis.call('INCR', KEYS[1])
            redis.call('PERSIST', KEYS[1])
            return redis.call('GET', KEYS[1])
            """, String.class);
    private final StringRedisTemplate redis;

    /** {@inheritDoc} */
    @Override
    public long revoke(UUID userId) {
        Objects.requireNonNull(userId, "账号标识不能为空");
        try {
            String result = redis.execute(REVOKE, List.of("auth:user:" + userId + ":ver"));
            if (result == null) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            long next = Long.parseLong(result);
            if (next <= 0) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            return next;
        } catch (DataAccessException | NumberFormatException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }
}
