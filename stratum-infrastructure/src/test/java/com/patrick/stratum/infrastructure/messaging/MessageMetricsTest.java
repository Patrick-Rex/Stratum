package com.patrick.stratum.infrastructure.messaging;

import java.util.List;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxPublisher.PublishResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证消息指标区分发布、消费及重投，且指标异常不影响调用方。 */
class MessageMetricsTest {
    /** 使用固定枚举结果及订阅组，不记录事件、正文或追踪身份。 */
    @Test
    void recordsBoundedIndependentOutcomes() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MessageMetrics(registry);
            metrics.published(PublishResult.CONFIRMED); metrics.published(PublishResult.RETURNED); metrics.publishException();
            metrics.consumed(Group.ERP_AUDIT, MessageMetrics.ConsumerResult.CONSUMED, false);
            metrics.consumed(Group.ERP_AUDIT, MessageMetrics.ConsumerResult.WAITING, true);
            metrics.consumed(Group.CACHE_INVALIDATION, MessageMetrics.ConsumerResult.FAILED, true);
            assertEquals(1, registry.get("stratum.messaging.published").tag("result", "confirmed").counter().count());
            assertEquals(1, registry.get("stratum.messaging.published").tag("result", "returned").counter().count());
            assertEquals(1, registry.get("stratum.messaging.published").tag("result", "exception").counter().count());
            assertEquals(1, registry.get("stratum.messaging.consumed").tags("group", "erp_audit", "result", "consumed").counter().count());
            assertEquals(1, registry.get("stratum.messaging.consumed").tags("group", "erp_audit", "result", "waiting").counter().count());
            assertEquals(1, registry.get("stratum.messaging.redelivered").tag("group", "erp_audit").counter().count());
            assertEquals(1, registry.get("stratum.messaging.redelivered").tag("group", "cache_invalidation").counter().count());
            assertTrue(registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().stream()
                    .allMatch(tag -> List.of("group", "result").contains(tag.getKey()))));
        } finally { registry.close(); }
    }

    /** 独立无注册表和指标后端运行时异常都不会抛给消息调用方。 */
    @Test
    void isolatesUnavailableOrFailingRegistry() {
        var disabled = new MessageMetrics(null);
        assertDoesNotThrow(() -> disabled.published(PublishResult.CONFIRMED));
        assertDoesNotThrow(() -> disabled.consumed(Group.ERP_AUDIT, MessageMetrics.ConsumerResult.CONSUMED, true));
        var failing = new SimpleMeterRegistry() {
            /** {@inheritDoc} */
            @Override public Counter counter(String name, String... tags) { throw new IllegalStateException("secret-metric-test"); }
        };
        try {
            var metrics = new MessageMetrics(failing);
            assertDoesNotThrow(() -> metrics.published(PublishResult.TIMED_OUT));
            assertDoesNotThrow(() -> metrics.consumed(Group.ERP_AUDIT, MessageMetrics.ConsumerResult.FAILED, true));
        } finally { failing.close(); }
    }
}
