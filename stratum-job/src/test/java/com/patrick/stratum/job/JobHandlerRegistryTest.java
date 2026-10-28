package com.patrick.stratum.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证有限注册表和定时定义，测试不执行任何处理器或创建调度资源。 */
class JobHandlerRegistryTest {
    /** 空列表没有隐式默认任务，未知代码拒绝。 */
    @Test
    void emptyRegistryDoesNotInventTasks() {
        var registry = new JobHandlerRegistry(List.of());
        assertTrue(registry.codes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.handler("unknown"));
        assertThrows(IllegalArgumentException.class, () -> registry.handler(null));
    }

    /** 列表和定义保存不可变快照，代码按固定顺序输出且处理器身份保持。 */
    @Test
    void snapshotsDefinitionsAndExposesImmutableSortedCodes() {
        var scheduled = new Probe("scheduled", Optional.of(cron("scheduled", JobDefinition.IdempotencyRule.SCHEDULE_TIME, "0/5 * * * * ?")));
        var explicit = new Probe("explicit", Optional.empty());
        var inputs = new ArrayList<JobHandler>(List.of(scheduled, explicit));
        var registry = new JobHandlerRegistry(inputs); inputs.clear();
        assertEquals(List.of("explicit", "scheduled"), registry.codes());
        assertSame(scheduled, registry.handler("scheduled"));
        assertEquals(scheduled.periodicDefinition(), registry.periodicDefinition("scheduled"));
        assertTrue(registry.periodicDefinition("explicit").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.codes().add("other"));
    }

    /** Relay固定代码只出现在专用调度器，普通代码不会被隐式转移或创造。 */
    @Test
    void partitionsFixedRelayFromOrdinaryHandlers() {
        var relay = new Probe("outbox.relay", Optional.empty());
        var ordinary = new Probe("warehouse.sync", Optional.empty());
        var backlog = new Probe("deadletter.backlog", Optional.empty());
        var registry = new JobHandlerRegistry(List.of(relay, ordinary, backlog));
        assertEquals(List.of("warehouse.sync"), registry.ordinaryCodes());
        assertEquals(List.of("outbox.relay"), registry.relayCodes());
        assertSame(relay, registry.handler("outbox.relay"));
        assertEquals(List.of("deadletter.backlog"), registry.backlogCodes());
        assertSame(backlog, registry.handler("deadletter.backlog"));
        assertTrue(new JobHandlerRegistry(List.of(ordinary)).relayCodes().isEmpty());
        assertTrue(new JobHandlerRegistry(List.of(ordinary)).backlogCodes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.relayCodes().clear());
    }

    /** 处理器数量最多64，不为多余输入分配无界注册结构。 */
    @Test
    void boundsHandlerCount() {
        var handlers = IntStream.range(0, 65).mapToObj(index -> (JobHandler) new Probe("handler." + index, Optional.empty())).toList();
        assertDoesNotThrow(() -> new JobHandlerRegistry(handlers.subList(0, 64)));
        assertThrows(IllegalArgumentException.class, () -> new JobHandlerRegistry(handlers));
    }

    /** 非法、重复和缺失处理器均在构造时失败，错误不暴露不可信代码文本。 */
    @Test
    void rejectsInvalidAndDuplicateCodes() {
        for (String code : List.of("", "Upper", "unsafe\nvalue", "x".repeat(65))) {
            var error = assertThrows(IllegalArgumentException.class, () -> new JobHandlerRegistry(List.of(new Probe(code, Optional.empty()))));
            if (!code.isEmpty()) assertFalse(error.getMessage().contains(code));
        }
        assertThrows(IllegalArgumentException.class, () -> new JobHandlerRegistry(List.of(new Probe("same", Optional.empty()), new Probe("same", Optional.empty()))));
        assertThrows(NullPointerException.class, () -> new JobHandlerRegistry(null));
        assertThrows(NullPointerException.class, () -> new JobHandlerRegistry(java.util.Arrays.asList((JobHandler) null)));
    }

    /** 周期定义必须属于同代码、Cron及计划时刻幂等规则，不能提供另一种执行计划。 */
    @Test
    void rejectsMismatchedPeriodicDefinitions() {
        var policy = new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30));
        var once = new JobDefinition("probe", JobDefinition.Type.ONCE, Optional.empty(), Optional.of(Instant.EPOCH),
                30, policy, JobDefinition.IdempotencyRule.SCHEDULE_TIME);
        for (var definition : List.of(once,
                cron("other", JobDefinition.IdempotencyRule.SCHEDULE_TIME, "0/5 * * * * ?"),
                cron("probe", JobDefinition.IdempotencyRule.EXPLICIT_KEY, "0/5 * * * * ?"),
                cron("probe", JobDefinition.IdempotencyRule.SCHEDULE_TIME, "invalid"))) {
            assertThrows(IllegalArgumentException.class, () -> new JobHandlerRegistry(List.of(new Probe("probe", Optional.of(definition)))));
        }
        assertThrows(NullPointerException.class, () -> new JobHandlerRegistry(List.of(new Probe("probe", null))));
    }

    /**
     * 构造无外部效果的Cron定义。
     * @param code 任务代码
     * @param rule 幂等规则
     * @param expression Cron文本
     * @return 技术定义
     */
    private JobDefinition cron(String code, JobDefinition.IdempotencyRule rule, String expression) {
        return new JobDefinition(code, JobDefinition.Type.CRON, Optional.of(expression), Optional.empty(), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)), rule);
    }

    /**
     * 仅用于注册契约的不可变处理器探针，不模拟任务执行。
     * @param jobCode 固定代码
     * @param periodic 周期定义
     */
    private record Probe(String jobCode, Optional<JobDefinition> periodic) implements JobHandler {
        /** {@inheritDoc} */
        @Override
        public Optional<JobDefinition> periodicDefinition() { return periodic; }
        /** {@inheritDoc} */
        @Override
        public void execute(Submission submission) { }
    }
}
