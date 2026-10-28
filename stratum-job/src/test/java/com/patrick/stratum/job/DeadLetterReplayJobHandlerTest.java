package com.patrick.stratum.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.application.job.DeadLetterReplayExecutionService;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** 固定重放处理器的领取证据、输入、中断及启用边界验证。 */
class DeadLetterReplayJobHandlerTest {
    /** 固定处理器只接受原领取证据，原令牌与任务身份在重复执行时不变。 */
    @Test
    void delegatesTheSameLeaseWithoutClaimingConsumption() throws Exception {
        var calls = new ArrayList<Lease>(); var handler = new DeadLetterReplayJobHandler(calls::add);
        var input = lease(submission(DeadLetterReplayJob.CODE, UUID.randomUUID().toString(), DeadLetterReplayJob.arguments(UUID.randomUUID())));
        assertEquals(DeadLetterReplayJob.CODE, handler.jobCode()); assertTrue(handler.periodicDefinition().isEmpty());
        assertThrows(IllegalStateException.class, () -> handler.execute(input.submission()));
        handler.executeLease(input); handler.executeLease(input);
        assertEquals(List.of(input, input), calls); assertSame(input, calls.getFirst());
    }

    /** 拒绝其他代码、非规范UUID、额外或残缺JSON；失败时不调用应用入口。 */
    @Test
    void rejectsWrongTargetAndMalformedInput() {
        var calls = new ArrayList<Lease>(); var handler = new DeadLetterReplayJobHandler(calls::add);
        UUID archive = UUID.randomUUID(); String key = UUID.randomUUID().toString();
        assertThrows(NullPointerException.class, () -> handler.executeLease(null));
        assertThrows(IllegalArgumentException.class, () -> handler.executeLease(lease(submission("other", key, DeadLetterReplayJob.arguments(archive)))));
        assertThrows(IllegalArgumentException.class, () -> handler.executeLease(lease(submission(DeadLetterReplayJob.CODE, "not-a-uuid", DeadLetterReplayJob.arguments(archive)))));
        for (String args : List.of("{}", "{\"archiveId\":\"}", "{\"archiveId\":\"bad\"}",
                "{\"archiveId\":\"" + archive + "\",\"extra\":1}", DeadLetterReplayJob.arguments(archive).toUpperCase())) {
            assertThrows(IllegalArgumentException.class, () -> handler.executeLease(lease(submission(DeadLetterReplayJob.CODE, key, args))));
        }
        assertTrue(calls.isEmpty());
    }

    /** 执行前中断不调用端口，执行后中断不伪报成功并保留中断标志。 */
    @Test
    void propagatesInterruptionBeforeAndAfterExecution() {
        var calls = new ArrayList<Lease>(); var input = lease(submission(DeadLetterReplayJob.CODE, UUID.randomUUID().toString(),
                DeadLetterReplayJob.arguments(UUID.randomUUID())));
        boolean previous = Thread.interrupted();
        try {
            var first = new DeadLetterReplayJobHandler(calls::add); Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> first.executeLease(input)); assertTrue(calls.isEmpty()); assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted(); var second = new DeadLetterReplayJobHandler(value -> { calls.add(value); Thread.currentThread().interrupt(); });
            assertThrows(InterruptedException.class, () -> second.executeLease(input)); assertEquals(List.of(input), calls); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); if (previous) Thread.currentThread().interrupt(); }
    }

    /** 执行入口的失败原样传播，不把失败结果变成成功。 */
    @Test
    void propagatesExecutionFailure() {
        var handler = new DeadLetterReplayJobHandler(value -> { throw new IllegalStateException("probe"); });
        var input = lease(submission(DeadLetterReplayJob.CODE, UUID.randomUUID().toString(), DeadLetterReplayJob.arguments(UUID.randomUUID())));
        assertThrows(IllegalStateException.class, () -> handler.executeLease(input));
    }

    /** 重放处理器默认关闭，启用时必须同时显式启用任务执行。 */
    @Test
    void requiresExplicitReplayAndJobEnablement() {
        try (var disabled = context(false, false)) { assertTrue(disabled.getBeansOfType(DeadLetterReplayJobHandler.class).isEmpty()); }
        assertThrows(RuntimeException.class, () -> context(true, false));
        try (var enabled = context(true, true)) {
            assertEquals(DeadLetterReplayJob.CODE, enabled.getBean(DeadLetterReplayJobHandler.class).jobCode());
        }
    }

    /**
     * 创建仅用于配置条件检查的Spring上下文，不启动调度器。
     * @param replay 是否启用重放处理器
     * @param jobs 是否启用任务执行
     * @return 调用方关闭的上下文
     */
    private AnnotationConfigApplicationContext context(boolean replay, boolean jobs) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("replay-config-test", Map.of(
                "stratum.jobs.replay.enabled", replay, "stratum.jobs.enabled", jobs)));
        context.registerBean(DeadLetterReplayExecutionService.class, () -> new DeadLetterReplayExecutionService(null, null, null, null, null));
        context.register(DeadLetterReplayJobConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /**
     * 构造有界的模拟领取证据，仅用于无I/O输入和中断测试。
     * @param submission 不可变任务
     * @return 不代表数据库领取的测试证据
     */
    private Lease lease(Submission submission) {
        var start = Instant.parse("2026-10-28T00:00:01Z");
        return new Lease(submission, UUID.randomUUID(), start, start.plusSeconds(60), 1, 0, 0);
    }

    /**
     * 构造指定固定代码和输入的一次性任务。
     * @param code 测试任务代码
     * @param key 测试幂等键
     * @param arguments 测试参数
     * @return 不可变任务输入
     */
    private Submission submission(String code, String key, String arguments) {
        var time = Instant.parse("2026-10-28T00:00:00Z");
        var definition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return new Submission(UUID.randomUUID(), definition, time, key, arguments, "handler-test");
    }
}
