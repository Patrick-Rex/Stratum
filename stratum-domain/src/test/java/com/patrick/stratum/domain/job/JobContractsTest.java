package com.patrick.stratum.domain.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证任务技术值对象、有限重试与敏感数据摘要，不把契约测试计为真实调度执行。 */
class JobContractsTest {
    private static final Instant TIME = Instant.parse("2026-10-26T00:00:00.123456Z");
    private static final JobDefinition.RetryPolicy POLICY = new JobDefinition.RetryPolicy(
            3, Duration.ofMillis(100), Duration.ofMillis(500));

    /** Cron与一次性计划字段互斥，定义保持原始有效文本及微秒时刻。 */
    @Test
    void validatesExclusiveScheduleFields() {
        var once = once(30);
        assertEquals(TIME, once.triggerAt().orElseThrow());
        var cron = cron("0/5 * * * * ?");
        assertEquals("0/5 * * * * ?", cron.cron().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new JobDefinition("probe", JobDefinition.Type.CRON,
                Optional.empty(), Optional.empty(), 30, POLICY, JobDefinition.IdempotencyRule.SCHEDULE_TIME));
        assertThrows(IllegalArgumentException.class, () -> new JobDefinition("probe", JobDefinition.Type.ONCE,
                Optional.of("0 * * * * ?"), Optional.of(TIME), 30, POLICY, JobDefinition.IdempotencyRule.EXPLICIT_KEY));
        assertThrows(IllegalArgumentException.class, () -> cron("\nsecret"));
        assertThrows(IllegalArgumentException.class, () -> cron("a".repeat(121)));
    }

    /** 非法代码、时限和会被数据库舍入的计划精度全部拒绝。 */
    @Test
    void boundsCodeTimeoutAndDatabaseTime() {
        assertThrows(IllegalArgumentException.class, () -> once(0));
        assertThrows(IllegalArgumentException.class, () -> once(301));
        for (var code : new String[]{"", "Upper", "bad space", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> new JobDefinition(code, JobDefinition.Type.ONCE,
                    Optional.empty(), Optional.of(TIME), 1, POLICY, JobDefinition.IdempotencyRule.EXPLICIT_KEY));
        }
        for (var time : new Instant[]{TIME.plusNanos(1), Instant.parse("0999-12-31T23:59:59Z"),
                Instant.parse("+10000-01-01T00:00:00Z")}) {
            assertThrows(IllegalArgumentException.class, () -> new JobDefinition("probe", JobDefinition.Type.ONCE,
                    Optional.empty(), Optional.of(time), 1, POLICY, JobDefinition.IdempotencyRule.EXPLICIT_KEY));
        }
        assertDoesNotThrow(() -> once(300));
    }

