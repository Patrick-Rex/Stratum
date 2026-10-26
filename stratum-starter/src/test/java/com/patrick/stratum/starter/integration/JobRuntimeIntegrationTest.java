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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import com.patrick.stratum.job.JobExecutionResult;
import com.patrick.stratum.job.JobRuntimeConfiguration;
import com.patrick.stratum.job.JobTaskExecutor;
import com.patrick.stratum.job.ProtectedJobWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(probe.virtual);
        assertFalse(probe.inTransaction);
        assertEquals("runtime-trace", probe.trace);
        await().atMost(Duration.ofSeconds(2)).until(() -> executor.activeCount() == 0);
        assertFalse(probe.threads.getFirst().isAlive());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=? AND result='SUCCEEDED'", Integer.class, id.toString()));
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
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    /** 经过真实一秒处理预算后中断实际处理器，超时记录提交且线程真正退出。 */
    @Test
    void interruptsTimedOutActualBody() throws Exception {
        probe.mode = Mode.WAIT;
        var executor = runtime();
        var id = register(1);
        assertEquals(JobExecutionResult.TIMED_OUT, executor.execute(probe.code, id));
        assertTrue(probe.interrupted.await(1, TimeUnit.SECONDS));
        assertEquals("DEAD", state(id));
        assertEquals("TIMED_OUT", jdbc.queryForObject("SELECT reason FROM dead_job WHERE execution_id=?", String.class, id.toString()));
        assertTrue(jdbc.queryForObject("SELECT duration_ms FROM job_attempt WHERE execution_id=?", Long.class, id.toString()) >= 800);
        await().atMost(Duration.ofSeconds(2)).until(() -> executor.activeCount() == 0);
        assertFalse(probe.threads.getFirst().isAlive());
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
        var context = new AnnotationConfigApplicationContext();
        String name = "runtime-" + UUID.randomUUID(); names.add(name); contexts.add(context);
        context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("runtime-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", name, "stratum.jobs.workers", 1)));
        context.registerBean(JobHandler.class, () -> probe);
        context.register(SchedulerConfiguration.class, JobRuntimeConfiguration.class);
        context.refresh();
        schedulerIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
        return context.getBean(JobTaskExecutor.class);
    }

    /**
     * 通过真实Application工作单元登记已到期的一次性任务。
     * @param timeoutSeconds 实际处理预算
     * @return 随机执行身份
     */
    private UUID register(int timeoutSeconds) {
        UUID id = UUID.randomUUID(); ids.add(id);
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        var definition = new JobDefinition(probe.code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), timeoutSeconds,
                new JobDefinition.RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
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
        public void execute(Submission submission) throws InterruptedException {
            threads.add(Thread.currentThread());
            virtual = Thread.currentThread().isVirtual();
            inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            trace = TraceIdContext.getRequiredTraceId();
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
