package com.patrick.stratum.job;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.patrick.stratum.domain.event.DeadLetterBacklogProbe;
import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** 验证周期采集显式开关、依赖和配置边界，不启动调度器。 */
class DeadLetterBacklogJobConfigurationTest {
    /** 默认关闭，不创建固定处理器。 */
    @Test void disabledDoesNotCreateHandler() {
        try (var context = context(false, false, false, false, false, false)) {
            assertTrue(context.getBeansOfType(DeadLetterBacklogJobHandler.class).isEmpty());
        }
    }

    /** 任一必需开关或端口缺失时启动失败，不静默配置半个采集器。 */
    @Test void rejectsMissingPrerequisites() {
        assertThrows(RuntimeException.class, () -> context(true, false, true, true, true, true));
        assertThrows(RuntimeException.class, () -> context(true, true, false, true, true, true));
        assertThrows(RuntimeException.class, () -> context(true, true, true, false, true, true));
        assertThrows(RuntimeException.class, () -> context(true, true, true, true, false, true));
        assertThrows(RuntimeException.class, () -> context(true, true, true, true, true, false));
    }

    /** 全部前置条件齐备时提供唯一固定处理器，使用有界默认计划。 */
    @Test void createsHandlerOnlyWhenExplicitlyConfigured() {
        try (var context = context(true, true, true, true, true, true)) {
            var definition = context.getBean(DeadLetterBacklogJobHandler.class).periodicDefinition().orElseThrow();
            assertEquals("0 0/5 * * * ?", definition.cron().orElseThrow()); assertEquals(60, definition.timeoutSec());
            assertEquals(1, definition.retryPolicy().maxAttempts());
            assertEquals(1, context.getBeansOfType(DeadLetterBacklogJobHandler.class).size());
        }
    }

    /** 禁止非法Cron、无界时间预算及快照有效期。 */
    @Test void validatesSettings() {
        assertDoesNotThrow(() -> new DeadLetterBacklogJobConfiguration.Properties("0 0/5 * * * ?", 60, Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterBacklogJobConfiguration.Properties("bad", 60, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterBacklogJobConfiguration.Properties("0 0/5 * * * ?", 0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterBacklogJobConfiguration.Properties("0 0/5 * * * ?", 60, Duration.ofMillis(999)));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterBacklogJobConfiguration.Properties("0 0/5 * * * ?", 60, Duration.ofMinutes(11)));
    }

    /**
     * 构造只包含本配置与受控端口的上下文，失败时也关闭。
     * @param enabled 是否启用采集
     * @param jobs 是否启用任务
     * @param messaging 是否启用消息
     * @param metrics 是否启用共享指标
     * @param probe 是否提供查询端口
     * @param store 是否提供共享存储端口
     * @return 必须关闭的上下文
     */
    private AnnotationConfigApplicationContext context(boolean enabled, boolean jobs, boolean messaging, boolean metrics, boolean probe, boolean store) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sampling-test", Map.of(
                "stratum.jobs.deadletter-sampling.enabled", enabled, "stratum.jobs.enabled", jobs,
                "stratum.messaging.enabled", messaging, "stratum.observability.deadletter.enabled", metrics)));
        if (probe) context.registerBean(DeadLetterBacklogProbe.class, () -> group -> OptionalLong.empty());
        if (store) context.registerBean(DeadLetterBacklogStore.class, Store::new);
        context.register(DeadLetterBacklogJobConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /** 不连接外部资源的配置替身。 */
    private static final class Store implements DeadLetterBacklogStore {
        /** {@inheritDoc} */
        @Override public UUID begin() { return UUID.randomUUID(); }
        /** {@inheritDoc} */
        @Override public boolean record(UUID sample, Group group, OptionalLong ready, Duration ttl) { return true; }
        /** {@inheritDoc} */
        @Override public OptionalLong read(Group group) { return OptionalLong.empty(); }
    }
}
