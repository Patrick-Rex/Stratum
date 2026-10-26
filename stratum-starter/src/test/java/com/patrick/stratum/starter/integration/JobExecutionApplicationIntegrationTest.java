package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobRecoveryRepository;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import com.patrick.stratum.domain.job.JobRecoveryRepository.ReplayRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 真实应用工作单元与数据库验证返回提交、故障回滚和上下文；包装器只在真实仓储调用后注入故障。 */
class JobExecutionApplicationIntegrationTest extends MariaDbIntegrationTest {
    private final JobExecutionApplicationService jobs;
    private final JobExecutionRepository executions;
    private final JobRecoveryRepository recovery;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<UUID> ids = new ArrayList<>();
    private final String code = "application." + UUID.randomUUID();

    /**
     * 注入完整应用组件与实际存储，不替换真实事务代理。
     * @param jobs 生产Application入口
     * @param executions 实际登记领取端口
     * @param recovery 实际完成恢复端口
     * @param unitOfWork Spring事务工作单元
     * @param jdbc 独立断言与随机数据清理
     */
    @Autowired
    JobExecutionApplicationIntegrationTest(JobExecutionApplicationService jobs, JobExecutionRepository executions,
            JobRecoveryRepository recovery, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.jobs = jobs; this.executions = executions; this.recovery = recovery; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 清理本用例技术记录和线程关联标识。 */
    @AfterEach
    void cleanup() {
        TraceIdContext.clear();
        for (UUID id : ids) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id.toString());
        }
    }

    /** 返回登记时独立连接已可见提交，领取及完成也在各自工作单元返回后可见。 */
    @Test
    void commitsBeforeReturningAndRestoresCallerTrace() throws Exception {
        TraceIdContext.setTraceId("caller-trace");
        var input = input(3);
        assertEquals(input.executionId(), jobs.register(input));
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.prepareStatement("SELECT state FROM job_execution WHERE execution_id=?")) {
            statement.setString(1, input.executionId().toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next()); assertEquals("WAITING", result.getString(1));
            }
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(List.of(input.executionId()), jobs.due(code, 1));
        var lease = jobs.claim(input.executionId()).orElseThrow();
        assertEquals("RUNNING", state(input.executionId()));
        assertTrue(jobs.finish(lease, Outcome.SUCCEEDED));
        assertEquals("SUCCEEDED", state(input.executionId()));
        assertEquals("caller-trace", TraceIdContext.getRequiredTraceId());
    }

    /** 登记写入后的异常及空结果均在提交前回滚，原调用trace恢复。 */
    @Test
    void rollsBackFailedAndNullRegistration() {
        for (boolean nil : List.of(false, true)) {
            var input = input(3);
            var observed = new AtomicReference<String>();
            TraceIdContext.setTraceId("caller-trace");
            var faulty = faulty("register", nil, observed);
            assertThrows(RuntimeException.class, () -> faulty.register(input));
            assertEquals("job-application-trace", observed.get());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE execution_id=?", Integer.class, input.executionId().toString()));
            assertEquals("caller-trace", TraceIdContext.getRequiredTraceId());
        }
    }

    /** 领取写入后的异常或空可选值不能留下RUNNING或尝试历史。 */
    @Test
    void rollsBackFailedAndNullClaim() {
        for (boolean nil : List.of(false, true)) {
            var input = input(3); jobs.register(input);
            var faulty = faulty("claim", nil, new AtomicReference<>());
            assertThrows(RuntimeException.class, () -> faulty.claim(input.executionId()));
            assertEquals("WAITING", state(input.executionId()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, input.executionId().toString()));
            assertEquals(1, jobs.claim(input.executionId()).orElseThrow().attempt());
        }
    }

    /** 成功及恢复关联写回后注入故障，运行尝试和待恢复死亡一起回滚。 */
    @Test
    void rollsBackFinishAndHistoricalResolution() {
        var original = start(1);
        jobs.finish(original, Outcome.FAILED);
        var request = request(original);
        jobs.replay(request);
        var next = jobs.claim(original.submission().executionId()).orElseThrow();
        var observed = new AtomicReference<String>();
        TraceIdContext.setTraceId("caller-trace");
        assertThrows(IllegalStateException.class, () -> faulty("finish", false, observed).finish(next, Outcome.SUCCEEDED));
        assertEquals(request.traceId(), observed.get());
        assertEquals("RUNNING", state(original.submission().executionId()));
        assertEquals("REPLAY_REQUESTED", jdbc.queryForObject("SELECT state FROM dead_job WHERE dead_id=?", String.class, request.deadId().toString()));
        assertEquals("caller-trace", TraceIdContext.getRequiredTraceId());
        assertTrue(jobs.finish(next, Outcome.SUCCEEDED));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT state FROM dead_job WHERE dead_id=?", String.class, request.deadId().toString()));
    }

    /** 恢复写入后的异常及空身份回滚周期/请求/死亡标志，新工作单元可重试原请求。 */
    @Test
    void rollsBackFailedAndNullReplay() {
        for (boolean nil : List.of(false, true)) {
            var lease = start(1); jobs.finish(lease, Outcome.FAILED);
            var request = request(lease);
            assertThrows(RuntimeException.class, () -> faulty("replay", nil, new AtomicReference<>()).replay(request));
            assertEquals("DEAD", state(lease.submission().executionId()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_replay_request WHERE request_id=?", Integer.class, request.requestId().toString()));
            assertEquals(0L, jdbc.queryForObject("SELECT cycle_no FROM job_execution WHERE execution_id=?", Long.class, lease.submission().executionId().toString()));
            assertEquals(lease.submission().executionId(), jobs.replay(request));
        }
    }

    /** 全部入口拒绝活动外层事务，避免返回但尚未提交；未进入仓储包装器。 */
    @Test
    void rejectsOuterTransactionBeforeEveryRepositoryCall() {
        var lease = start(3);
        var input = input(3);
        var observed = new AtomicReference<String>();
        var guarded = faulty("", false, observed);
        unitOfWork.execute(() -> {
            assertThrows(IllegalStateException.class, () -> guarded.register(input));
            assertThrows(IllegalStateException.class, () -> guarded.due(code, 1));
            assertThrows(IllegalStateException.class, () -> guarded.claim(lease.submission().executionId()));
            assertThrows(IllegalStateException.class, () -> guarded.finish(lease, Outcome.SUCCEEDED));
            assertThrows(IllegalStateException.class, () -> guarded.replay(new ReplayRequest(UUID.randomUUID(), UUID.randomUUID(), "trace")));
            return null;
        });
        assertNull(observed.get());
        assertEquals("RUNNING", state(lease.submission().executionId()));
    }

    /** 无原上下文时所有正常/异常路径清理临时关联，不污染复用线程。 */
    @Test
    void clearsTemporaryTraceOnSuccessAndFailure() {
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        var lease = start(3);
        jobs.due(code, 1);
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        jobs.finish(lease, Outcome.SUCCEEDED);
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        assertThrows(IllegalStateException.class, () -> faulty("register", false, new AtomicReference<>()).register(input(3)));
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 对未知执行身份的操作使用安全调用trace，结束后精确恢复先前上下文。 */
    @Test
    void sanitizesOperationTraceWithoutChangingCallerContext() {
        var observed = new AtomicReference<String>();
        TraceIdContext.setTraceId("unsafe\ncaller");
        assertTrue(faulty("", false, observed).due(code, 1).isEmpty());
        assertTrue(CorrelationIdPolicy.isValid(observed.get()));
        assertEquals("unsafe\ncaller", TraceIdContext.getRequiredTraceId());
    }

    /**
     * 构造由真实工作单元支持的故障包装入口，不替换数据源或事务适配。
     * @param operation 真实调用后注入故障的操作名，空字符串不注入
     * @param nil 是否以空结果代替抛出故障
     * @param observed 事务内观测到的关联标识
     * @return 手动构造的同一生产应用编排类
     */
    private JobExecutionApplicationService faulty(String operation, boolean nil, AtomicReference<String> observed) {
        var faults = new Faults(executions, recovery, operation, nil, observed);
        return new JobExecutionApplicationService(unitOfWork, faults, faults);
    }

    /**
     * 构造有界一次性任务输入。
     * @param attempts 每周期最大次数
     * @return 本用例负责清理的随机身份输入
     */
    private Submission input(int attempts) {
        UUID id = UUID.randomUUID(); ids.add(id);
        Instant time = Instant.parse("2026-01-01T00:00:00.123456Z");
        var definition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                new JobDefinition.RetryPolicy(attempts, Duration.ofSeconds(1), Duration.ofSeconds(5)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return new Submission(id, definition, time, id.toString(), "{\"quantity\":12345678901234567890.123456}", "job-application-trace");
    }

    /**
     * 通过完整应用入口登记及领取。
     * @param attempts 周期上限
     * @return 已提交的领取
     */
    private Lease start(int attempts) {
        var input = input(attempts); jobs.register(input);
        return jobs.claim(input.executionId()).orElseThrow();
    }

    /**
     * 创建当前死亡记录的恢复请求。
     * @param lease 已失败领取
     * @return 独立恢复请求
     */
    private ReplayRequest request(Lease lease) {
        String dead = jdbc.queryForObject("SELECT dead_id FROM dead_job WHERE execution_id=? AND cycle_no=?", String.class,
                lease.submission().executionId().toString(), lease.cycle());
        return new ReplayRequest(UUID.randomUUID(), UUID.fromString(dead), "manual-task-trace");
    }

    /**
     * 独立断言自身技术状态。
     * @param id 执行UUID
     * @return 当前状态
     */
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, id.toString()); }

    /**
     * 仅在真实存储操作后故障注入，验证Application提交前校验及整事务回滚。
     * @param executions 真实登记领取存储
     * @param recovery 真实完成恢复存储
     * @param operation 指定故障阶段
     * @param nil 是否返回空结果
     * @param observed 真实事务内关联值
     */
    private record Faults(JobExecutionRepository executions, JobRecoveryRepository recovery, String operation,
            boolean nil, AtomicReference<String> observed) implements JobExecutionRepository, JobRecoveryRepository {
        /** {@inheritDoc} */
        @Override
        public UUID register(Submission submission) { return after("register", executions.register(submission)); }
        /** {@inheritDoc} */
        @Override
        public List<UUID> due(String code, int limit) { return after("due", executions.due(code, limit)); }
        /** {@inheritDoc} */
        @Override
        public Optional<Lease> claim(UUID id) { return after("claim", executions.claim(id)); }
        /** {@inheritDoc} */
        @Override
        public boolean finish(Lease lease, Outcome outcome) { return after("finish", recovery.finish(lease, outcome)); }
        /** {@inheritDoc} */
        @Override
        public UUID replay(ReplayRequest request) { return after("replay", recovery.replay(request)); }

        /**
         * 记录真实事务内上下文，并按测试配置在写入后注入故障或空结果。
         * @param <T> 原返回类型
         * @param phase 当前操作
         * @param value 真实存储返回值
         * @return 原值或配置的空值
         * @throws IllegalStateException 本测试要求的写入后故障
         */
        private <T> T after(String phase, T value) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            observed.set(TraceIdContext.getRequiredTraceId());
            if (!operation.equals(phase)) return value;
            if (nil) return null;
            throw new IllegalStateException("controlled-job-storage-failure");
        }
    }
}
