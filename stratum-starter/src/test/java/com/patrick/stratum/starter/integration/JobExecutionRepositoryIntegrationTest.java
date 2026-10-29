package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.job.JobCancellationRepository;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskRepository;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import com.patrick.stratum.domain.job.JobLeaseAuthority;
import com.patrick.stratum.domain.job.JobRecoveryRepository;
import com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MariaDB验证生产登记与竞争领取；超时位置只用自身SQL时钟夹具，不伪装真实任务执行。 */
class JobExecutionRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");
    private final UnitOfWork unitOfWork;
    private final JobExecutionRepository repository;
    private final JobCancellationRepository cancellation;
    private final DeadLetterReplayTaskRepository replayTasks;
    private final JobLeaseAuthority leaseAuthority;
    private final JobRecoveryRepository recovery;
    private final JdbcTemplate jdbc;
    private final List<UUID> executions = new ArrayList<>();
    private final List<UUID> replayRequests = new ArrayList<>();
    private final String code = "probe." + UUID.randomUUID();

    /**
     * 注入应用工作单元和实际任务仓储。
     * @param unitOfWork 真实事务入口
     * @param repository 生产登记领取端口
     * @param cancellation 生产未开始取消端口
     * @param replayTasks 重放任务绑定端口
     * @param leaseAuthority 任务领取资格端口
     * @param recovery 任务完成端口
     * @param jdbc 独立断言与自身夹具清理
     */
    @Autowired
    JobExecutionRepositoryIntegrationTest(UnitOfWork unitOfWork, JobExecutionRepository repository,
            JobCancellationRepository cancellation, DeadLetterReplayTaskRepository replayTasks, JobLeaseAuthority leaseAuthority,
            JobRecoveryRepository recovery, JdbcTemplate jdbc) {
        this.unitOfWork = unitOfWork; this.repository = repository; this.cancellation = cancellation; this.replayTasks = replayTasks;
        this.leaseAuthority = leaseAuthority; this.recovery = recovery; this.jdbc = jdbc;
    }

    /** 仅清理本用例登记的随机执行身份及其技术历史。 */
    @AfterEach
    void cleanup() {
        for (UUID id : replayRequests) jdbc.update("DELETE FROM dead_letter_replay_task WHERE request_id=?", id.toString());
        for (UUID id : executions) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id.toString());
        }
    }

    /** 真实登记保存原始高精度JSON、定义、首次trace，并在独立事务中可见。 */
    @Test
    void persistsOriginalInputWithoutLosingPrecision() {
        var input = input("{\"quantity\":9999999999999999999999.123456789012}", 3);
        assertEquals(input.executionId(), register(input));
        var row = jdbc.queryForMap("SELECT * FROM job_execution WHERE execution_id=?", input.executionId().toString());
        assertEquals(input.arguments(), row.get("arguments"));
        assertEquals(input.traceId(), row.get("initial_trace_id"));
        assertEquals("WAITING", row.get("state"));
        assertEquals(0L, ((Number) row.get("attempt_no")).longValue());
        var lease = claim(input.executionId()).orElseThrow();
        assertEquals(input, lease.submission());
    }

    /** 正常登记保存初始和当前标准上下文，重复登记不能覆盖首次上下文。 */
    @Test
    void capturesStandardTraceAndKeepsFirstForEquivalentRegistration() {
        var trace = new OpenTelemetryTraceContext(); var first = input("{}", 3);
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        try (var ignored = trace.restore(trace.normalize(parent, "vendor=first"))) {
            assertEquals(first.executionId(), register(first));
        }
        var repeated = copy(first, id(), first.idempotencyKey(), first.arguments(), "new-business-trace");
        try (var ignored = trace.restore(trace.normalize("00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01", "vendor=second"))) {
            assertEquals(first.executionId(), register(repeated));
        }
        assertStandardTrace(first.executionId(), parent, "vendor=first"); assertEquals(1, count());
    }

    /** 空上下文不会伪造标准标识，回滚时任务及标准上下文同时撤销。 */
    @Test
    void keepsEmptyStandardTraceAndRollsBackWithTask() {
        var trace = new OpenTelemetryTraceContext(); var empty = input("{}", 3); var rolledBack = input("{}", 3);
        try (var ignored = trace.restore(Optional.empty())) { register(empty); }
        assertStandardTrace(empty.executionId(), null, null);
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        try (var ignored = trace.restore(trace.normalize(parent, "vendor=rollback"))) {
            assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
                repository.register(rolledBack); throw new IllegalStateException("rollback-probe");
            }));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE execution_id=?", Integer.class,
                    rolledBack.executionId().toString()));
            register(rolledBack);
        }
        assertStandardTrace(rolledBack.executionId(), parent, "vendor=rollback");
    }

    /** 死信重放任务复用登记边界捕获原始上下文，重复请求不覆盖。 */
    @Test
    void capturesStandardTraceForReplayTaskRegistration() {
        var trace = new OpenTelemetryTraceContext(); UUID request = UUID.randomUUID();
        var first = replayRequest(request, UUID.randomUUID(), UUID.randomUUID(), TIME);
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        try (var ignored = trace.restore(trace.normalize(parent, "vendor=replay"))) {
            assertEquals(first.submission().executionId(), unitOfWork.execute(() -> replayTasks.register(first)));
        }
        try (var ignored = trace.restore(Optional.empty())) {
            assertEquals(first.submission().executionId(), unitOfWork.execute(() -> replayTasks.register(first)));
        }
        assertStandardTrace(first.submission().executionId(), parent, "vendor=replay");
    }

    /** 领取和过期接管后的新尝试保留原始字段，篡改字段不能通过当前领取资格核验。 */
    @Test
    void carriesCurrentTraceWithLeaseAndFencesAlteredEvidence() {
        var trace = new OpenTelemetryTraceContext(); var input = input("{}", 3);
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        try (var ignored = trace.restore(trace.normalize(parent, "vendor=first"))) { register(input); }
        var original = claim(input.executionId()).orElseThrow();
        assertEquals(new JobExecutionRepository.TraceFields(parent, "vendor=first"), original.standardTrace().orElseThrow());
        assertTrue(unitOfWork.execute(() -> leaseAuthority.lockCurrent(original)));
        var forged = new JobExecutionRepository.Lease(original.submission(), original.token(), original.startTime(), original.expiresAt(),
                original.attempt(), original.failures(), original.cycle(),
                Optional.of(new JobExecutionRepository.TraceFields(parent, "vendor=changed")));
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(forged)));
        assertFalse(unitOfWork.execute(() -> recovery.finish(forged, JobRecoveryRepository.Outcome.SUCCEEDED)));
        var missing = new JobExecutionRepository.Lease(original.submission(), original.token(), original.startTime(), original.expiresAt(),
                original.attempt(), original.failures(), original.cycle());
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(missing)));
        expire(input.executionId()); assertTrue(claim(input.executionId()).isEmpty());
        jdbc.update("UPDATE job_execution SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE execution_id=?", input.executionId().toString());
        var retry = claim(input.executionId()).orElseThrow();
        assertEquals(2, retry.attempt()); assertEquals(original.standardTrace(), retry.standardTrace());
        assertTrue(unitOfWork.execute(() -> leaseAuthority.lockCurrent(retry)));
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(original)));
        assertTrue(unitOfWork.execute(() -> recovery.finish(retry, JobRecoveryRepository.Outcome.SUCCEEDED)));
    }

    /** 历史空值保持空证据；不合法持久头保留为原始有界字段供执行边界校验。 */
    @Test
    void carriesHistoricalEmptyAndUntrustedStoredTrace() {
        var trace = new OpenTelemetryTraceContext(); var historical = input("{}", 3); var malformed = input("{}", 3);
        try (var ignored = trace.restore(Optional.empty())) { register(historical); register(malformed); }
        jdbc.update("UPDATE job_execution SET standard_traceparent=?,standard_tracestate=? WHERE execution_id=?",
                "invalid-parent", "untrusted-state", malformed.executionId().toString());
        assertTrue(claim(historical.executionId()).orElseThrow().standardTrace().isEmpty());
        var unavailable = mock(StandardTraceContext.class);
        var reader = new JobExecutionRepositoryImpl(jdbc.getDataSource(), JsonMapper.builder().build(), unavailable);
        var fields = unitOfWork.execute(() -> reader.claim(malformed.executionId())).orElseThrow().standardTrace().orElseThrow();
        verifyNoInteractions(unavailable);
        assertEquals("invalid-parent", fields.traceparent()); assertEquals("untrusted-state", fields.tracestate());
        assertFalse(fields.toString().contains("untrusted-state"));
    }

    /**
     * 追踪捕获、规范化或契约异常不阻断真实登记，只输出安全类型并保留空上下文。
     * @param output 测试捕获的日志
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void continuesRegistrationWhenTraceComponentFails(CapturedOutput output) {
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        for (int mode = 0; mode < 4; mode++) {
            var trace = mock(StandardTraceContext.class);
            if (mode == 0) when(trace.capture()).thenThrow(new IllegalStateException("sensitive-capture-value"));
            else if (mode == 1) {
                when(trace.capture()).thenReturn(Optional.of(new StandardTraceContext.Carrier(parent, "vendor=value")));
                when(trace.normalize(parent, "vendor=value")).thenThrow(new IllegalStateException("sensitive-normalize-value"));
            } else if (mode == 2) when(trace.capture()).thenReturn(null);
            else {
                when(trace.capture()).thenReturn(Optional.of(new StandardTraceContext.Carrier(parent, "vendor=value")));
                when(trace.normalize(parent, "vendor=value")).thenReturn(null);
            }
            var input = input("{}", 3);
            var failingRepository = new JobExecutionRepositoryImpl(jdbc.getDataSource(), JsonMapper.builder().build(), trace);
            assertEquals(input.executionId(), unitOfWork.execute(() -> failingRepository.register(input)));
            assertStandardTrace(input.executionId(), null, null);
            assertEquals(input.traceId(), jdbc.queryForObject("SELECT trace_id FROM job_execution WHERE execution_id=?",
                    String.class, input.executionId().toString()));
        }
        assertTrue(output.getOut().contains("任务标准追踪上下文捕获失败"));
        assertTrue(output.getOut().contains("IllegalStateException"));
        assertFalse(output.getOut().contains("sensitive-capture-value"));
        assertFalse(output.getOut().contains("sensitive-normalize-value"));
    }

    /**
     * 核验首次登记的初始及当前标准上下文。
     * @param id 执行UUID
     * @param parent 预期父头或空值
     * @param state 预期状态头或空值
     */
    private void assertStandardTrace(UUID id, String parent, String state) {
        var row = jdbc.queryForMap("""
                SELECT initial_standard_traceparent,initial_standard_tracestate,standard_traceparent,standard_tracestate
                FROM job_execution WHERE execution_id=?
                """, id.toString());
        assertEquals(parent, row.get("initial_standard_traceparent")); assertEquals(state, row.get("initial_standard_tracestate"));
        assertEquals(parent, row.get("standard_traceparent")); assertEquals(state, row.get("standard_tracestate"));
    }

    /** 同任务/键及同JSON值复用首次UUID，不替换首次trace或新增记录。 */
    @Test
    void reusesFirstIdentityAndTraceForEquivalentRegistration() {
        var first = input("{\"a\":1,\"b\":99999999999999.123456789}", 3);
        register(first);
        var repeated = copy(first, id(), first.idempotencyKey(), "{\"b\":99999999999999.123456789,\"a\":1}", "new-trace");
        assertEquals(first.executionId(), register(repeated));
        assertEquals(1, count());
        assertEquals(first, claim(first.executionId()).orElseThrow().submission());
    }

    /** 同键不同输入或同UUID不同键都冲突；主键和业务键同时碰撞时两条原记录保持。 */
    @Test
    void rejectsConflictsIncludingTwoExistingUniqueKeys() {
        var one = input("{\"value\":1}", 3);
        var two = input("{\"value\":2}", 3);
        register(one); register(two);
        conflict(() -> register(copy(one, id(), one.idempotencyKey(), "{\"value\":3}", "trace")));
        conflict(() -> register(copy(one, one.executionId(), "another-key", one.arguments(), "trace")));
        conflict(() -> register(copy(one, one.executionId(), two.idempotencyKey(), two.arguments(), "trace")));
        assertEquals(2, count());
        assertEquals(one, claim(one.executionId()).orElseThrow().submission());
        assertEquals(two, claim(two.executionId()).orElseThrow().submission());
    }

    /** 严格拒绝非法/重复键/尾随JSON及真正非法Cron，拒绝前没有可见登记。 */
    @Test
    void rejectsInvalidJsonAndCronBeforeInserting() {
        for (String arguments : List.of("{", "{\"a\":1,\"a\":2}", "{} {}")) {
            assertEquals(40000, assertThrows(StratumException.class, () -> register(input(arguments, 3))).getErrorCode().getCode());
        }
        var policy = new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5));
        var definition = new JobDefinition(code, JobDefinition.Type.CRON, Optional.of("invalid-cron"), Optional.empty(),
                30, policy, JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        var invalid = new JobExecutionRepository.Submission(id(), definition, TIME, "bad-cron", "{}", "trace");
        assertEquals(40000, assertThrows(StratumException.class, () -> register(invalid)).getErrorCode().getCode());
        assertEquals(0, count());
    }

    /** 没有写事务或带只读标志时登记、候选和领取全部拒绝，不自行开启事务。 */
    @Test
    void requiresExistingWritableApplicationUnitOfWork() {
        var input = input("{}", 3);
        assertThrows(IllegalStateException.class, () -> repository.register(input));
        assertThrows(IllegalStateException.class, () -> repository.due(code, 1));
        assertThrows(IllegalStateException.class, () -> repository.claim(input.executionId()));
        unitOfWork.execute(() -> {
            boolean previous = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try {
                assertThrows(IllegalStateException.class, () -> repository.register(input));
                assertThrows(IllegalStateException.class, () -> repository.due(code, 1));
                assertThrows(IllegalStateException.class, () -> repository.claim(input.executionId()));
            } finally {
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(previous);
            }
            return null;
        });
        assertEquals(0, count());
    }

    /** 仅到期任务成为有限不可变候选，未来任务和另一个代码保持独立。 */
    @Test
    void boundsDueCandidatesAndDoesNotClaimFutureRuns() {
        var due = input("{}", 3);
        register(due);
        var futureDefinition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(),
                Optional.of(Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS)), 30,
                due.definition().retryPolicy(), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        var future = new JobExecutionRepository.Submission(id(), futureDefinition, futureDefinition.triggerAt().orElseThrow(),
                "future-key", "{}", "trace");
        register(future);
        var candidates = unitOfWork.execute(() -> repository.due(code, 1));
        assertEquals(List.of(due.executionId()), candidates);
        assertThrows(UnsupportedOperationException.class, () -> candidates.add(future.executionId()));
        assertTrue(claim(future.executionId()).isEmpty());
        assertTrue(unitOfWork.execute(() -> repository.due("another.code", 128)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> unitOfWork.execute(() -> repository.due(code, 129)));
        assertThrows(IllegalArgumentException.class, () -> unitOfWork.execute(() -> repository.due("unsafe code", 1)));
    }

    /** 领取同事务创建尝试，租期由数据库开始时刻加执行预算及30秒余量生成。 */
    @Test
    void claimsOnceWithDatabaseTimeAndPersistentAttempt() {
        var input = input("{}", 3);
        register(input);
        var lease = claim(input.executionId()).orElseThrow();
        assertEquals(Duration.ofSeconds(60), Duration.between(lease.startTime(), lease.expiresAt()));
        assertEquals(1, lease.attempt());
        assertEquals(0, lease.failures());
        assertEquals(0, lease.cycle());
        assertTrue(claim(input.executionId()).isEmpty());
        assertEquals(lease.token().toString(), jdbc.queryForObject("SELECT lease_token FROM job_attempt WHERE execution_id=?",
                String.class, input.executionId().toString()));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, input.executionId().toString()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT start_time>UTC_TIMESTAMP(6) OR start_time<UTC_TIMESTAMP(6)-INTERVAL 5 SECOND
                FROM job_execution WHERE execution_id=?
                """, Integer.class, input.executionId().toString()));
    }

    /** 登记或领取后的上层失败回滚执行状态和尝试，不残留占用。 */
    @Test
    void rollsBackRegistrationAndClaimTogether() {
        var input = input("{}", 3);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.register(input);
            repository.claim(input.executionId()).orElseThrow();
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(0, count());
        register(input);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.claim(input.executionId()).orElseThrow();
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, input.executionId().toString()));
        assertEquals(1, claim(input.executionId()).orElseThrow().attempt());
    }

    /** 过期尝试先留痕并退避，到期后新领取保留输入、增加序号并更换令牌。 */
    @Test
    void recordsExpiredLeaseAndDefersNextAttempt() {
        var input = input("{\"value\":1}", 3);
        register(input);
        var original = claim(input.executionId()).orElseThrow();
        expire(input.executionId());
        assertTrue(claim(input.executionId()).isEmpty());
        assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, input.executionId().toString()));
        assertEquals("RETRY_WAIT", state(input.executionId()));
        assertTrue(unitOfWork.execute(() -> repository.due(code, 1)).isEmpty());
        jdbc.update("UPDATE job_execution SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE execution_id=?", input.executionId().toString());
        var next = claim(input.executionId()).orElseThrow();
        assertEquals(2, next.attempt());
        assertEquals(1, next.failures());
        assertNotEquals(original.token(), next.token());
        assertEquals(input, next.submission());
    }

    /** 周期次数耗尽时过期尝试和死亡关联同事务完成，终态不再被候选或领取。 */
    @Test
    void archivesExhaustedExpiredAttempt() {
        var input = input("{}", 1);
        register(input);
        claim(input.executionId()).orElseThrow();
        expire(input.executionId());
        assertTrue(claim(input.executionId()).isEmpty());
        assertEquals("DEAD", state(input.executionId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE execution_id=? AND reason='LEASE_EXPIRED' AND cycle_no=0",
                Integer.class, input.executionId().toString()));
        assertTrue(claim(input.executionId()).isEmpty());
        assertTrue(unitOfWork.execute(() -> repository.due(code, 1)).isEmpty());
    }

    /** 过期归档后的上层失败回滚尝试和死亡记录，下一完整工作单元仍可正确归档。 */
    @Test
    void rollsBackExpiredAttemptAndDeathAtomically() {
        var input = input("{}", 1);
        register(input);
        var original = claim(input.executionId()).orElseThrow();
        expire(input.executionId());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertTrue(repository.claim(input.executionId()).isEmpty());
            assertEquals("DEAD", state(input.executionId()));
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals("RUNNING", state(input.executionId()));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, input.executionId().toString()));
        assertEquals(original.token().toString(), jdbc.queryForObject("SELECT lease_token FROM job_execution WHERE execution_id=?", String.class, input.executionId().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE execution_id=?", Integer.class, input.executionId().toString()));
        assertTrue(claim(input.executionId()).isEmpty());
        assertEquals("DEAD", state(input.executionId()));
    }

    /** 两个独立连接竞争同一行，第二事务跳过持锁记录；首事务提交后不可重复领取。 */
    @Test
    void skipsBusyLeaseAcrossIndependentTransactions() throws Exception {
        var input = input("{}", 3);
        register(input);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> unitOfWork.execute(() -> {
                var lease = repository.claim(input.executionId()).orElseThrow();
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试持锁超时");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("测试中断", failure);
                }
                return lease;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(2), () -> assertTrue(claim(input.executionId()).isEmpty()));
            } finally {
                release.countDown();
            }
            assertNotNull(first.get(5, TimeUnit.SECONDS));
            assertTrue(claim(input.executionId()).isEmpty());
        }
    }

    /** 取消仅影响匹配代码的未开始任务，重复取消保持版本，取消终态不能领取或成为候选。 */
    @Test
    void cancelsOnlyMatchingUnstartedTaskAndIsIdempotent() {
        var input = input("{}", 3); register(input);
        assertThrows(IllegalStateException.class, () -> cancellation.cancelUnstarted(input.executionId(), code));
        assertThrows(IllegalArgumentException.class, () -> cancel(input.executionId(), "bad code"));
        assertEquals(JobCancellationRepository.Result.NOT_FOUND, cancel(UUID.randomUUID(), code));
        assertEquals(JobCancellationRepository.Result.NOT_FOUND, cancel(input.executionId(), "other.code"));
        assertEquals("WAITING", state(input.executionId()));
        assertEquals(JobCancellationRepository.Result.CANCELLED, cancel(input.executionId(), code));
        long version = jdbc.queryForObject("SELECT version FROM job_execution WHERE execution_id=?", Long.class, input.executionId().toString());
        assertNotNull(jdbc.queryForObject("SELECT finished_at FROM job_execution WHERE execution_id=?", java.time.LocalDateTime.class, input.executionId().toString()));
        assertEquals(JobCancellationRepository.Result.CANCELLED, cancel(input.executionId(), code));
        assertEquals(version, jdbc.queryForObject("SELECT version FROM job_execution WHERE execution_id=?", Long.class, input.executionId().toString()));
        assertTrue(claim(input.executionId()).isEmpty());
        assertTrue(unitOfWork.execute(() -> repository.due(code, 128)).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, input.executionId().toString()));
    }

    /** 取消事务回滚不改变资格；领取后和已发生重试的任务不能取消。 */
    @Test
    void rollsBackCancellationAndRejectsAlreadyStartedTask() {
        var input = input("{}", 3); register(input);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertEquals(JobCancellationRepository.Result.CANCELLED, cancellation.cancelUnstarted(input.executionId(), code));
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals("WAITING", state(input.executionId())); claim(input.executionId()).orElseThrow();
        assertEquals(JobCancellationRepository.Result.ALREADY_STARTED, cancel(input.executionId(), code));
        expire(input.executionId()); assertTrue(claim(input.executionId()).isEmpty());
        assertEquals("RETRY_WAIT", state(input.executionId()));
        assertEquals(JobCancellationRepository.Result.ALREADY_STARTED, cancel(input.executionId(), code));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE job_execution SET state='CANCELLED',finished_at=UTC_TIMESTAMP(6) WHERE execution_id=?", input.executionId().toString()));
    }

    /**
     * 取消事务先持行锁时领取跳过，提交后任务保持取消且没有执行尝试。
     * @throws Exception 并发任务失败或等待超时
     */
    @Test
    void cancellationWinsAgainstConcurrentClaim() throws Exception {
        var input = input("{}", 3); register(input);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = worker.submit(() -> unitOfWork.execute(() -> {
                var result = cancellation.cancelUnstarted(input.executionId(), code); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试等待中断", failure); }
                return result;
            }));
            try { assertTrue(held.await(5, TimeUnit.SECONDS)); assertTrue(claim(input.executionId()).isEmpty()); }
            finally { release.countDown(); }
            assertEquals(JobCancellationRepository.Result.CANCELLED, winner.get(5, TimeUnit.SECONDS));
        }
        assertTrue(claim(input.executionId()).isEmpty()); assertEquals("CANCELLED", state(input.executionId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, input.executionId().toString()));
    }

    /**
     * 领取事务先持行锁时取消等待提交，并在读取已开始状态后拒绝。
     * @throws Exception 并发任务失败或等待超时
     */
    @Test
    void committedClaimWinsAgainstConcurrentCancellation() throws Exception {
        var input = input("{}", 3); register(input);
        var held = new CountDownLatch(1); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = workers.submit(() -> unitOfWork.execute(() -> {
                var lease = repository.claim(input.executionId()).orElseThrow(); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试等待中断", failure); }
                return lease;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var contender = workers.submit(() -> { started.countDown(); return cancel(input.executionId(), code); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> contender.get(300, TimeUnit.MILLISECONDS));
                release.countDown(); winner.get(5, TimeUnit.SECONDS);
                assertEquals(JobCancellationRepository.Result.ALREADY_STARTED, contender.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertEquals("RUNNING", state(input.executionId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, input.executionId().toString()));
    }

    /**
     * 在独立工作单元尝试取消，等待结果提交。
     * @param id 本测试执行身份
     * @param jobCode 限定的任务代码
     * @return 取消结果
     */
    private JobCancellationRepository.Result cancel(UUID id, String jobCode) {
        return unitOfWork.execute(() -> cancellation.cancelUnstarted(id, jobCode));
    }

    /** 同请求、归档和操作者始终返回首次任务；变更操作者或归档不能复用请求身份。 */
    @Test
    void bindsReplayRequestToFirstTaskArchiveAndActor() {
        UUID request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var first = replayRequest(request, archive, actor, TIME); var next = replayRequest(request, archive, actor, TIME.plusSeconds(1));
        var later = next.submission();
        var duplicate = new DeadLetterReplayTaskRepository.Request(request, archive, actor, new JobExecutionRepository.Submission(
                later.executionId(), later.definition(), later.scheduleTime(), later.idempotencyKey(), later.arguments(), "later-trace"));
        assertThrows(IllegalStateException.class, () -> replayTasks.register(first));
        UUID task = unitOfWork.execute(() -> replayTasks.register(first));
        assertEquals(task, unitOfWork.execute(() -> replayTasks.register(duplicate)));
        var changedArchive = replayRequest(request, UUID.randomUUID(), actor, TIME);
        var changedActor = replayRequest(request, archive, UUID.randomUUID(), TIME);
        conflict(() -> unitOfWork.execute(() -> replayTasks.register(changedArchive)));
        conflict(() -> unitOfWork.execute(() -> replayTasks.register(changedActor)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_replay_task WHERE request_id=?", Integer.class, request.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE job_code=? AND idempotency_key=?", Integer.class,
                DeadLetterReplayJob.CODE, request.toString()));
        assertEquals(first.submission().traceId(), jdbc.queryForObject("SELECT initial_trace_id FROM job_execution WHERE execution_id=?", String.class, task.toString()));
        assertEquals(JobCancellationRepository.Result.CANCELLED, cancel(task, DeadLetterReplayJob.CODE));
        assertEquals(task, unitOfWork.execute(() -> replayTasks.register(duplicate)));
        assertEquals("CANCELLED", state(task));
    }

    /** 请求绑定及任务一起回滚，固定代码、幂等键和归档参数必须一致。 */
    @Test
    void rollsBackReplayTaskAndRejectsMismatchedSubmission() {
        UUID request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var first = replayRequest(request, archive, actor, TIME);
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskRepository.Request(request, UUID.randomUUID(), actor, first.submission()));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskRepository.Request(UUID.randomUUID(), archive, actor, first.submission()));
        var unrelated = input("{}", 3);
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayTaskRepository.Request(request, archive, actor, unrelated));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            replayTasks.register(first); throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_replay_task WHERE request_id=?", Integer.class, request.toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE execution_id=?", Integer.class, first.submission().executionId().toString()));
        var retry = replayRequest(request, archive, actor, TIME.plusSeconds(1));
        assertEquals(retry.submission().executionId(), unitOfWork.execute(() -> replayTasks.register(retry)));
    }

    /** 绑定只在写工作单元中读取，返回首次请求、归档、操作者及任务的完整身份。 */
    @Test
    void readsReplayBindingOnlyInWritableUnitOfWork() {
        UUID request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var input = replayRequest(request, archive, actor, TIME);
        UUID task = unitOfWork.execute(() -> replayTasks.register(input));
        assertThrows(IllegalStateException.class, () -> replayTasks.lockBinding(task));
        assertThrows(NullPointerException.class, () -> unitOfWork.execute(() -> replayTasks.lockBinding(null)));
        assertTrue(unitOfWork.execute(() -> replayTasks.lockBinding(UUID.randomUUID())).isEmpty());
        var expected = new DeadLetterReplayTaskRepository.Binding(request, archive, actor, task);
        assertEquals(expected, unitOfWork.execute(() -> replayTasks.lockBinding(task)).orElseThrow());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertEquals(expected, replayTasks.lockBinding(task).orElseThrow()); throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(expected, unitOfWork.execute(() -> replayTasks.lockBinding(task)).orElseThrow());
    }

    /**
     * 执行事务持有绑定锁时，同请求登记等待其释放再返回首次身份。
     * @throws Exception 并发任务失败或等待超时
     */
    @Test
    void locksReplayBindingAgainstConcurrentRegistration() throws Exception {
        UUID request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var first = replayRequest(request, archive, actor, TIME); var duplicate = replayRequest(request, archive, actor, TIME.plusSeconds(1));
        UUID task = unitOfWork.execute(() -> replayTasks.register(first));
        var held = new CountDownLatch(1); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = workers.submit(() -> unitOfWork.execute(() -> {
                var binding = replayTasks.lockBinding(task).orElseThrow(); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试等待中断", failure); }
                return binding;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var contender = workers.submit(() -> { started.countDown(); return unitOfWork.execute(() -> replayTasks.register(duplicate)); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> contender.get(300, TimeUnit.MILLISECONDS));
                release.countDown(); assertEquals(task, owner.get(5, TimeUnit.SECONDS).taskId());
                assertEquals(task, contender.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    /** 任务UUID已被其他请求占用时，新绑定完整回滚且不影响原任务。 */
    @Test
    void rejectsTaskIdentityCollisionWithoutLeavingBinding() {
        UUID archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var first = replayRequest(UUID.randomUUID(), archive, actor, TIME);
        UUID task = unitOfWork.execute(() -> replayTasks.register(first));
        UUID otherRequest = UUID.randomUUID(); var other = replayRequest(otherRequest, archive, actor, TIME);
        var collision = new JobExecutionRepository.Submission(task, other.submission().definition(), other.submission().scheduleTime(),
                otherRequest.toString(), other.submission().arguments(), other.submission().traceId());
        var request = new DeadLetterReplayTaskRepository.Request(otherRequest, archive, actor, collision);
        conflict(() -> unitOfWork.execute(() -> replayTasks.register(request)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_replay_task WHERE request_id=?", Integer.class, otherRequest.toString()));
        assertEquals("WAITING", state(task));
    }

    /**
     * 同一请求的两个事务串行固定首次绑定，即使第二次生成了不同任务身份和计划时间。
     * @throws Exception 并发任务失败或等待超时
     */
    @Test
    void serializesConcurrentReplayTaskRegistrations() throws Exception {
        UUID request = UUID.randomUUID(), archive = UUID.randomUUID(), actor = UUID.randomUUID();
        var first = replayRequest(request, archive, actor, TIME); var second = replayRequest(request, archive, actor, TIME.plusSeconds(1));
        var held = new CountDownLatch(1); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = workers.submit(() -> unitOfWork.execute(() -> {
                UUID result = replayTasks.register(first); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试等待中断", failure); }
                return result;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var contender = workers.submit(() -> { started.countDown(); return unitOfWork.execute(() -> replayTasks.register(second)); });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> contender.get(300, TimeUnit.MILLISECONDS));
                release.countDown(); UUID expected = owner.get(5, TimeUnit.SECONDS);
                assertEquals(expected, contender.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE job_code=? AND idempotency_key=?", Integer.class,
                DeadLetterReplayJob.CODE, request.toString()));
    }

    /**
     * 构造并登记测试清理身份，生成固定归档参数和请求幂等键。
     * @param request 请求UUID
     * @param archive 归档UUID
     * @param actor 操作者UUID
     * @param time 单次执行时刻
     * @return 任务绑定请求
     */
    private DeadLetterReplayTaskRepository.Request replayRequest(UUID request, UUID archive, UUID actor, Instant time) {
        replayRequests.add(request); var policy = new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5));
        var definition = new JobDefinition(DeadLetterReplayJob.CODE, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                policy, JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        var submission = new JobExecutionRepository.Submission(id(), definition, time, request.toString(), DeadLetterReplayJob.arguments(archive), "task-trace");
        return new DeadLetterReplayTaskRepository.Request(request, archive, actor, submission);
    }

    /** 持久领取资格校验完整原快照和令牌；过期及接管后旧证据失效。 */
    @Test
    void verifiesCurrentLeaseAndFencesExpiredOrReplacedEvidence() {
        var input = input("{}", 3); register(input); var lease = claim(input.executionId()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> leaseAuthority.lockCurrent(lease));
        assertThrows(NullPointerException.class, () -> unitOfWork.execute(() -> leaseAuthority.lockCurrent(null)));
        assertTrue(unitOfWork.execute(() -> leaseAuthority.lockCurrent(lease)));
        var forgedToken = new JobExecutionRepository.Lease(lease.submission(), UUID.randomUUID(), lease.startTime(), lease.expiresAt(),
                lease.attempt(), lease.failures(), lease.cycle());
        var altered = copy(input, input.executionId(), input.idempotencyKey(), input.arguments(), "altered-trace");
        var forgedInput = new JobExecutionRepository.Lease(altered, lease.token(), lease.startTime(), lease.expiresAt(),
                lease.attempt(), lease.failures(), lease.cycle());
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(forgedToken)));
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(forgedInput)));
        expire(input.executionId());
        var times = jdbc.queryForObject("SELECT start_time,lease_until FROM job_execution WHERE execution_id=?",
                (rs, index) -> List.of(rs.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC),
                        rs.getObject(2, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)), input.executionId().toString());
        Instant start = times.getFirst(); Instant until = times.getLast();
        var expired = new JobExecutionRepository.Lease(lease.submission(), lease.token(), start, until,
                lease.attempt(), lease.failures(), lease.cycle());
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(expired)));
        assertTrue(claim(input.executionId()).isEmpty());
        jdbc.update("UPDATE job_execution SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE execution_id=?", input.executionId().toString());
        var replacement = claim(input.executionId()).orElseThrow();
        assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(expired)));
        assertTrue(unitOfWork.execute(() -> leaseAuthority.lockCurrent(replacement)));
    }

    /**
     * 当前领取的行锁与竞争校验互斥，锁忙时失败关闭，释放或回滚后可再次核验。
     * @throws Exception 并发执行失败或等待超时
     */
    @Test
    void keepsLeaseLockUntilTransactionEnds() throws Exception {
        var input = input("{}", 3); register(input); var lease = claim(input.executionId()).orElseThrow();
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = workers.submit(() -> unitOfWork.execute(() -> {
                assertTrue(leaseAuthority.lockCurrent(lease)); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试等待中断", failure); }
                return true;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertFalse(unitOfWork.execute(() -> leaseAuthority.lockCurrent(lease)));
            } finally { release.countDown(); }
            assertTrue(owner.get(5, TimeUnit.SECONDS));
        }
        assertTrue(unitOfWork.execute(() -> leaseAuthority.lockCurrent(lease)));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertTrue(leaseAuthority.lockCurrent(lease)); throw new IllegalStateException("rollback-probe");
        }));
        assertTrue(unitOfWork.execute(() -> leaseAuthority.lockCurrent(lease)));
    }

    /** 极大累计序号和版本不发生溢出，也不留下半条尝试。 */
    @Test
    void rejectsExhaustedCountersWithoutPartialWrites() {
        for (String counter : List.of("attempt_no", "version")) {
            var input = input("{}", 3);
            register(input);
            jdbc.update("UPDATE job_execution SET " + counter + "=? WHERE execution_id=?", Long.MAX_VALUE, input.executionId().toString());
            if (counter.equals("version")) conflict(() -> cancel(input.executionId(), code));
            conflict(() -> claim(input.executionId()));
            assertEquals("WAITING", state(input.executionId()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, input.executionId().toString()));
        }
    }

    /**
     * 使用SQL定位自己租约的过期位置，并同步技术尝试开始时刻；不宣称真实等待预算经过。
     * @param id 自身执行UUID
     */
    private void expire(UUID id) {
        jdbc.update("""
                UPDATE job_execution e JOIN job_attempt a ON a.execution_id=e.execution_id AND a.attempt_no=e.attempt_no
                SET e.start_time=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND,e.lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,
                a.start_time=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND WHERE e.execution_id=?
                """, id.toString());
    }

    /**
     * 在实际应用工作单元中登记。
     * @param input 完整输入
     * @return 首次执行身份
     */
    private UUID register(JobExecutionRepository.Submission input) { return unitOfWork.execute(() -> repository.register(input)); }

    /**
     * 在独立实际工作单元中竞争领取。
     * @param id 执行身份
     * @return 可空领取凭据
     */
    private Optional<JobExecutionRepository.Lease> claim(UUID id) { return unitOfWork.execute(() -> repository.claim(id)); }

    /**
     * 读取自身执行状态，仅用于独立断言。
     * @param id 执行身份
     * @return 当前状态
     */
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, id.toString()); }

    /**
     * 统计自身任务代码的记录数量。
     * @return 本用例登记数量
     */
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE job_code=?", Integer.class, code); }

    /**
     * 验证失败为固定可重试状态冲突。
     * @param operation 预期失败的完整工作单元
     */
    private void conflict(Runnable operation) { assertEquals(40900, assertThrows(StratumException.class, operation::run).getErrorCode().getCode()); }

    /**
     * 构造可立即领取、五秒退避的一次性任务。
     * @param arguments JSON原文
     * @param attempts 周期最大次数
     * @return 随机幂等身份输入
     */
    private JobExecutionRepository.Submission input(String arguments, int attempts) {
        var policy = new JobDefinition.RetryPolicy(attempts, Duration.ofSeconds(5), Duration.ofSeconds(10));
        var definition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(TIME),
                30, policy, JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return new JobExecutionRepository.Submission(id(), definition, TIME, UUID.randomUUID().toString(), arguments, "job-trace");
    }

    /**
     * 复制定义并显式替换用于冲突/幂等断言的字段。
     * @param original 原输入
     * @param id 新执行身份
     * @param key 新幂等键
     * @param arguments 新参数
     * @param trace 新trace
     * @return 不可变输入
     */
    private JobExecutionRepository.Submission copy(JobExecutionRepository.Submission original, UUID id,
            String key, String arguments, String trace) {
        return new JobExecutionRepository.Submission(id, original.definition(), original.scheduleTime(), key, arguments, trace);
    }

    /**
     * 登记本用例负责清理的UUID。
     * @return 新执行UUID
     */
    private UUID id() { var id = UUID.randomUUID(); executions.add(id); return id; }
}
