package com.patrick.stratum.common.base.lock;

import java.util.Optional;

/** 无框架依赖的分布式短租约锁契约，适用于缓存重建，不承诺数据库业务互斥。 */
public interface DistributedLock {

    /**
     * 立即尝试获取锁，锁必须有租期，锁服务故障不能伪装成获取成功。
     * @param key 以 lock: 开头的锁键
     * @return 成功时的租约或空结果
     * @apiNote 示例：lock.tryAcquire("lock:cache-rebuild:erp:material:id")。
     */
    Optional<Lease> tryAcquire(String key);

    /**
     * 在配置的时间与等待容量内获取锁；超时或等待容量耗尽返回空结果。
     * @param key 以 lock: 开头的锁键
     * @return 成功时的租约或空结果
     * @throws java.util.concurrent.CancellationException 当前线程被中断时抛出
     * @apiNote 获取成功后用 try-with-resources 关闭租约；默认最长等待 5 秒。
     */
    Optional<Lease> acquire(String key);

    /** 租约句柄；释放必须验证持有者身份，不能误删后继持有者的锁。 */
    interface Lease extends AutoCloseable {
        /**
         * 仅当当前 token 仍持有锁时删除，重复释放返回 false。
         * @return 是否实际释放了当前锁
         * @apiNote 示例：lease.release()。
         */
        boolean release();

        /**
         * 释放租约，适用于 try-with-resources；已过期或已释放时不修改他人锁。
         * @apiNote 示例：try (var lease = lock.tryAcquire(key).orElseThrow()) { ... }。
         */
        @Override
        default void close() { release(); }
    }
}
