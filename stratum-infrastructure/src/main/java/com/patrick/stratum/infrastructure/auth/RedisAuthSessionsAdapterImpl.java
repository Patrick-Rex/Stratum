package com.patrick.stratum.infrastructure.auth;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.TokenCodec.Claims;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis单节点/Sentinel会话适配器；原子脚本只比较版本字符串，避免Lua双精度截断。 */
@Component
@RequiredArgsConstructor
public class RedisAuthSessionsAdapterImpl implements AuthSessions {
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final DefaultRedisScript<String> VERSION_SCRIPT = new DefaultRedisScript<>("""
            local version = redis.call('GET', KEYS[1])
            if not version then
                redis.call('SET', KEYS[1], '0')
                return '0'
            end
            return version
            """, String.class);
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            local now = tonumber(redis.call('TIME')[1])
            local expires = tonumber(ARGV[4])
            if expires <= now or expires > now + 1209600 then return 0 end
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('EXISTS', KEYS[2], KEYS[3]) ~= 0 then return -1 end
            redis.call('SET', KEYS[2], ARGV[2], 'EXAT', ARGV[4])
            redis.call('SET', KEYS[3], ARGV[3], 'EXAT', ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVE = new DefaultRedisScript<>("""
            if tonumber(ARGV[3]) <= tonumber(redis.call('TIME')[1]) then return 0 end
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then return 0 end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;

    /** {@inheritDoc} */
    @Override
    public long userVersion(String subject) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new IllegalArgumentException("用户标识不合法");
        }
        try {
            String value = redis.execute(VERSION_SCRIPT, List.of(userKey(subject)));
            if (value == null || !VERSION.matcher(value).matches()) {
                throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            }
            return Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void create(Claims refresh) {
        validate(refresh, Purpose.REFRESH);
        try {
            Long result = redis.execute(CREATE,
                    List.of(userKey(refresh.subject()), sessionKey(refresh), refreshKey(refresh)),
                    Long.toString(refresh.version()), state(refresh), refreshState(refresh),
                    Long.toString(refresh.expiresAt().getEpochSecond()));
            if (Long.valueOf(-1).equals(result)) { throw new StratumException(CommonErrorCode.CONFLICT); }
            if (!Long.valueOf(1).equals(result)) { throw new StratumException(CommonErrorCode.UNAUTHORIZED); }
        } catch (DataAccessException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void requireActive(Claims access) {
        validate(access, Purpose.ACCESS);
        try {
            Long result = redis.execute(ACTIVE, List.of(userKey(access.subject()), sessionKey(access)),
                    Long.toString(access.version()), state(access), Long.toString(access.expiresAt().getEpochSecond()));
            if (!Long.valueOf(1).equals(result)) { throw new StratumException(CommonErrorCode.UNAUTHORIZED); }
        } catch (DataAccessException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 防止无效内部参数绕开用途、时间或键空间边界；不能代替签名校验。
     * @param claims 已验证声明
     * @param purpose 方法要求用途
     */
    private static void validate(Claims claims, Purpose purpose) {
        try {
            Instant now = Instant.now();
            if (claims == null || claims.purpose() != purpose || claims.subject() == null
                    || !SUBJECT.matcher(claims.subject()).matches() || claims.tokenId() == null
                    || claims.sessionId() == null || claims.version() < 0 || claims.issuedAt() == null
                    || claims.expiresAt() == null || claims.issuedAt().getNano() != 0
                    || claims.issuedAt().isAfter(now) || !claims.expiresAt().isAfter(now)
                    || !claims.issuedAt().plus(purpose.lifetime()).equals(claims.expiresAt())) {
                throw new StratumException(CommonErrorCode.UNAUTHORIZED);
            }
        } catch (DateTimeException failure) {
            throw new StratumException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 构造用户版本键。
     * @param subject 已校验用户标识
     * @return 无过期时间的权威版本键
     */
    private static String userKey(String subject) { return "auth:user:" + subject + ":ver"; }

    /**
     * 构造会话键。
     * @param claims 已校验声明
     * @return 会话键
     */
    private static String sessionKey(Claims claims) { return "auth:sess:" + claims.sessionId(); }

    /**
     * 构造Refresh白名单键。
     * @param claims 已校验声明
     * @return 白名单键
     */
    private static String refreshKey(Claims claims) { return "auth:rt:" + claims.tokenId(); }

    /**
     * 编码会话所有者与版本，标识不允许包含分隔符。
     * @param claims 已校验声明
     * @return 有界状态字符串
     */
    private static String state(Claims claims) { return claims.subject() + "|" + claims.version(); }

    /**
     * 绑定白名单到所属会话与版本。
     * @param claims 已校验声明
     * @return 有界白名单状态
     */
    private static String refreshState(Claims claims) { return state(claims) + "|" + claims.sessionId(); }
}
