package com.patrick.stratum.infrastructure.job;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 仅限制本实例续期任务数量；跨实例资格始终交给数据库ShedLock，不以内存容量代替分布式锁。
 */
final class BoundedJobLockProvider implements LockProvider, AutoCloseable {
    private final LockProvider delegate;
    private final Semaphore capacity;
    private final ThreadPoolTaskScheduler renewal;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 设置固定续期池和在途锁数量，仅拥有内部续期线程，不创建数据源。
     * @param database 可扩展的实际数据库锁提供者
     * @param workers 已校验的1至8个续期线程
     * @param name 已校验调度器命名空间，供线程清理追踪
     */
    BoundedJobLockProvider(ExtensibleLockProvider database, int workers, String name) {
        this.capacity = new Semaphore(2 * workers + 2);
        this.renewal = new ThreadPoolTaskScheduler();
        renewal.setPoolSize(workers);
        renewal.setThreadNamePrefix("stratum-job-lock-" + name + "-");
        renewal.setRemoveOnCancelPolicy(true);
        renewal.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        renewal.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        renewal.setWaitForTasksToCompleteOnShutdown(false);
        renewal.setAwaitTerminationSeconds(5);
        renewal.initialize();
        this.delegate = new KeepAliveLockProvider(database, renewal.getScheduledThreadPoolExecutor());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SimpleLock> lock(LockConfiguration configuration) {
        String name = configuration.getName();
        if (name == null || name.length() > 128 || !name.matches("lock:job:[A-Za-z0-9:._-]+")) {
            throw new IllegalArgumentException("任务锁名称不合法");
        }
        if (closed.get() || !capacity.tryAcquire()) return Optional.empty();
        boolean transferred = false;
        try {
            var lock = delegate.lock(configuration);
            if (lock.isEmpty()) return Optional.empty();
            var result = new Lease(lock.orElseThrow());
            if (closed.get()) {
                lock.orElseThrow().unlock();
                return Optional.empty();
            }
            transferred = true;
            return Optional.of(result);
        } finally {
            if (!transferred) capacity.release();
        }
    }

    /**
     * 停止接纳并取消续期，最多等待五秒退出内部线程；持有者应先由执行器关闭。
     * 不删除数据库锁，进程结束后的持有资格仍由数据库租期决定。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) renewal.destroy();
    }

    /** 本实例释放凭据，只有实际解锁后才释放一次续期容量。 */
    private final class Lease implements SimpleLock {
        private final SimpleLock original;
        private final AtomicBoolean released = new AtomicBoolean();

        /**
         * 保留真实数据库锁，不在包装过程中改变所有权。
         * @param original 实际续期锁
         */
        private Lease(SimpleLock original) { this.original = original; }

        /** {@inheritDoc} */
        @Override
        public void unlock() {
            if (!released.compareAndSet(false, true)) return;
            try {
                original.unlock();
            } finally {
                capacity.release();
            }
        }
    }
}
