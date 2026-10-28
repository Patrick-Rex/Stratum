package com.patrick.stratum.infrastructure.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 以Redis原子脚本保存固定组可见快照，使用唯一采样代号阻止迟到工作覆盖。
 * <p>示例：{@code new RedisDeadLetterBacklogStore(redis, "stratum")}。</p>
 */
public final class RedisDeadLetterBacklogStore implements DeadLetterBacklogStore {
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('set', KEYS[2], ARGV[1] .. '|' .. ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> READ = new DefaultRedisScript<>("""
            local generation = redis.call('get', KEYS[1])
            local value = redis.call('get', KEYS[2])
            if not generation or not value or redis.call('pttl', KEYS[2]) <= 0 then return '' end
            local prefix = generation .. '|'
            if string.sub(value, 1, string.len(prefix)) ~= prefix then return '' end
            return string.sub(value, string.len(prefix) + 1)
            """, String.class);
    private final StringRedisTemplate redis;
    private final String prefix;

    /**
     * 固化隔离命名空间，所有脚本键共享Redis Cluster哈希槽。
     * @param redis 有界连接配置的客户端
     * @param namespace 1至48个小写字母、数字或连字符，以字母开头
     * @throws NullPointerException Redis客户端为空
     * @throws IllegalArgumentException 命名空间不合法
     * @apiNote 示例：{@code new RedisDeadLetterBacklogStore(redis, "stratum")}。
     */
    public RedisDeadLetterBacklogStore(StringRedisTemplate redis, String namespace) {
        this.redis = Objects.requireNonNull(redis, "Redis客户端不能为空");
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("指标命名空间不合法");
        this.prefix = namespace + ":{deadletter-backlog}:";
    }

    /** {@inheritDoc} */
    @Override public UUID begin() {
        UUID sample = UUID.randomUUID(); redis.opsForValue().set(prefix + "generation", sample.toString()); return sample;
    }

    /** {@inheritDoc} */
    @Override public boolean record(UUID sample, Group group, OptionalLong ready, Duration ttl) {
        Objects.requireNonNull(sample, "采样代号不能为空"); Objects.requireNonNull(group, "订阅组不能为空");
        Objects.requireNonNull(ready, "可见数不能为空");
        if (ready.isPresent() && ready.getAsLong() < 0) throw new IllegalArgumentException("可见数不能为负数");
        if (ttl == null || ttl.compareTo(Duration.ofMillis(100)) < 0 || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("指标有效期不合法");
        }
        String value = ready.isPresent() ? Long.toString(ready.getAsLong()) : "-1";
        Long result = redis.execute(WRITE, keys(group), sample.toString(), value, Long.toString(ttl.toMillis()));
        if (result == null) throw new IllegalStateException("共享采样写入未返回结果");
        return result == 1;
    }

    /** {@inheritDoc} */
    @Override public OptionalLong read(Group group) {
        Objects.requireNonNull(group, "订阅组不能为空");
        try {
            String result = redis.execute(READ, keys(group));
            if (result == null || !result.matches("0|[1-9][0-9]{0,18}")) return OptionalLong.empty();
            return OptionalLong.of(Long.parseLong(result));
        } catch (RuntimeException unavailable) { return OptionalLong.empty(); }
    }

    /**
     * 返回固定组对应的同槽键，不接受外部队列名称。
     * @param group 固定订阅组
     * @return 代号与组快照键
     */
    private List<String> keys(Group group) {
        return List.of(prefix + "generation", prefix + group.name().toLowerCase(Locale.ROOT));
    }
}
