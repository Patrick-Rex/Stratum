package com.patrick.stratum.infrastructure.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.base.cache.VersionedCache.Policy;
import com.patrick.stratum.common.base.cache.VersionedCache.Value;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** Redis 原子版本存储与 L1 适配；L1 命中前核对远端标记，避免跨实例更新后继续返回旧值。 */
@Component
@RequiredArgsConstructor
public class RedisVersionedCacheStore {
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
            local stamp = redis.call('get', KEYS[3]) or ''
            local body = redis.call('get', KEYS[1])
            local source = KEYS[1]
            if not body then body = redis.call('get', KEYS[2]); source = KEYS[2] end
            if not body or stamp == '' then return {stamp, '', -1} end
            return {stamp, body, redis.call('pttl', source)}
            """, List.class);
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            local current = redis.call('get', KEYS[3])
            if current and string.sub(current, 1, 19) > ARGV[1] then return 0 end
            redis.call('set', KEYS[3], ARGV[2], 'PX', ARGV[5])
            redis.call('set', KEYS[1], ARGV[3], 'PX', ARGV[4])
            redis.call('del', KEYS[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> WRITE_ABSENT = new DefaultRedisScript<>("""
            local current = redis.call('get', KEYS[3]) or ''
            if current ~= ARGV[1] then return 0 end
            redis.call('set', KEYS[3], ARGV[2], 'PX', ARGV[5])
            redis.call('set', KEYS[2], ARGV[3], 'PX', ARGV[4])
            redis.call('del', KEYS[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            local current = redis.call('get', KEYS[3])
            if current and string.sub(current, 1, 19) > ARGV[1] then return 0 end
            redis.call('set', KEYS[3], ARGV[2], 'PX', ARGV[3])
            redis.call('del', KEYS[1], KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Cache<String, String> local;
    private final JsonMapper json;
    private final LocalCacheProperties localProperties;

    /**
     * 查询缓存并区分未命中与已缓存的空值。
     * @param key biz:业务域:资源:id 形式的键
     * @return 命中信息及本次观察到的远端标记
     * @apiNote 示例：store.read("biz:erp:material:id")。
     */
    public Lookup read(String key) {
        var keys = keys(key);
        String stamp = redis.opsForValue().get(keys.get(2));
        String cached = local.getIfPresent(key);
        if (cached != null) {
            var entry = json.readValue(cached, LocalEntry.class);
            if (entry.stamp().equals(stamp) && entry.expiresAtNanos() - System.nanoTime() > 0) {
                return lookup(entry.value(), entry.stamp());
            }
            local.invalidate(key);
        }
        long readStarted = System.nanoTime();
        List<?> raw = redis.execute(READ, keys);
        if (raw == null || raw.size() != 3) { throw new IllegalStateException("Redis 缓存响应无效"); }
        String currentStamp = (String) raw.get(0);
        String body = (String) raw.get(1);
        long remainingMillis = ((Number) raw.get(2)).longValue();
        if (body.isEmpty() || remainingMillis <= 0) { return new Lookup(false, Optional.empty(), currentStamp); }
        var value = json.readValue(body, StoredValue.class);
        long localNanos = Duration.ofMillis(remainingMillis).compareTo(localProperties.ttl()) < 0
                ? Duration.ofMillis(remainingMillis).toNanos() : localProperties.ttl().toNanos();
        long deadline = readStarted + localNanos;
        if (deadline - System.nanoTime() <= 0) { return new Lookup(false, Optional.empty(), currentStamp); }
        local.put(key, json.writeValueAsString(new LocalEntry(currentStamp, value, deadline)));
        return lookup(value, currentStamp);
    }

    /**
     * 原子比较版本后更新正常缓存，同时清除对应空值缓存。
     * @param key 业务缓存键
     * @param value 已提交版本和值
     * @param policy TTL 策略
     * @return 是否接受此版本
     * @apiNote 示例：store.putIfNewer(key, new Value(1, "{}"), policy)。
     */
    public boolean putIfNewer(String key, Value value, Policy policy) {
        var keys = keys(key);
        String version = sortableVersion(value.version());
        Duration ttl = policy.nextTtl(false, ThreadLocalRandom.current());
        String body = json.writeValueAsString(new StoredValue(value.version(), value.payload()));
        Long result = redis.execute(WRITE, keys, version, stamp(version), body,
                Long.toString(ttl.toMillis()), Long.toString(guardTtl(policy).toMillis()));
        local.invalidate(key);
        return Long.valueOf(1).equals(result);
    }

    /**
     * 仅在回源期间远端标记未变时写入空值，防止并发新增被旧空结果覆盖。
     * @param key 业务缓存键
     * @param expectedStamp 回源前观察到的标记，未见记录时为空字符串
     * @param policy 屏障存活策略，空值本身固定 60 秒
     * @return 是否接受空值
     * @apiNote 示例：store.putAbsent(key, lookup.stamp(), policy)。
     */
    public boolean putAbsent(String key, String expectedStamp, Policy policy) {
        var keys = keys(key);
        String version = expectedStamp.isEmpty() ? sortableVersion(0) : expectedStamp.substring(0, 19);
        Long result = redis.execute(WRITE_ABSENT, keys, expectedStamp, stamp(version),
                json.writeValueAsString(new StoredValue(-1, null)), Long.toString(VersionedCache.NULL_TTL.toMillis()),
                Long.toString(guardTtl(policy).toMillis()));
        local.invalidate(key);
        return Long.valueOf(1).equals(result);
    }

    /**
     * 删除缓存并留下有期限的最低版本屏障，旧版本失效请求也不能删除更新的数据。
     * @param key 业务缓存键
     * @param minimumVersion 最低可接受版本
     * @param policy 屏障 TTL 策略
     * @return 是否执行了失效操作
     * @apiNote 示例：store.invalidate(key, 2, policy)。
     */
    public boolean invalidate(String key, long minimumVersion, Policy policy) {
        var keys = keys(key);
        String version = sortableVersion(minimumVersion);
        Long result = redis.execute(INVALIDATE, keys, version, stamp(version), Long.toString(guardTtl(policy).toMillis()));
        local.invalidate(key);
        return Long.valueOf(1).equals(result);
    }

    /**
     * 生成业务、空值与版本键，所有键均由 Lua 设置过期时间。
     * @param key 业务缓存键
     * @return 三个关联键
     * @throws IllegalArgumentException 键格式非法时抛出
     */
    private static List<String> keys(String key) {
        if (key == null || key.length() > 256
                || !key.matches("biz:[a-z0-9_-]+:[a-z0-9_-]+:[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("业务缓存键必须符合 biz:业务域:资源:id 格式且不超过 256 字符");
        }
        String suffix = key.substring(4);
        return List.of(key, "null:" + suffix, "version:" + suffix);
    }

    /**
     * 编码为固定宽度十进制，避免 Lua double 对大整数版本比较丢失精度。
     * @param version 非负版本
     * @return 19 位版本字符串
     * @throws IllegalArgumentException 版本为负数时抛出
     */
    private static String sortableVersion(long version) {
        if (version < 0) { throw new IllegalArgumentException("版本不能为负数"); }
        String digits = Long.toString(version);
        return "0".repeat(19 - digits.length()) + digits;
    }

    /**
     * 同版本失效或空值变化也生成不同标记，确保其他实例的 L1 可识别变化。
     * @param version 固定宽度版本
     * @return 版本与随机标记
     */
    private static String stamp(String version) { return version + ":" + UUID.randomUUID(); }

    /**
     * 屏障长于所有关联值 TTL，另保留 60 秒，覆盖 5 秒回源窗口和 30 秒锁租期。
     * @param policy 数据过期策略
     * @return 屏障存活时间
     */
    private static Duration guardTtl(Policy policy) {
        Duration maximum = policy.ttl().plus(policy.jitter());
        return (maximum.compareTo(VersionedCache.NULL_TTL) < 0 ? VersionedCache.NULL_TTL : maximum)
                .plus(VersionedCache.NULL_TTL);
    }

    /**
     * 将存储空值与正常值恢复为门面契约。
     * @param stored Redis 或 L1 中的值
     * @param stamp 当前标记
     * @return 缓存命中结果
     */
    private static Lookup lookup(StoredValue stored, String stamp) {
        return new Lookup(true, stored.payload() == null ? Optional.empty()
                : Optional.of(new Value(stored.version(), stored.payload())), stamp);
    }

    /**
     * 查询结果：hit=false 与 hit=true/value=empty 具有不同含义。
     * @param hit 是否命中缓存
     * @param value 正常值或空值
     * @param stamp 远端版本标记
     */
    public record Lookup(boolean hit, Optional<Value> value, String stamp) { }

    /**
     * Redis 内部值，负版本且空 payload 仅代表空值缓存。
     * @param version 聚合版本
     * @param payload 序列化数据，空值时为 null
     */
    private record StoredValue(long version, String payload) { }

    /**
     * 仅保存在本机的 L1 快照，使用单调时间避免时钟调整延长缓存寿命。
     * @param stamp 远端标记
     * @param value 缓存数据
     * @param expiresAtNanos 本机单调截止时间
     */
    private record LocalEntry(String stamp, StoredValue value, long expiresAtNanos) { }
}
