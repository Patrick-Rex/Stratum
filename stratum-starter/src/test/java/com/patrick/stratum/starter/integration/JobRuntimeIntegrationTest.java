package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import com.patrick.stratum.job.JobExecutionResult;
import com.patrick.stratum.job.JobRuntimeConfiguration;
import com.patrick.stratum.job.JobMetrics;
import com.patrick.stratum.job.JobTaskExecutor;
import com.patrick.stratum.job.ProtectedJobWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实Application、MariaDB和ShedLock代理验证有界虚拟线程执行，故障探针不替代事务或数据库锁。 */
class JobRuntimeIntegrationTest extends MariaDbIntegrationTest {
    private final ApplicationContext parent;
    private final JobExecutionApplicationService jobs;
    private final JdbcTemplate jdbc;
    private final Probe probe = new Probe("runtime." + UUID.randomUUID());
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> schedulerIds = new ArrayList<>();
    private final List<UUID> ids = new ArrayList<>();

    /**
     * 注入完整生产应用状态入口及独立断言客户端。
     * @param parent 已迁移父上下文
     * @param jobs 生产工作单元入口
     * @param jdbc 断言和自身命名空间清理
     */
    @Autowired
    JobRuntimeIntegrationTest(ApplicationContext parent, JobExecutionApplicationService jobs, JdbcTemplate jdbc) {
        this.parent = parent; this.jobs = jobs; this.jdbc = jdbc;
    }

