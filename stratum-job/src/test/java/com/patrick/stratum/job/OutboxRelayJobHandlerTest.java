package com.patrick.stratum.job;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.patrick.stratum.application.event.OutboxReconciliationApplicationService;
import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxReconciliationRepository;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** 验证Relay处理器的有界对账、发布顺序、输入和中断；不创建调度器。 */
class OutboxRelayJobHandlerTest {
    /** 对账先于领取并按扫描数限额，处理器使用固定Relay代码和UTC Cron定义。 */
    @Test
    void reconcilesBeforePublishingWithBoundedWork() throws Exception {
        var probe = new Probe(); var handler = handler(probe, 2);
        assertEquals("outbox.relay", handler.jobCode());
        assertEquals("0 0/1 * * * ?", handler.periodicDefinition().orElseThrow().cron().orElseThrow());
        handler.execute(submission(handler.periodicDefinition().orElseThrow(), "{}"));
        assertEquals(List.of("scan", "scan", "claim"), probe.calls);
    }

    /** 输入不能替换任务代码或携带额外正文，拒绝前不访问存储。 */
    @Test
    void rejectsOtherCodeAndArguments() {
        var probe = new Probe(); var handler = handler(probe, 1);
        assertThrows(IllegalArgumentException.class, () -> handler.execute(submission(handler.periodicDefinition().orElseThrow(), "{\"x\":1}")));
        var source = handler.periodicDefinition().orElseThrow();
        var other = new JobDefinition("other", source.jobType(), source.cron(), source.triggerAt(), source.timeoutSec(), source.retryPolicy(), source.idempotencyKeyRule());
        assertThrows(IllegalArgumentException.class, () -> handler.execute(submission(other, "{}")));
        assertTrue(probe.calls.isEmpty());
    }

