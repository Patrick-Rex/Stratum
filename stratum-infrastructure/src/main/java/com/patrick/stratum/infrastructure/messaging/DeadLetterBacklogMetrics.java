package com.patrick.stratum.infrastructure.messaging;

import java.util.Locale;
import java.util.Objects;

import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 按固定订阅组暴露共享可见数，不可用时输出NaN而非零。
 * <p>示例：{@code new DeadLetterBacklogMetrics(registry, store)}。</p>
 */
public final class DeadLetterBacklogMetrics {
    private final DeadLetterBacklogStore store;

    /**
     * 注册两个固定组Gauge并持有共享状态端口，不创建采样线程。
     * @param registry 应用指标注册表
     * @param store 共享快照读取端口
     * @throws NullPointerException 参数为空
     * @apiNote 示例：{@code new DeadLetterBacklogMetrics(registry, store)}。
     */
    public DeadLetterBacklogMetrics(MeterRegistry registry, DeadLetterBacklogStore store) {
        this.store = Objects.requireNonNull(store, "共享状态不能为空"); Objects.requireNonNull(registry, "指标注册表不能为空");
        for (var group : Group.values()) {
            Gauge.builder("stratum.messaging.deadletter.visible", this, metrics -> metrics.value(group))
                    .description("Broker死信队列可见待处理数，不可用为NaN")
                    .tag("group", group.name().toLowerCase(Locale.ROOT)).register(registry);
        }
    }

    /**
     * 读取当前未过期快照；端口失败也保持不可用，不能冒充空队列。
     * @param group 固定订阅组
     * @return 有效非负值或NaN
     */
    private double value(Group group) {
        try { var ready = store.read(group); return ready.isPresent() && ready.getAsLong() >= 0 ? ready.getAsLong() : Double.NaN; }
        catch (RuntimeException unavailable) { return Double.NaN; }
    }
}
