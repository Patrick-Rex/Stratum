package com.patrick.stratum.domain.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证重放任务首次绑定与领取输入的一致性，不执行重放或授权。 */
class DeadLetterReplayTaskBindingTest {
    /** 首次绑定匹配原任务，替换任务、请求、归档或处理器时拒绝。 */
    @Test
    void matchesOnlyTheOriginalTaskRequestAndArchive() {
        UUID task = UUID.randomUUID(), request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var binding = new DeadLetterReplayTaskRepository.Binding(request, archive, actor, task);
        var original = submission(task, request, archive, DeadLetterReplayJob.CODE);
        assertTrue(binding.matches(original)); assertThrows(NullPointerException.class, () -> binding.matches(null));
        assertFalse(binding.matches(submission(UUID.randomUUID(), request, archive, DeadLetterReplayJob.CODE)));
        assertFalse(binding.matches(submission(task, UUID.randomUUID(), archive, DeadLetterReplayJob.CODE)));
        assertFalse(binding.matches(submission(task, request, UUID.randomUUID(), DeadLetterReplayJob.CODE)));
        assertFalse(binding.matches(submission(task, request, archive, "other.code")));
    }

    /** 登记请求与执行绑定共用一致性规则，不能替换归档或请求键。 */
    @Test
    void registrationUsesTheSameFixedTargetRules() {
        UUID task = UUID.randomUUID(), request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var input = submission(task, request, archive, DeadLetterReplayJob.CODE);
        assertDoesNotThrow(() -> new DeadLetterReplayTaskRepository.Request(request, archive, actor, input));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskRepository.Request(UUID.randomUUID(), archive, actor, input));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskRepository.Request(request, UUID.randomUUID(), actor, input));
    }

    /**
     * 构造用于比较的单次任务，其他字段符合已有技术约束。
     * @param task 执行UUID
     * @param request 请求UUID
     * @param archive 归档UUID
     * @param code 处理器代码
     * @return 不可变任务输入
     */
    private JobExecutionRepository.Submission submission(UUID task, UUID request, UUID archive, String code) {
        Instant time = Instant.parse("2026-10-28T00:00:00Z");
        var definition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return new JobExecutionRepository.Submission(task, definition, time, request.toString(), DeadLetterReplayJob.arguments(archive), "binding-test");
    }
}