    /** 已中断或对账过程中收到中断时不开始发布，并保留线程中断标志。 */
    @Test
    void preservesInterruptionAndAvoidsPublishing() {
        var probe = new Probe(); var handler = handler(probe, 2);
        boolean previous = Thread.interrupted();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> handler.execute(submission(handler.periodicDefinition().orElseThrow(), "{}")));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(probe.calls.isEmpty());
            Thread.interrupted(); probe.interruptOnScan = true;
            assertThrows(InterruptedException.class, () -> handler.execute(submission(handler.periodicDefinition().orElseThrow(), "{}")));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(List.of("scan"), probe.calls);
        } finally { Thread.interrupted(); if (previous) Thread.currentThread().interrupt(); }
    }

    /** 配置拒绝无效Cron及无界处理资源。 */
    @Test
    void validatesConfigurationBounds() {
        assertDoesNotThrow(() -> new OutboxRelayJobConfiguration.Properties("0 0/1 * * * ?", 1, 128, 300));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayJobConfiguration.Properties("bad", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayJobConfiguration.Properties("0 0/1 * * * ?", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayJobConfiguration.Properties("0 0/1 * * * ?", 1, 129, 1));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayJobConfiguration.Properties("0 0/1 * * * ?", 1, 1, 301));
    }

    /** 启用必须同时具备任务、消息和实际发布端口；默认关闭不创建处理器。 */
    @Test
    void requiresExplicitTaskAndMessagingConfiguration() {
        try (var disabled = context(false, false, false, false)) {
            assertTrue(disabled.getBeansOfType(OutboxRelayJobHandler.class).isEmpty());
        }
        assertThrows(RuntimeException.class, () -> context(true, false, true, true));
        assertThrows(RuntimeException.class, () -> context(true, true, false, true));
        assertThrows(RuntimeException.class, () -> context(true, true, true, false));
        try (var enabled = context(true, true, true, true)) {
            var handler = enabled.getBean(OutboxRelayJobHandler.class);
            assertEquals("0 0/1 * * * ?", handler.periodicDefinition().orElseThrow().cron().orElseThrow());
        }
    }

    /** 不使用标准追踪的隔离测试替身。 */
    private static final class TraceFake implements StandardTraceContext {
        /** {@inheritDoc} */
        @Override public java.util.Optional<Carrier> capture() { return java.util.Optional.empty(); }
        /** {@inheritDoc} */
        @Override public java.util.Optional<Carrier> normalize(String parent, String state) { return java.util.Optional.empty(); }
        /** {@inheritDoc} */
        @Override public TraceScope restore(java.util.Optional<Carrier> carrier) { return () -> { }; }
    }

    /**
     * 用独立Spring上下文验证配置开关及端口装配，失败时关闭上下文。
     * @param relayEnabled Relay开关
     * @param jobsEnabled 任务开关
     * @param messagingEnabled 消息开关
     * @param publisherPresent 是否注册真实发布端口替身
     * @return 必须由调用方关闭的上下文
     */
    private AnnotationConfigApplicationContext context(boolean relayEnabled, boolean jobsEnabled,
            boolean messagingEnabled, boolean publisherPresent) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("relay-test", Map.of(
                "stratum.jobs.relay.enabled", relayEnabled, "stratum.jobs.enabled", jobsEnabled,
                "stratum.messaging.enabled", messagingEnabled)));
        var work = new UnitFake(); var probe = new Probe();
        if (publisherPresent) context.registerBean(OutboxPublisher.class, () -> delivery -> OutboxPublisher.PublishResult.CONFIRMED);
        context.registerBean(OutboxRelayApplicationService.class,
                () -> new OutboxRelayApplicationService(work, probe, context.getBeanProvider(OutboxPublisher.class), new TraceFake()));
        context.registerBean(OutboxReconciliationApplicationService.class, () -> new OutboxReconciliationApplicationService(work, probe::scan));
        context.register(OutboxRelayJobConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /**
     * 组合实际Application入口和无I/O端口替身。
     * @param probe 本测试观察器
     * @param limit 最多扫描凭据数
     * @return 待测处理器
     */
    private OutboxRelayJobHandler handler(Probe probe, int limit) {
        var work = new UnitFake(); var beans = new StaticListableBeanFactory();
        beans.addBean("publisher", (OutboxPublisher) delivery -> OutboxPublisher.PublishResult.CONFIRMED);
        var relay = new OutboxRelayApplicationService(work, probe, beans.getBeanProvider(OutboxPublisher.class), new TraceFake());
        var reconciliation = new OutboxReconciliationApplicationService(work, probe::scan);
        return new OutboxRelayJobHandler(relay, reconciliation, new OutboxRelayJobConfiguration.Properties("0 0/1 * * * ?", 16, limit, 30));
    }

    /**
     * 生成不可变的周期执行输入。
     * @param definition 已验证定义
     * @param arguments 测试参数
     * @return 固定计划时刻的任务输入
     */
    private Submission submission(JobDefinition definition, String arguments) {
        var time = Instant.parse("2026-10-27T00:00:00Z");
        return new Submission(UUID.randomUUID(), definition, time, definition.jobCode() + ":" + time, arguments, "relay-test");
    }

    /** 简单执行替身；事务行为另由真实数据库验收。 */
    private static final class UnitFake implements UnitOfWork {
        /** {@inheritDoc} */
        @Override public <T> T execute(Supplier<T> action) { return action.get(); }
    }

    /** 记录顺序和中断，不访问外部服务。 */
    private static final class Probe implements OutboxRelayRepository {
        private final List<String> calls = new ArrayList<>();
        private boolean interruptOnScan;
        /**
         * 返回待扫描结果以验证应用层限额。
         * @return 固定的已扫描结果
         */
        private OutboxReconciliationRepository.ScanOutcome scan() {
            calls.add("scan"); if (interruptOnScan) Thread.currentThread().interrupt();
            return OutboxReconciliationRepository.ScanOutcome.SCANNED;
        }
        /** {@inheritDoc} */
        @Override public List<Claim> claim(ClaimRequest request) { calls.add("claim"); return List.of(); }
        /** {@inheritDoc} */
        @Override public boolean finish(Claim claim, OutboxPublisher.PublishResult result, RetryPolicy retryPolicy) {
            throw new AssertionError("空领取不应调用完成");
        }
    }
}
