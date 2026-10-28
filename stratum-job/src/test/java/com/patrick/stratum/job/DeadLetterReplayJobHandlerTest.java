package com.patrick.stratum.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 固定重放处理器输入、执行委托与中断边界的无I/O验证。 */
class DeadLetterReplayJobHandlerTest {
    /** 固定代码仅处理单次任务，原任务身份和参数在重复执行时不变。 */
    @Test
    void delegatesTheSameFixedTaskWithoutClaimingConsumption() throws Exception {
        var calls = new ArrayList<Submission>(); var handler = new DeadLetterReplayJobHandler(calls::add);
        var input = submission(DeadLetterReplayJob.CODE, UUID.randomUUID().toString(), DeadLetterReplayJob.arguments(UUID.randomUUID()));
        assertEquals(DeadLetterReplayJob.CODE, handler.jobCode()); assertTrue(handler.periodicDefinition().isEmpty());
        handler.execute(input); handler.execute(input);
        assertEquals(List.of(input, input), calls); assertSame(input, calls.getFirst());
    }

    /** 拒绝其他代码、非规范UUID、额外或残缺JSON；失败时不调用应用入口。 */
    @Test
    void rejectsWrongTargetAndMalformedInput() {
        var calls = new ArrayList<Submission>(); var handler = new DeadLetterReplayJobHandler(calls::add);
        UUID archive = UUID.randomUUID(); String key = UUID.randomUUID().toString();
        assertThrows(NullPointerException.class, () -> handler.execute(null));
        assertThrows(IllegalArgumentException.class, () -> handler.execute(submission("other", key, DeadLetterReplayJob.arguments(archive))));
        assertThrows(IllegalArgumentException.class, () -> handler.execute(submission(DeadLetterReplayJob.CODE, "not-a-uuid", DeadLetterReplayJob.arguments(archive))));
        for (String args : List.of("{}", "{\"archiveId\":\"}", "{\"archiveId\":\"bad\"}",
                "{\"archiveId\":\"" + archive + "\",\"extra\":1}", DeadLetterReplayJob.arguments(archive).toUpperCase())) {
            assertThrows(IllegalArgumentException.class, () -> handler.execute(submission(DeadLetterReplayJob.CODE, key, args)));
        }
        assertTrue(calls.isEmpty());
    }

    /** 执行前中断不调用端口，执行后中断不伪报成功并保留中断标志。 */
    @Test
    void propagatesInterruptionBeforeAndAfterExecution() {
        var calls = new ArrayList<Submission>(); var input = submission(DeadLetterReplayJob.CODE, UUID.randomUUID().toString(),
                DeadLetterReplayJob.arguments(UUID.randomUUID()));
        boolean previous = Thread.interrupted();
        try {
            var first = new DeadLetterReplayJobHandler(calls::add); Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> first.execute(input)); assertTrue(calls.isEmpty()); assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted(); var second = new DeadLetterReplayJobHandler(value -> { calls.add(value); Thread.currentThread().interrupt(); });
            assertThrows(InterruptedException.class, () -> second.execute(input)); assertEquals(List.of(input), calls); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); if (previous) Thread.currentThread().interrupt(); }
    }

    /** 执行入口的失败原样传播，不把失败结果变成成功。 */
    @Test
    void propagatesExecutionFailure() {
        var handler = new DeadLetterReplayJobHandler(value -> { throw new IllegalStateException("probe"); });
        var input = submission(DeadLetterReplayJob.CODE, UUID.randomUUID().toString(), DeadLetterReplayJob.arguments(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> handler.execute(input));
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
