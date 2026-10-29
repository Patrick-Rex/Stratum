package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobRecoveryRepository;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import com.patrick.stratum.domain.job.JobRecoveryRepository.ReplayRequest;
import com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl;
import com.patrick.stratum.infrastructure.job.JobRecoveryRepositoryImpl;
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

/** 真实数据库验证完成令牌、失败退避与人工恢复；结果由测试端口输入，不伪称实际执行器已交付。 */
class JobRecoveryRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");
    private final UnitOfWork unitOfWork;
    private final JobExecutionRepository executions;
    private final JobRecoveryRepository recovery;
    private final JdbcTemplate jdbc;
    private final List<UUID> ids = new ArrayList<>();
    private final String code = "recovery." + UUID.randomUUID();

    /**
     * 注入生产技术端口与真实应用工作单元。
     * @param unitOfWork 应用事务
     * @param executions 登记领取端口
     * @param recovery 完成恢复端口
     * @param jdbc 独立断言及自身夹具清理
     */
    @Autowired
    JobRecoveryRepositoryIntegrationTest(UnitOfWork unitOfWork, JobExecutionRepository executions,
            JobRecoveryRepository recovery, JdbcTemplate jdbc) {
        this.unitOfWork = unitOfWork; this.executions = executions; this.recovery = recovery; this.jdbc = jdbc;
    }

    /** 保留其他任务，仅按自身UUID逆外键顺序清理技术历史。 */
    @AfterEach
    void cleanup() {
        for (UUID id : ids) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id.toString());
        }
    }

    /** 成功完成保存结束/耗时、清理租约，重复完成不改变历史。 */
    @Test
    void completesSuccessfulAttemptExactlyOnce() {
        var lease = start(3);
        assertTrue(finish(lease, Outcome.SUCCEEDED));
        assertFalse(finish(lease, Outcome.SUCCEEDED));
        assertEquals("SUCCEEDED", state(lease));
        var row = jdbc.queryForMap("SELECT * FROM job_execution WHERE execution_id=?", id(lease));
        assertNull(row.get("lease_token")); assertNull(row.get("start_time")); assertNull(row.get("lease_until"));
        assertNotNull(row.get("finished_at"));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, id(lease)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=? AND end_time>=start_time AND duration_ms>=0", Integer.class, id(lease)));
        assertTrue(unitOfWork.execute(() -> executions.due(code, 128)).isEmpty());
    }

    /** 三种实际输入分类分别留痕，退避按5秒/10秒封顶，周期耗尽关联死亡记录。 */
    @Test
    void persistsRetryBackoffAndExhaustedFailure() {
        var lease = start(3);
        for (var outcome : List.of(Outcome.FAILED, Outcome.TIMED_OUT, Outcome.INTERRUPTED)) {
            assertTrue(finish(lease, outcome));
            if (outcome != Outcome.INTERRUPTED) {
                assertEquals("RETRY_WAIT", state(lease));
                assertEquals(outcome == Outcome.FAILED ? 5_000_000L : 10_000_000L,
                        jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,next_attempt_at) FROM job_execution WHERE execution_id=?", Long.class, id(lease)));
                ready(lease);
                lease = claim(lease.submission().executionId());
            }
        }
        assertEquals("DEAD", state(lease));
        assertEquals(List.of("FAILED", "TIMED_OUT", "INTERRUPTED"), jdbc.queryForList(
                "SELECT result FROM job_attempt WHERE execution_id=? ORDER BY attempt_no", String.class, id(lease)));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT reason FROM dead_job WHERE execution_id=?", String.class, id(lease)));
        assertFalse(finish(lease, Outcome.SUCCEEDED));
    }

    /** 完整身份、参数、周期、次数、令牌、开始或截止时间有任何伪造均不能覆盖真实执行。 */
    @Test
    void rejectsForgedLeaseEvidence() {
        var lease = start(3);
        var original = lease.submission();
        var tampered = new JobExecutionRepository.Submission(original.executionId(), original.definition(), original.scheduleTime(),
                original.idempotencyKey(), "{\"tampered\":true}", original.traceId());
        var forgeries = List.of(
                new Lease(original, UUID.randomUUID(), lease.startTime(), lease.expiresAt(), 1, 0, 0),
                new Lease(tampered, lease.token(), lease.startTime(), lease.expiresAt(), 1, 0, 0),
                new Lease(original, lease.token(), lease.startTime(), lease.expiresAt().plusSeconds(1), 1, 0, 0),
                new Lease(original, lease.token(), lease.startTime().minusNanos(1000), lease.expiresAt(), 1, 0, 0),
                new Lease(original, lease.token(), lease.startTime(), lease.expiresAt(), 2, 0, 0),
                new Lease(original, lease.token(), lease.startTime(), lease.expiresAt(), 1, 1, 0),
                new Lease(original, lease.token(), lease.startTime(), lease.expiresAt(), 1, 0, 1));
        for (var forged : forgeries) assertFalse(finish(forged, Outcome.SUCCEEDED));
        assertEquals("RUNNING", state(lease));
        assertTrue(finish(lease, Outcome.SUCCEEDED));
    }

    /** 完全匹配数据库旧证据也不能越过真实截止时间，接管后的旧令牌继续失效。 */
    @Test
    void rejectsExpiredAndSupersededCompletionUsingDatabaseTime() {
        var lease = start(3);
        jdbc.update("""
                UPDATE job_execution e JOIN job_attempt a ON a.execution_id=e.execution_id AND a.attempt_no=e.attempt_no
                SET e.start_time=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND,e.lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,
                a.start_time=UTC_TIMESTAMP(6)-INTERVAL 61 SECOND WHERE e.execution_id=?
                """, id(lease));
        Instant start = jdbc.queryForObject("SELECT start_time FROM job_execution WHERE execution_id=?",
                LocalDateTime.class, id(lease)).toInstant(ZoneOffset.UTC);
        Instant end = jdbc.queryForObject("SELECT lease_until FROM job_execution WHERE execution_id=?",
                LocalDateTime.class, id(lease)).toInstant(ZoneOffset.UTC);
        var expired = new Lease(lease.submission(), lease.token(), start, end, 1, 0, 0);
        assertFalse(finish(expired, Outcome.SUCCEEDED));
        assertTrue(unitOfWork.execute(() -> executions.claim(lease.submission().executionId())).isEmpty());
        ready(lease);
        var next = claim(lease.submission().executionId());
        assertFalse(finish(expired, Outcome.SUCCEEDED));
        assertTrue(finish(next, Outcome.SUCCEEDED));
    }

    /** 完成后的外层失败回滚结果和死亡记录，原领取仍可由新完整事务完成。 */
    @Test
    void rollsBackCompletionAndDeathTogether() {
        var lease = start(1);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            assertTrue(recovery.finish(lease, Outcome.FAILED));
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals("RUNNING", state(lease));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE execution_id=?", Integer.class, id(lease)));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, id(lease)));
        assertTrue(finish(lease, Outcome.FAILED));
    }

    /** 人工恢复保留原输入/UUID与首次trace，重置周期失败并累加尝试；成功关联历史死亡。 */
    @Test
    void replaysCurrentDeathAndResolvesOnlyAfterSuccess() {
        var original = start(1);
        assertTrue(finish(original, Outcome.FAILED));
        var request = request(death(original));
        assertEquals(original.submission().executionId(), replay(request));
        assertEquals("WAITING", state(original));
        assertEquals("REPLAY_REQUESTED", jdbc.queryForObject("SELECT state FROM dead_job WHERE dead_id=?", String.class, request.deadId().toString()));
        var next = claim(original.submission().executionId());
        assertEquals(2, next.attempt()); assertEquals(1, next.cycle()); assertEquals(0, next.failures());
        assertEquals(original.submission().arguments(), next.submission().arguments());
        assertEquals(request.traceId(), next.submission().traceId());
        assertEquals(original.submission().traceId(), jdbc.queryForObject("SELECT initial_trace_id FROM job_execution WHERE execution_id=?", String.class, id(original)));
        assertFalse(finish(original, Outcome.SUCCEEDED));
        assertTrue(finish(next, Outcome.SUCCEEDED));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT state FROM dead_job WHERE dead_id=?", String.class, request.deadId().toString()));
        assertEquals(original.submission().executionId(), replay(request));
        assertEquals("SUCCEEDED", state(original));
    }

    /** 人工恢复保留首次上下文，首次恢复请求设置当前上下文且重复请求不能覆盖。 */
    @Test
    void preservesInitialAndUsesFirstManualRecoveryContext() {
        var trace = new OpenTelemetryTraceContext(); Lease original;
        String initial = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        String manual = "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01";
        try (var ignored = trace.restore(trace.normalize(initial, "vendor=initial"))) { original = start(1); }
        assertTrue(finish(original, Outcome.FAILED)); var request = request(death(original));
        try (var ignored = trace.restore(trace.normalize(manual, "vendor=manual"))) {
            assertEquals(original.submission().executionId(), replay(request));
        }
        try (var ignored = trace.restore(Optional.empty())) {
            assertEquals(original.submission().executionId(), replay(request));
        }
        var row = jdbc.queryForMap("""
                SELECT initial_standard_traceparent,initial_standard_tracestate,standard_traceparent,standard_tracestate
                FROM job_execution WHERE execution_id=?
                """, id(original));
        assertEquals(initial, row.get("initial_standard_traceparent")); assertEquals("vendor=initial", row.get("initial_standard_tracestate"));
        assertEquals(manual, row.get("standard_traceparent")); assertEquals("vendor=manual", row.get("standard_tracestate"));
        var recovered = claim(original.submission().executionId());
        assertEquals(new JobExecutionRepository.TraceFields(manual, "vendor=manual"), recovered.standardTrace().orElseThrow());
        assertEquals(1, recovered.cycle()); assertFalse(finish(original, Outcome.SUCCEEDED));
        assertTrue(finish(recovered, Outcome.SUCCEEDED));
    }

    /** 无恢复上下文使用空值，恢复事务回滚时不能提前修改当前上下文或留下请求。 */
    @Test
    void rollsBackRecoveryContextAndAllowsEmptyManualContext() {
        var trace = new OpenTelemetryTraceContext(); Lease original;
        String initial = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        try (var ignored = trace.restore(trace.normalize(initial, "vendor=initial"))) { original = start(1); }
        assertTrue(finish(original, Outcome.FAILED)); var request = request(death(original));
        try (var ignored = trace.restore(trace.normalize("00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01", "vendor=rolledback"))) {
            assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
                recovery.replay(request); throw new IllegalStateException("rollback-probe");
            }));
        }
        assertEquals(initial, jdbc.queryForObject("SELECT standard_traceparent FROM job_execution WHERE execution_id=?", String.class, id(original)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_replay_request WHERE request_id=?", Integer.class, request.requestId().toString()));
        try (var ignored = trace.restore(Optional.empty())) { assertEquals(original.submission().executionId(), replay(request)); }
        var row = jdbc.queryForMap("SELECT initial_standard_traceparent,standard_traceparent,standard_tracestate FROM job_execution WHERE execution_id=?", id(original));
        assertEquals(initial, row.get("initial_standard_traceparent")); assertNull(row.get("standard_traceparent")); assertNull(row.get("standard_tracestate"));
        assertTrue(claim(original.submission().executionId()).standardTrace().isEmpty());
    }

    /**
     * 人工恢复登记时追踪组件故障不阻断新周期，只记录安全类型并清空当前上下文。
     * @param output 捕获的测试日志
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void continuesManualRecoveryWhenTraceCaptureFails(CapturedOutput output) {
        var trace = new OpenTelemetryTraceContext(); Lease original;
        String initial = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        try (var ignored = trace.restore(trace.normalize(initial, "vendor=initial"))) { original = start(1); }
        assertTrue(finish(original, Outcome.FAILED)); var request = request(death(original));
        var unavailable = mock(StandardTraceContext.class);
        when(unavailable.capture()).thenThrow(new IllegalStateException("private-recovery-failure"));
        var executionStore = new JobExecutionRepositoryImpl(jdbc.getDataSource(), JsonMapper.builder().build(), unavailable);
        var recoveryStore = new JobRecoveryRepositoryImpl(jdbc.getDataSource(), executionStore);
        assertEquals(original.submission().executionId(), unitOfWork.execute(() -> recoveryStore.replay(request)));
        var row = jdbc.queryForMap("SELECT initial_standard_traceparent,standard_traceparent,standard_tracestate FROM job_execution WHERE execution_id=?", id(original));
        assertEquals(initial, row.get("initial_standard_traceparent")); assertNull(row.get("standard_traceparent")); assertNull(row.get("standard_tracestate"));
        assertTrue(claim(original.submission().executionId()).standardTrace().isEmpty());
        assertTrue(output.getOut().contains("任务标准追踪上下文捕获失败"));
        assertTrue(output.getOut().contains("IllegalStateException")); assertFalse(output.getOut().contains("private-recovery-failure"));
    }

    /** 同请求历史回执稳定；新请求不能重新恢复旧死亡周期，后续周期死亡需指定新目标。 */
    @Test
    void preservesEveryFailedCycleAndStableReplayReceipt() {
        var first = start(1);
        finish(first, Outcome.FAILED);
        var one = request(death(first)); replay(one);
        conflict(() -> replay(request(one.deadId())));
        var second = claim(first.submission().executionId()); finish(second, Outcome.TIMED_OUT);
        conflict(() -> replay(request(one.deadId())));
        var two = request(death(second)); replay(two);
        var third = claim(first.submission().executionId());
        assertEquals(3, third.attempt()); assertEquals(2, third.cycle());
        assertTrue(finish(third, Outcome.SUCCEEDED));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE execution_id=? AND state='RESOLVED'", Integer.class, id(first)));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, id(first)));
        assertEquals(first.submission().executionId(), replay(one));
        assertEquals(first.submission().executionId(), replay(two));
    }

    /** 同请求UUID不可更换死亡目标，未知目标404，原执行状态不受影响。 */
    @Test
    void rejectsChangedOrUnknownReplayTarget() {
        var first = start(1); finish(first, Outcome.FAILED);
        var second = start(1); finish(second, Outcome.FAILED);
        var request = request(death(first)); replay(request);
        conflict(() -> replay(new ReplayRequest(request.requestId(), death(second), "other-trace")));
        assertEquals("DEAD", state(second));
        assertEquals(40400, assertThrows(StratumException.class, () -> replay(request(UUID.randomUUID()))).getErrorCode().getCode());
    }

    /** 人工恢复后的上层失败回滚执行周期、死亡标志和请求记录。 */
    @Test
    void rollsBackReplayRegistrationCompletely() {
        var lease = start(1); finish(lease, Outcome.FAILED);
        var request = request(death(lease));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            recovery.replay(request);
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals("DEAD", state(lease));
        assertEquals(0L, jdbc.queryForObject("SELECT cycle_no FROM job_execution WHERE execution_id=?", Long.class, id(lease)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_replay_request WHERE request_id=?", Integer.class, request.requestId().toString()));
        assertEquals("DEAD", jdbc.queryForObject("SELECT state FROM dead_job WHERE dead_id=?", String.class, request.deadId().toString()));
        assertEquals(lease.submission().executionId(), replay(request));
    }

    /** 极大累计尝试不能恢复成无法领取的WAITING，版本到顶同样保留原死亡状态。 */
    @Test
    void rejectsReplayWhenCountersCannotAdvance() {
        var version = start(1); finish(version, Outcome.FAILED);
        jdbc.update("UPDATE job_execution SET version=? WHERE execution_id=?", Long.MAX_VALUE, id(version));
        conflict(() -> replay(request(death(version))));
        assertEquals("DEAD", state(version));
        var attempts = start(1); finish(attempts, Outcome.FAILED);
        jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id(attempts));
        jdbc.update("UPDATE job_attempt SET attempt_no=? WHERE execution_id=?", Long.MAX_VALUE, id(attempts));
        jdbc.update("UPDATE job_execution SET attempt_no=? WHERE execution_id=?", Long.MAX_VALUE, id(attempts));
        UUID dead = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dead_job(dead_id,execution_id,cycle_no,last_attempt,failure_count,reason)
                VALUES (?,?,0,?,1,'FAILED')
                """, dead.toString(), id(attempts), Long.MAX_VALUE);
        conflict(() -> replay(request(dead)));
        assertEquals("DEAD", state(attempts));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_replay_request WHERE execution_id=?", Integer.class, id(attempts)));
    }

    /** 写事务与固定分类约束不能绕过；租约过期结果仅供内部数据库接管。 */
    @Test
    void rejectsMissingReadOnlyTransactionAndInternalOutcome() {
        var lease = start(1);
        var request = request(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> recovery.finish(lease, Outcome.SUCCEEDED));
        assertThrows(IllegalStateException.class, () -> recovery.replay(request));
        unitOfWork.execute(() -> {
            assertThrows(IllegalArgumentException.class, () -> recovery.finish(lease, Outcome.LEASE_EXPIRED));
            boolean previous = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try {
                assertThrows(IllegalStateException.class, () -> recovery.finish(lease, Outcome.SUCCEEDED));
                assertThrows(IllegalStateException.class, () -> recovery.replay(request));
            } finally {
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(previous);
            }
            return null;
        });
        assertEquals("RUNNING", state(lease));
    }

    /** 两个独立连接同时完成时，忙行跳过，不等待旧时刻或重复写历史。 */
    @Test
    void skipsConcurrentCompletionUntilOwnerCommits() throws Exception {
        var lease = start(3);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> unitOfWork.execute(() -> {
                assertTrue(recovery.finish(lease, Outcome.SUCCEEDED));
                held.countDown(); awaitRelease(release);
                return null;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(2), () -> assertFalse(finish(lease, Outcome.FAILED)));
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            assertFalse(finish(lease, Outcome.FAILED));
        }
        assertEquals("SUCCEEDED", state(lease));
    }

    /** 恢复竞争由执行行控制，未提交请求不可见时返回409，提交后原请求稳定去重。 */
    @Test
    void serializesReplayAcrossIndependentTransactions() throws Exception {
        var lease = start(1); finish(lease, Outcome.FAILED);
        var request = request(death(lease));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> unitOfWork.execute(() -> {
                UUID result = recovery.replay(request);
                held.countDown(); awaitRelease(release);
                return result;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(2), () -> conflict(() -> replay(request)));
            } finally { release.countDown(); }
            assertEquals(lease.submission().executionId(), first.get(5, TimeUnit.SECONDS));
            assertEquals(lease.submission().executionId(), replay(request));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_replay_request WHERE execution_id=?", Integer.class, id(lease)));
    }

    /**
     * 在真实工作单元中登记并领取一次，保持输入精度。
     * @param attempts 每周期最大次数
     * @return 已提交领取凭据
     */
    private Lease start(int attempts) {
        UUID id = UUID.randomUUID(); ids.add(id);
        var definition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(TIME), 30,
                new JobDefinition.RetryPolicy(attempts, Duration.ofSeconds(5), Duration.ofSeconds(10)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        var input = new JobExecutionRepository.Submission(id, definition, TIME, id.toString(),
                "{\"quantity\":9999999999999999999999.123456789}", "initial-job-trace");
        unitOfWork.execute(() -> executions.register(input));
        return claim(id);
    }

    /**
     * 在新工作单元中领取。
     * @param id 执行UUID
     * @return 完整领取证据
     */
    private Lease claim(UUID id) { return unitOfWork.execute(() -> executions.claim(id)).orElseThrow(); }

    /**
     * 在新工作单元中完成。
     * @param lease 原领取
     * @param outcome 固定分类
     * @return 是否完成
     */
    private boolean finish(Lease lease, Outcome outcome) { return unitOfWork.execute(() -> recovery.finish(lease, outcome)); }

    /**
     * 在新工作单元中登记人工恢复。
     * @param request 独立请求UUID和目标
     * @return 原执行UUID
     */
    private UUID replay(ReplayRequest request) { return unitOfWork.execute(() -> recovery.replay(request)); }

    /**
     * 返回执行UUID文本以绑定独立断言。
     * @param lease 领取证据
     * @return 执行UUID文本
     */
    private String id(Lease lease) { return lease.submission().executionId().toString(); }

    /**
     * 独立读取自身状态。
     * @param lease 领取证据
     * @return 当前状态
     */
    private String state(Lease lease) { return jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, id(lease)); }

    /**
     * 查询当前失败周期的实际死亡UUID。
     * @param lease 当前周期领取
     * @return 死亡UUID
     */
    private UUID death(Lease lease) {
        return UUID.fromString(jdbc.queryForObject("SELECT dead_id FROM dead_job WHERE execution_id=? AND cycle_no=?",
                String.class, id(lease), lease.cycle()));
    }

    /**
     * 构造人工请求，不替换任务参数。
     * @param dead 死亡UUID
     * @return 安全关联的随机请求
     */
    private ReplayRequest request(UUID dead) { return new ReplayRequest(UUID.randomUUID(), dead, "manual-replay-trace"); }

    /**
     * 仅用SQL把自身退避定位到到期位置，Quartz触发行为在后续节点验收。
     * @param lease 自身领取
     */
    private void ready(Lease lease) { jdbc.update("UPDATE job_execution SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE execution_id=?", id(lease)); }

    /**
     * 验证固定状态冲突。
     * @param action 预期失败的工作单元
     */
    private void conflict(Runnable action) { assertEquals(40900, assertThrows(StratumException.class, action::run).getErrorCode().getCode()); }

    /**
     * 有界等待测试释放信号，不参与分布式互斥。
     * @param release 本用例信号
     */
    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试持锁超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("测试中断", failure);
        }
    }
}