    /** 每周期尝试有限，指数退避封顶且极大失败次数不会导致溢出或长循环。 */
    @Test
    void capsRetryBackoffAndRejectsUnboundedPolicy() {
        assertEquals(Duration.ofMillis(100), POLICY.delayForFailure(1));
        assertEquals(Duration.ofMillis(200), POLICY.delayForFailure(2));
        assertEquals(Duration.ofMillis(400), POLICY.delayForFailure(3));
        assertEquals(Duration.ofMillis(500), POLICY.delayForFailure(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> POLICY.delayForFailure(0));
        assertThrows(IllegalArgumentException.class, () -> new JobDefinition.RetryPolicy(11, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new JobDefinition.RetryPolicy(1, Duration.ofMillis(99), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new JobDefinition.RetryPolicy(1, Duration.ofSeconds(1).plusNanos(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new JobDefinition.RetryPolicy(1, Duration.ofSeconds(1), Duration.ofHours(2)));
    }

    /** 已登记字段不可变；摘要不输出正文、幂等键或trace。 */
    @Test
    void preservesSubmissionWithoutExposingArguments() {
        UUID id = UUID.randomUUID();
        var submission = new JobExecutionRepository.Submission(id, once(30), TIME,
                "secret-key", "{\"secret\":12345678901234567890.123456}", "secret-trace");
        assertEquals(id, submission.executionId());
        assertTrue(submission.arguments().contains("12345678901234567890.123456"));
        assertFalse(submission.toString().contains("secret"));
        assertFalse(submission.toString().contains("123456"));
    }

    /** UTF-8字节上限与未配对代理字符必须严格核验，不让替换编码改变输入身份。 */
    @Test
    void boundsArgumentsByUtf8BytesAndEncoding() {
        assertDoesNotThrow(() -> submission("x".repeat(16_384), "key", "trace"));
        for (var arguments : new String[]{"", " ", "x".repeat(16_385), "中".repeat(5_462), "\uD800"}) {
            assertThrows(IllegalArgumentException.class, () -> submission(arguments, "key", "trace"));
        }
    }

    /** 显式键和trace只允许有界ASCII；一次性计划的时刻不能在登记时被替换。 */
    @Test
    void validatesIdentityAndTrace() {
        assertThrows(IllegalArgumentException.class, () -> submission("{}", "中", "trace"));
        assertThrows(IllegalArgumentException.class, () -> submission("{}", "x".repeat(129), "trace"));
        assertThrows(IllegalArgumentException.class, () -> submission("{}", "key", "bad\ntrace"));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Submission(
                UUID.randomUUID(), once(30), TIME.plusSeconds(1), "key", "{}", "trace"));
        assertThrows(NullPointerException.class, () -> new JobExecutionRepository.Submission(null, once(30), TIME, "key", "{}", "trace"));
    }

    /** 计划时刻幂等键由代码和UTC时刻确定，不接受调用者的另一身份。 */
    @Test
    void enforcesScheduleTimeIdempotencyKey() {
        var definition = cron("0/5 * * * * ?");
        assertDoesNotThrow(() -> new JobExecutionRepository.Submission(UUID.randomUUID(), definition,
                TIME, "outbox.relay:" + TIME, "{}", "trace"));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Submission(UUID.randomUUID(), definition,
                TIME, "arbitrary-key", "{}", "trace"));
    }

    /** 领取不依据本机当前时钟拒绝历史证据，但严格限制租期、尝试和失败周期。 */
    @Test
    void validatesLeaseEvidenceAndHidesToken() {
        var submission = submission("{}", "key", "trace");
        UUID token = UUID.randomUUID();
        var lease = new JobExecutionRepository.Lease(submission, token, TIME, TIME.plusSeconds(60), 1, 0, 0);
        assertEquals(token, lease.token());
        assertFalse(lease.toString().contains(token.toString()));
        assertFalse(lease.toString().contains("key"));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Lease(submission, token, TIME, TIME, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Lease(submission, token, TIME, TIME.plusSeconds(331), 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Lease(submission, token, TIME, TIME.plusSeconds(60), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Lease(submission, token, TIME, TIME.plusSeconds(60), 1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new JobExecutionRepository.Lease(submission, token, TIME, TIME.plusSeconds(60), 1, 0, -1));
    }

    /** 人工恢复只能引用当前死亡记录和安全trace，不能替换原任务参数。 */
    @Test
    void validatesReplayRequest() {
        UUID request = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        assertEquals(dead, new JobRecoveryRepository.ReplayRequest(request, dead, "replay-trace").deadId());
        assertThrows(IllegalArgumentException.class, () -> new JobRecoveryRepository.ReplayRequest(request, dead, "bad trace"));
        assertThrows(NullPointerException.class, () -> new JobRecoveryRepository.ReplayRequest(null, dead, "trace"));
    }

    /**
     * 构造显式幂等键的一次性任务夹具。
     * @param timeout 单次执行秒数
     * @return 不含业务副作用的定义
     */
    private JobDefinition once(int timeout) {
        return new JobDefinition("probe", JobDefinition.Type.ONCE, Optional.empty(), Optional.of(TIME),
                timeout, POLICY, JobDefinition.IdempotencyRule.EXPLICIT_KEY);
    }

    /**
     * 构造计划时刻幂等的Cron任务夹具，不用此处的文本校验替代Quartz解析。
     * @param expression Cron文本
     * @return 技术任务定义
     */
    private JobDefinition cron(String expression) {
        return new JobDefinition("outbox.relay", JobDefinition.Type.CRON, Optional.of(expression), Optional.empty(),
                30, POLICY, JobDefinition.IdempotencyRule.SCHEDULE_TIME);
    }

    /**
     * 构造一次性执行登记，用于独立输入边界断言。
     * @param arguments 参数文本
     * @param key 显式幂等键
     * @param trace 安全trace
     * @return 不可变登记值
     */
    private JobExecutionRepository.Submission submission(String arguments, String key, String trace) {
        return new JobExecutionRepository.Submission(UUID.randomUUID(), once(30), TIME, key, arguments, trace);
    }
}
