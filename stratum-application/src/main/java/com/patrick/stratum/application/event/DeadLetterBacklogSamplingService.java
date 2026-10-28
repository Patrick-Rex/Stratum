package com.patrick.stratum.application.event;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

import com.patrick.stratum.domain.event.DeadLetterBacklogProbe;
import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在事务外顺序采集固定组，将每组结果写入同一共享采样代号。
 * <p>示例：周期任务调用{@code service.sample()}；本组件不创建调度或线程。</p>
 */
public final class DeadLetterBacklogSamplingService {
    private final DeadLetterBacklogProbe probe;
    private final DeadLetterBacklogStore store;
    private final Duration ttl;

    /**
     * 固化查询、共享存储和有效期。
     * @param probe 不修改Broker的查询端口
     * @param store 共享快照端口
     * @param ttl 有界结果有效期
     * @throws NullPointerException 必需参数为空
     * @throws IllegalArgumentException 有效期越界
     * @apiNote 示例：{@code new DeadLetterBacklogSamplingService(probe, store, Duration.ofMinutes(2))}。
     */
    public DeadLetterBacklogSamplingService(DeadLetterBacklogProbe probe, DeadLetterBacklogStore store, Duration ttl) {
        this.probe = Objects.requireNonNull(probe, "查询端口不能为空"); this.store = Objects.requireNonNull(store, "存储端口不能为空");
        this.ttl = Objects.requireNonNull(ttl, "有效期不能为空");
        if (ttl.compareTo(Duration.ofMillis(100)) < 0 || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("指标有效期不合法");
        }
    }

    /**
     * 开始新采样并逐组写入；单组查询异常标记不可用，旧采样被替换时停止。
     * @return 两组均成功登记当前结果为true；采样已被替换为false，不代表两组均可用
     * @throws IllegalStateException 在事务内调用或共享存储失败
     * @throws InterruptedException 执行前或两组之间中断
     * @apiNote 示例：{@code boolean current = service.sample()}。
     */
    public boolean sample() throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("死信采样必须在事务外执行");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("死信采样已中断");
        var generation = store.begin();
        for (var group : Group.values()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("死信采样已中断");
            OptionalLong value;
            try { value = Objects.requireNonNull(probe.ready(group), "查询结果不能为空"); }
            catch (RuntimeException unavailable) { value = OptionalLong.empty(); }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("死信采样已中断");
            if (!store.record(generation, group, value, ttl)) return false;
        }
        return true;
    }
}