    /** 先释放故障探针并关闭自己的实际工作，再删除自己的技术记录，不触碰其他命名空间。 */
    @AfterEach
    void cleanup() {
        probe.release.countDown();
        contexts.reversed().forEach(AnnotationConfigApplicationContext::close);
        await().atMost(Duration.ofSeconds(5)).until(() -> probe.threads.stream().noneMatch(Thread::isAlive));
        await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && (names.stream().anyMatch(n -> t.getName().contains(n))
                        || schedulerIds.stream().anyMatch(n -> t.getName().contains(n)))));
        TraceIdContext.clear();
        for (var id : ids) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id.toString());
        }
        for (var name : names) {
            jdbc.update("DELETE FROM STRATUM_QRTZ_LOCKS WHERE SCHED_NAME=?", name);
            jdbc.update("DELETE FROM STRATUM_QRTZ_SCHEDULER_STATE WHERE SCHED_NAME=?", name);
        }
        jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", "lock:job:" + probe.code);
        var metrics = parent.getBean(MeterRegistry.class);
        metrics.getMeters().stream().filter(meter -> probe.code.equals(meter.getId().getTag("job"))).toList().forEach(metrics::remove);
    }

    /** 默认关闭不创建执行器；启用后真实虚拟线程处理在事务外，使用原trace且不污染调用方。 */
    @Test
    void executesOutsideTransactionAndRestoresTrace() throws Exception {
        assertTrue(parent.getBeansOfType(JobTaskExecutor.class).isEmpty());
        var executor = runtime();
        var id = register(10);
        TraceIdContext.setTraceId("caller-runtime");
        assertEquals(JobExecutionResult.SUCCEEDED, executor.execute(probe.code, id));
        assertEquals("caller-runtime", TraceIdContext.getRequiredTraceId());
        assertEquals("SUCCEEDED", state(id));
        assertEquals(1, probe.calls.get());
        assertNotNull(probe.lease); assertEquals(id, probe.lease.submission().executionId());
        assertEquals(probe.lease.token().toString(), jdbc.queryForObject("SELECT lease_token FROM job_attempt WHERE execution_id=?", String.class, id.toString()));
        assertTrue(probe.virtual);
        assertFalse(probe.inTransaction);
        assertEquals("runtime-trace", probe.trace);
        await().atMost(Duration.ofSeconds(2)).until(() -> executor.activeCount() == 0);
        assertFalse(probe.threads.getFirst().isAlive());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=? AND result='SUCCEEDED'", Integer.class, id.toString()));
        assertEquals(1, timerCount("succeeded"));
        String scrape = parent.getBean(PrometheusMeterRegistry.class).scrape();
        assertTrue(scrape.contains("stratum_job_duration_seconds_count"));
        assertFalse(scrape.contains(id.toString())); assertFalse(scrape.contains("runtime-trace"));
        assertFalse(scrape.contains("secret-runtime-canary"));
    }

    /** 工作线程恢复登记上下文和双标识日志字段，调用方上下文不被更改。 */
    @Test
    void restoresStoredStandardContextOnPrivateWorker() throws Exception {
        var trace = new OpenTelemetryTraceContext(); var executor = runtime(); UUID id;
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        String caller = "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01";
        try (var ignored = trace.restore(trace.normalize(parent, "vendor=job"))) { id = register(10); }
        MDC.put("businessTraceId", "caller-business"); MDC.put("trace_id", "caller-standard"); MDC.put("span_id", "caller-span");
        try (var ignored = trace.restore(trace.normalize(caller, "vendor=caller"))) {
            assertEquals(JobExecutionResult.SUCCEEDED, executor.execute(probe.code, id));
            assertEquals(caller, trace.capture().orElseThrow().traceparent());
            assertEquals("caller-business", MDC.get("businessTraceId"));
            assertEquals("caller-standard", MDC.get("trace_id")); assertEquals("caller-span", MDC.get("span_id"));
        } finally { MDC.remove("businessTraceId"); MDC.remove("trace_id"); MDC.remove("span_id"); }
        assertEquals(parent, probe.standardTrace.orElseThrow().traceparent());
        assertEquals("vendor=job", probe.standardTrace.orElseThrow().tracestate());
        assertEquals("runtime-trace", probe.businessMdc);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", probe.standardMdc); assertEquals("bbbbbbbbbbbbbbbb", probe.spanMdc);
    }

    /** 历史空上下文和非法父头使用根上下文，不继承调用方标准标识。 */
    @Test
    void keepsHistoricalAndInvalidContextIsolated() throws Exception {
        var trace = new OpenTelemetryTraceContext(); var executor = runtime(); UUID historical; UUID invalid;
        try (var ignored = trace.restore(Optional.empty())) { historical = register(10); invalid = register(10); }
        jdbc.update("UPDATE job_execution SET standard_traceparent=?,standard_tracestate=? WHERE execution_id=?",
                "invalid-parent", "invalid-state", invalid.toString());
        String caller = "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01";
        try (var ignored = trace.restore(trace.normalize(caller, "vendor=caller"))) {
            assertEquals(JobExecutionResult.SUCCEEDED, executor.execute(probe.code, historical));
            assertTrue(probe.standardTrace.isEmpty()); assertNull(probe.standardMdc); assertNull(probe.spanMdc);
            assertEquals(JobExecutionResult.SUCCEEDED, executor.execute(probe.code, invalid));
            assertTrue(probe.standardTrace.isEmpty()); assertNull(probe.standardMdc); assertNull(probe.spanMdc);
            assertEquals(caller, trace.capture().orElseThrow().traceparent());
        }
    }

    /** 规范化或恢复组件故障时跳过处理器并保留租约，到期接管后按既有规则可重试。 */
    @Test
    void leavesLeaseForRecoveryWhenTraceComponentFails() throws Exception {
        var actual = new OpenTelemetryTraceContext();
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        var logger = (Logger) LoggerFactory.getLogger(ProtectedJobWorker.class);
        var captured = new ListAppender<ILoggingEvent>(); captured.start(); logger.addAppender(captured);
        try {
            for (boolean failNormalize : List.of(true, false)) {
                var faulty = mock(StandardTraceContext.class);
                if (failNormalize) when(faulty.normalize(nullable(String.class), nullable(String.class)))
                        .thenThrow(new IllegalStateException("private-normalize-failure"));
                else {
                    when(faulty.normalize(nullable(String.class), nullable(String.class)))
                            .thenAnswer(call -> actual.normalize(call.getArgument(0), call.getArgument(1)));
                    when(faulty.restore(any())).thenThrow(new IllegalStateException("private-restore-failure"));
                }
                UUID id;
                try (var ignored = actual.restore(actual.normalize(parent, "vendor=job"))) { id = register(10, 2); }
                int before = probe.calls.get(); var faultyExecutor = runtime(false, faulty);
                assertEquals(JobExecutionResult.STATE_UNCERTAIN, faultyExecutor.execute(probe.code, id));
                assertEquals(before, probe.calls.get()); assertEquals("RUNNING", state(id));
                assertEquals("RUNNING", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, id.toString()));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE execution_id=?", Integer.class, id.toString()));
                jdbc.update("""
                        UPDATE job_execution e JOIN job_attempt a ON a.execution_id=e.execution_id AND a.attempt_no=e.attempt_no
                        SET e.start_time=UTC_TIMESTAMP(6)-INTERVAL 41 SECOND,e.lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,
                        a.start_time=UTC_TIMESTAMP(6)-INTERVAL 41 SECOND WHERE e.execution_id=?
                        """, id.toString());
                var recoveredExecutor = runtime();
                assertEquals(JobExecutionResult.SKIPPED, recoveredExecutor.execute(probe.code, id));
                assertEquals("RETRY_WAIT", state(id));
                assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=? AND attempt_no=1", String.class, id.toString()));
                jdbc.update("UPDATE job_execution SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE execution_id=?", id.toString());
                assertEquals(JobExecutionResult.SUCCEEDED, recoveredExecutor.execute(probe.code, id));
                assertEquals(before + 1, probe.calls.get()); assertEquals(parent, probe.standardTrace.orElseThrow().traceparent());
            }
            var warnings = captured.list.stream().filter(event -> event.getFormattedMessage().contains("任务标准追踪恢复失败")).toList();
            assertEquals(2, warnings.size());
            assertTrue(warnings.stream().allMatch(event -> event.getKeyValuePairs().stream()
                    .anyMatch(pair -> pair.key.equals("errorType") && pair.value.equals("IllegalStateException"))));
            assertFalse(warnings.toString().contains("private-normalize-failure")); assertFalse(warnings.toString().contains("private-restore-failure"));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    /** 原异常正文及参数不进技术日志，失败与死亡记录真实提交且具备必需关联字段。 */
    @Test
    void persistsFailureAndLogsOnlySafeFields() throws Exception {
        probe.mode = Mode.FAIL;
        var executor = runtime();
        var id = register(10);
        var logger = (Logger) LoggerFactory.getLogger(ProtectedJobWorker.class);
        var captured = new ListAppender<ILoggingEvent>(); captured.start(); logger.addAppender(captured);
        try {
            assertEquals(JobExecutionResult.FAILED, executor.execute(probe.code, id));
            assertEquals("DEAD", state(id));
            assertEquals("FAILED", jdbc.queryForObject("SELECT reason FROM dead_job WHERE execution_id=?", String.class, id.toString()));
            var event = captured.list.stream().filter(e -> e.getFormattedMessage().contains(id.toString())).findFirst().orElseThrow();
            String text = event.getFormattedMessage();
            for (String field : List.of("jobCode=", "scheduleTime=", "startTime=", "endTime=", "durationMs=", "result=FAILED", "traceId=runtime-trace")) {
                assertTrue(text.contains(field), field);
            }
            assertFalse(text.contains("secret-runtime-canary"));
            assertNull(event.getThrowableProxy());
            assertEquals(Level.WARN, event.getLevel()); assertEquals(1, timerCount("failed"));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    /** 经过真实一秒处理预算后中断实际处理器，超时记录提交且线程真正退出。 */
    @Test
    void interruptsTimedOutActualBody() throws Exception {
        probe.mode = Mode.WAIT;
        var executor = runtime();
        var id = register(1);
        var logger = (Logger) LoggerFactory.getLogger(ProtectedJobWorker.class);
        var captured = new ListAppender<ILoggingEvent>(); captured.start(); logger.addAppender(captured);
        try {
            assertEquals(JobExecutionResult.TIMED_OUT, executor.execute(probe.code, id));
            assertTrue(probe.interrupted.await(1, TimeUnit.SECONDS));
            assertEquals("DEAD", state(id));
            assertEquals("TIMED_OUT", jdbc.queryForObject("SELECT reason FROM dead_job WHERE execution_id=?", String.class, id.toString()));
            assertTrue(jdbc.queryForObject("SELECT duration_ms FROM job_attempt WHERE execution_id=?", Long.class, id.toString()) >= 800);
            assertEquals(1, timerCount("timed_out"));
            assertEquals(1, parent.getBean(MeterRegistry.class).get("stratum.job.slow")
                    .tags("job", probe.code, "result", "timed_out").counter().count());
            assertEquals(Level.WARN, captured.list.stream().filter(event -> event.getFormattedMessage().contains(id.toString())).findFirst().orElseThrow().getLevel());
            await().atMost(Duration.ofSeconds(2)).until(() -> executor.activeCount() == 0);
            assertFalse(probe.threads.getFirst().isAlive());
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    /**
     * 领取证据被竞争替换时不能记作成功，真实结果及指标均显示状态不确定。
     * @throws Exception 并发测试失败
     */
    @Test
    void recordsUncertainResultWhenLeaseTokenChanges() throws Exception {
        probe.mode = Mode.WAIT; var executor = runtime(); var id = register(10);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var running = caller.submit(() -> executor.execute(probe.code, id));
            try {
                assertTrue(probe.entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, jdbc.update("UPDATE job_execution SET lease_token=? WHERE execution_id=?", UUID.randomUUID().toString(), id.toString()));
            } finally { probe.release.countDown(); }
            assertEquals(JobExecutionResult.STATE_UNCERTAIN, running.get(5, TimeUnit.SECONDS));
            assertEquals(1, timerCount("state_uncertain"));
            assertNull(parent.getBean(MeterRegistry.class).find("stratum.job.duration").tags("job", probe.code, "result", "succeeded").timer());
        }
    }

    /**
     * 指标后端抛错不改变实际业务任务已提交结果。
     * @throws Exception 执行或装配失败
     */
    @Test
    void preservesCommittedResultWhenMetricsFail() throws Exception {
        probe.mode = Mode.WAIT; var executor = runtime(true); var id = register(10);
        var logger = (Logger) LoggerFactory.getLogger(ProtectedJobWorker.class);
        var captured = new ListAppender<ILoggingEvent>(); captured.start(); logger.addAppender(captured);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var running = caller.submit(() -> executor.execute(probe.code, id));
            try {
                assertTrue(probe.entered.await(5, TimeUnit.SECONDS));
                assertFalse(new CountDownLatch(1).await(20, TimeUnit.MILLISECONDS));
            } finally { probe.release.countDown(); }
            assertEquals(JobExecutionResult.SUCCEEDED, running.get(5, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", state(id));
            assertTrue(captured.list.stream().anyMatch(event -> event.getFormattedMessage().contains("任务指标记录失败")));
            assertTrue(captured.list.stream().noneMatch(event -> event.getFormattedMessage().contains("secret-metrics-canary")));
            assertEquals(Level.WARN, captured.list.stream().filter(event -> event.getFormattedMessage().contains(id.toString())).findFirst().orElseThrow().getLevel());
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    /** 故意不合作的探针在超时返回后仍占本机容量和实际数据库锁，另实例跳过而不领取后续任务。 */
    @Test
    void retainsCapacityAndDatabaseLockUntilActualExit() throws Exception {
        probe.mode = Mode.IGNORE_INTERRUPT;
        var first = runtime(); var second = runtime();
        var one = register(1); var two = register(10);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var running = caller.submit(() -> first.execute(probe.code, one));
            try {
                assertTrue(probe.entered.await(5, TimeUnit.SECONDS));
                assertEquals(JobExecutionResult.STILL_RUNNING, running.get(9, TimeUnit.SECONDS));
                assertTrue(probe.interrupted.await(1, TimeUnit.SECONDS));
                assertEquals(1, first.activeCount());
                assertEquals("RUNNING", state(one));
                assertEquals(JobExecutionResult.CAPACITY_REJECTED, first.execute(probe.code, two));
                assertEquals(JobExecutionResult.SKIPPED, second.execute(probe.code, two));
                assertEquals("WAITING", state(two));
                assertEquals(1, probe.calls.get());
            } finally { probe.release.countDown(); }
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> first.activeCount() == 0);
        assertEquals("DEAD", state(one));
        probe.mode = Mode.RUN;
        assertEquals(JobExecutionResult.SUCCEEDED, second.execute(probe.code, two));
        assertEquals(1, probe.maximum.get());
    }

    /** 关闭时先阻止新工作并中断实际处理，完成INTERRUPTED提交后才关闭锁续期资源。 */
    @Test
    void closesActualWorkBeforeProvider() throws Exception {
        probe.mode = Mode.WAIT;
        var executor = runtime(); var id = register(30);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> executor.execute(probe.code, id));
            assertTrue(probe.entered.await(5, TimeUnit.SECONDS));
            contexts.getFirst().close();
            assertEquals(JobExecutionResult.INTERRUPTED, result.get(5, TimeUnit.SECONDS));
            assertEquals(0, executor.activeCount());
            assertFalse(probe.threads.getFirst().isAlive());
            assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT reason FROM dead_job WHERE execution_id=?", String.class, id.toString()));
            assertEquals(JobExecutionResult.CLOSED, executor.execute(probe.code, register(10)));
        }
    }

    /** 调用者预先中断不启动实际处理；未知身份跳过，未登记代码在创建线程前拒绝。 */
    @Test
    void rejectsBeforeStartingAndSkipsUnknownIdentity() throws Exception {
        var executor = runtime(); var id = register(10);
        try (var caller = Executors.newSingleThreadExecutor()) {
            assertTrue(caller.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    return executor.execute(probe.code, id) == JobExecutionResult.INTERRUPTED && Thread.currentThread().isInterrupted();
                } finally { Thread.interrupted(); }
            }).get(5, TimeUnit.SECONDS));
        }
        assertEquals("WAITING", state(id));
        assertEquals(JobExecutionResult.SKIPPED, executor.execute(probe.code, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> executor.execute("not.registered", id));
        assertEquals(0, probe.calls.get());
    }

    /**
     * 创建独立生产调度/锁/执行装配，共享真实父数据源，使用独立生命周期。
     * @return 当前用例负责关闭的执行器
     * @throws Exception 调度器身份读取失败
     */
    private JobTaskExecutor runtime() throws Exception {
        return runtime(false);
    }

    /**
     * 建立独立执行器，可注入只影响指标的受控失败。
     * @param failingMetrics 是否让指标记录失败
     * @return 当前用例负责关闭的执行器
     * @throws Exception 调度器身份读取失败
     */
    private JobTaskExecutor runtime(boolean failingMetrics) throws Exception {
        return runtime(failingMetrics, null);
    }

    /**
     * 建立独立执行器，可为工作线程注入标准追踪故障探针。
     * @param failingMetrics 是否让指标记录失败
     * @param trace 可空的标准追踪测试替身
     * @return 当前用例负责关闭的执行器
     * @throws Exception 调度器身份读取失败
     */
    private JobTaskExecutor runtime(boolean failingMetrics, StandardTraceContext trace) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        String name = "runtime-" + UUID.randomUUID(); names.add(name); contexts.add(context);
        context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("runtime-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", name, "stratum.jobs.workers", 1,
                "observability.job.slow-threshold-ms", failingMetrics ? 1 : 500)));
        context.registerBean(JobHandler.class, () -> probe);
        if (trace != null) context.registerBean("traceProbe", StandardTraceContext.class, () -> trace, definition -> definition.setPrimary(true));
        if (failingMetrics) {
            var failing = mock(JobMetrics.class);
            when(failing.record(anyString(), any(), anyLong())).thenThrow(new IllegalStateException("secret-metrics-canary"));
            context.registerBean("failingMetrics", JobMetrics.class, () -> failing, definition -> definition.setPrimary(true));
        }
        context.register(SchedulerConfiguration.class, JobRuntimeConfiguration.class);
        context.refresh();
        schedulerIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
        return context.getBean(JobTaskExecutor.class);
    }

    /**
     * 读取本用例固定任务和结果的实际耗时样本数。
     * @param result 有限结果标签
     * @return 已记录样本数
     */
    private long timerCount(String result) {
        return parent.getBean(MeterRegistry.class).get("stratum.job.duration").tags("job", probe.code, "result", result).timer().count();
    }

    /**
     * 通过真实Application工作单元登记已到期的一次性任务。
     * @param timeoutSeconds 实际处理预算
     * @return 随机执行身份
     */
    private UUID register(int timeoutSeconds) {
        return register(timeoutSeconds, 1);
    }

    /**
     * 按指定重试次数登记本用例的已到期任务。
     * @param timeoutSeconds 单次处理预算秒数
     * @param attempts 周期内最大尝试数
     * @return 随机执行身份
     */
    private UUID register(int timeoutSeconds, int attempts) {
        UUID id = UUID.randomUUID(); ids.add(id);
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        var definition = new JobDefinition(probe.code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), timeoutSeconds,
                new JobDefinition.RetryPolicy(attempts, Duration.ofSeconds(1), Duration.ofSeconds(1)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return jobs.register(new Submission(id, definition, time, id.toString(), "{\"canary\":\"secret-runtime-canary\"}", "runtime-trace"));
    }

    /**
     * 独立连接读取本用例执行状态。
     * @param id 自身执行身份
     * @return 持久化状态
     */
    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, id.toString());
    }

    /** 测试固定模式；不合作模式仅用于证明超时返回不能释放实际资源。 */
    private enum Mode { RUN, FAIL, WAIT, IGNORE_INTERRUPT }

    /** 有界同步探针，保留实际虚拟线程引用验证退出；所有等待均有测试截止时间。 */
    private static final class Probe implements JobHandler {
        private final String code;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private volatile Mode mode = Mode.RUN;
        private volatile boolean virtual;
        private volatile boolean inTransaction;
        private volatile String trace;
        private volatile Optional<StandardTraceContext.Carrier> standardTrace;
        private volatile String businessMdc;
        private volatile String standardMdc;
        private volatile String spanMdc;
        private volatile Lease lease;

        /**
         * 创建当前测试唯一固定代码探针。
         * @param code 随机固定任务代码
         */
        private Probe(String code) { this.code = code; }

        /** {@inheritDoc} */
        @Override
        public String jobCode() { return code; }

        /** {@inheritDoc} */
        @Override
        public void executeLease(Lease input) throws InterruptedException {
            lease = input; JobHandler.super.executeLease(input);
        }

        /** {@inheritDoc} */
        @Override
        public void execute(Submission submission) throws InterruptedException {
            threads.add(Thread.currentThread());
            virtual = Thread.currentThread().isVirtual();
            inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            trace = TraceIdContext.getRequiredTraceId();
            standardTrace = new OpenTelemetryTraceContext().capture();
            businessMdc = MDC.get("businessTraceId"); standardMdc = MDC.get("trace_id"); spanMdc = MDC.get("span_id");
            calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            try {
                switch (mode) {
                    case RUN -> { }
                    case FAIL -> throw new IllegalStateException("secret-runtime-canary");
                    case WAIT -> {
                        try { assertTrue(release.await(20, TimeUnit.SECONDS)); }
                        catch (InterruptedException signal) { interrupted.countDown(); throw signal; }
                    }
                    case IGNORE_INTERRUPT -> {
                        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                        while (release.getCount() > 0) {
                            long remaining = deadline - System.nanoTime();
                            assertTrue(remaining > 0, "故障探针必须在测试预算内释放");
                            try { assertTrue(release.await(remaining, TimeUnit.NANOSECONDS)); }
                            catch (InterruptedException signal) { interrupted.countDown(); }
                        }
                    }
                }
            } finally { active.decrementAndGet(); }
        }
    }
}
