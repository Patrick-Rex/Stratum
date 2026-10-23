package com.patrick.stratum.common.base.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** 框架无关的版本化 Cache Aside 门面，调用方传入序列化值及有界回源行为。 */
public interface VersionedCache {
    Duration NULL_TTL = Duration.ofSeconds(60);

    /**
     * 校验共用缓存键，预留重建锁前缀增加的 15 个字符。
     * @param key biz:业务域:资源:id 形式的键，最长 241 字符
     * @return 原键，便于调用处使用
     * @throws IllegalArgumentException 键格式或长度无效时抛出
     * @apiNote 示例：VersionedCache.validateKey("biz:erp:material:id")。
     */
    static String validateKey(String key) {
        if (key == null || key.length() > 241
                || !key.matches("biz:[a-z0-9_-]+:[a-z0-9_-]+:[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("业务缓存键必须符合 biz:业务域:资源:id 格式且不超过 241 字符");
        }
        return key;
    }

    /**
     * 读取缓存，未命中时合并回源；空结果同样缓存，超时不能伪装成不存在。
     * @param key biz:业务域:资源:id 形式的键
     * @param policy 按资源或热点级别选择的缓存策略
     * @param loader 回源函数，必须自行约束外部 I/O 时间并显式捕获所需权限上下文
     * @return 当前值或确实不存在的空结果
     * @throws UnavailableException 回源等待超时、容量耗尽或被取消时抛出
     * @apiNote 示例：cache.get(key, policy, () -> repository.findById(id).map(mapper))。
     */
    Optional<Value> get(String key, Policy policy, Supplier<Optional<Value>> loader);

    /**
     * 仅用不旧于已知版本的值更新缓存，防止迟到写入覆盖新状态。
     * @param key 业务缓存键
     * @param value 已提交的数据及版本
     * @param policy 缓存策略
     * @return 是否接受更新
     * @apiNote 示例：cache.putIfNewer(key, new Value(2, "{}"), policy)。
     */
    boolean putIfNewer(String key, Value value, Policy policy);

    /**
     * 删除缓存并保留有 TTL 的最低版本屏障，阻止旧回源结果重新写入。
     * @param key 业务缓存键
     * @param minimumVersion 已提交的最低版本
     * @param policy 用于计算屏障存活时间的缓存策略
     * @apiNote 示例：cache.invalidate(key, 3, policy)。
     */
    void invalidate(String key, long minimumVersion, Policy policy);

    /**
     * 不可变缓存值，不共享业务对象引用。
     * @param version 聚合版本，非负数
     * @param payload 序列化数据
     */
    record Value(long version, String payload) {
        /**
         * 验证版本与非空数据，空缓存通过 Optional.empty 表达。
         * @param version 非负版本
         * @param payload 序列化数据
         * @throws IllegalArgumentException 版本非法时抛出
         * @apiNote 示例：new Value(1, "{\"name\":\"物料\"}")。
         */
        public Value {
            if (version < 0) { throw new IllegalArgumentException("缓存版本不能为负数"); }
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 可按资源分级配置的过期策略，空值固定 60 秒，不参与抖动。
     * @param ttl 基础 TTL，1 秒至 24 小时
     * @param jitter 正负抖动范围，必须小于基础 TTL
     */
    record Policy(Duration ttl, Duration jitter) {
        /**
         * 校验有界 TTL，避免永久键及负存活时间。
         * @param ttl 基础存活时间
         * @param jitter 正负抖动范围
         * @throws IllegalArgumentException TTL 或抖动范围非法时抛出
         * @apiNote 示例：new Policy(Duration.ofMinutes(5), Duration.ofSeconds(30))。
         */
        public Policy {
            Objects.requireNonNull(ttl, "ttl");
            Objects.requireNonNull(jitter, "jitter");
            if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofHours(24)) > 0
                    || jitter.isNegative() || jitter.compareTo(ttl) >= 0
                    || jitter.toMillis() >= ttl.toMillis()) {
                throw new IllegalArgumentException("缓存 TTL 或抖动配置无效");
            }
        }

        /**
         * 计算本次写入 TTL，正常值带抖动，空值固定为 60 秒。
         * @param empty 是否为空值缓存
         * @param random 随机数来源
         * @return 正数存活时间
         * @apiNote 示例：policy.nextTtl(false, ThreadLocalRandom.current())。
         */
        public Duration nextTtl(boolean empty, RandomGenerator random) {
            if (empty) { return NULL_TTL; }
            long jitterMillis = jitter.toMillis();
            long offset = jitterMillis == 0 ? 0 : random.nextLong(-jitterMillis, jitterMillis + 1);
            return Duration.ofMillis(ttl.toMillis() + offset);
        }
    }

    /** 缓存重建暂时不可用，调用方不能将它解释为业务对象不存在。 */
    final class UnavailableException extends RuntimeException {
        /**
         * 创建可识别的缓存降级异常。
         * @param message 原因，不包含敏感业务数据
         * @param cause 根因，可为空
         * @apiNote 示例：new UnavailableException("缓存重建等待超时", cause)。
         */
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
