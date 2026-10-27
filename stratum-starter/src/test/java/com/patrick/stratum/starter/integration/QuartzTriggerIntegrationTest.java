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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobRecoveryRepository.ReplayRequest;
import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import com.patrick.stratum.job.JobRuntimeConfiguration;
import com.patrick.stratum.job.QuartzDispatchJob;
import com.patrick.stratum.job.QuartzTriggerConfiguration;
import com.patrick.stratumtest.job.JobCrashProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Quartz JDBC触发验证固定注册、数据库重试、Cron计划身份、重启和双实例；没有手工调用Job冒充调度。 */
class QuartzTriggerIntegrationTest extends MariaDbIntegrationTest {
    private static final String GROUP = "stratum.fixed.jobs";
    private final ApplicationContext parent;
    private final JobExecutionApplicationService jobs;
    private final JdbcTemplate jdbc;
    private final String name = "trigger-" + UUID.randomUUID();
    private final Probe probe = new Probe("trigger." + UUID.randomUUID());
    private final RelayProbe relayProbe = new RelayProbe();
    private final List<UUID> relayExecutions = new ArrayList<>();
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final List<String> schedulerIds = new ArrayList<>();

    /**
     * 注入生产Application与已迁移数据源，Quartz独立实例只共享持久化基础设施。
     * @param parent 真实父上下文
     * @param jobs 执行状态入口
     * @param jdbc 独立断言和清理
     */
    @Autowired
    QuartzTriggerIntegrationTest(ApplicationContext parent, JobExecutionApplicationService jobs, JdbcTemplate jdbc) {
        this.parent = parent; this.jobs = jobs; this.jdbc = jdbc;
    }

    /** 所有实例先停止并验证平台/实际虚拟线程退出，再仅删除本用例随机命名空间。 */
    @AfterEach
    void cleanup() {
        probe.release.countDown(); relayProbe.release.countDown();
        contexts.reversed().forEach(AnnotationConfigApplicationContext::close);
        await().atMost(Duration.ofSeconds(5)).until(() -> probe.threads.stream().noneMatch(Thread::isAlive)
                && relayProbe.threads.stream().noneMatch(Thread::isAlive));
        await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && (t.getName().contains(name) || schedulerIds.stream().anyMatch(id -> t.getName().contains(id)))));
        var ids = jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code=?", String.class, probe.code);
        for (var id : ids) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id);
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id);
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id);
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id);
        }
        for (var id : relayExecutions) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id.toString());
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id.toString());
        }
        if (!relayExecutions.isEmpty()) {
            for (String prefix : List.of("lock:job:", "lock:job:trigger:run.", "lock:job:trigger:schedule.")) {
                jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", prefix + "outbox.relay");
            }
        }
        for (String schedulerName : List.of(name, "relay:" + name)) {
            jdbc.update("DELETE FROM STRATUM_QRTZ_SIMPLE_TRIGGERS WHERE SCHED_NAME=?", schedulerName);
            jdbc.update("DELETE FROM STRATUM_QRTZ_CRON_TRIGGERS WHERE SCHED_NAME=?", schedulerName);
            jdbc.update("DELETE FROM STRATUM_QRTZ_TRIGGERS WHERE SCHED_NAME=?", schedulerName);
            jdbc.update("DELETE FROM STRATUM_QRTZ_JOB_DETAILS WHERE SCHED_NAME=?", schedulerName);
            jdbc.update("DELETE FROM STRATUM_QRTZ_FIRED_TRIGGERS WHERE SCHED_NAME=?", schedulerName);
            jdbc.update("DELETE FROM STRATUM_QRTZ_LOCKS WHERE SCHED_NAME=?", schedulerName);
            jdbc.update("DELETE FROM STRATUM_QRTZ_SCHEDULER_STATE WHERE SCHED_NAME=?", schedulerName);
        }
        for (String prefix : List.of("lock:job:", "lock:job:trigger:run.", "lock:job:trigger:schedule.")) {
            jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", prefix + probe.code);
        }
    }

    /**
     * 同一完整装配包含两个调度器时，普通任务仍使用主调度器；Relay待机且不产生任务。
     * @throws Exception 调度器或任务操作失败
     */
    @Test
    void ordinaryFixedJobRunsWithRelaySchedulerInStandby() throws Exception {
        var context = context(true);
        var primary = context.getBean(Scheduler.class);
        var relay = context.getBean("relayJobScheduler", Scheduler.class);
        assertEquals(name, primary.getSchedulerName());
        assertEquals("relay:" + name, relay.getSchedulerName()); assertTrue(relay.isInStandbyMode());
        assertTrue(relay.getJobKeys(GroupMatcher.anyJobGroup()).isEmpty());
        var id = register();
        await().atMost(Duration.ofSeconds(15)).until(() -> "SUCCEEDED".equals(state(id)));
        assertEquals(1, probe.calls.get());
    }

    /**
     * 普通与Relay目标只登记到各自持久空间；双实例及重启核验相同定义，禁用实例不加入Relay。
     * @throws Exception 调度器或存储操作失败
     */
    @Test
    void registersRelayOnlyInItsDedicatedCluster() throws Exception {
        var first = context(true, true); var second = context(true, true);
        var primary = first.getBean(Scheduler.class); var relay = first.getBean("relayJobScheduler", Scheduler.class);
        assertTrue(relay.isInStandbyMode());
        assertEquals(java.util.Set.of(new JobKey("run." + probe.code, GROUP)), primary.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)));
        var expected = java.util.Set.of(new JobKey("run.outbox.relay", GROUP), new JobKey("schedule.outbox.relay", GROUP));
        assertEquals(expected, relay.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)));
        assertEquals(expected, second.getBean("relayJobScheduler", Scheduler.class).getJobKeys(GroupMatcher.jobGroupEquals(GROUP)));
        first.close(); second.close();
        var disabled = context(false, false);
        assertFalse(disabled.containsBean("relayJobScheduler"));
        assertEquals(java.util.Set.of(new JobKey("run." + probe.code, GROUP)), disabled.getBean(Scheduler.class).getJobKeys(GroupMatcher.jobGroupEquals(GROUP)));
        var restarted = context(true, true).getBean("relayJobScheduler", Scheduler.class);
        assertTrue(restarted.isInStandbyMode()); assertEquals(expected, restarted.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)));
    }

    /**
     * 已存储未知Relay目标或缺少专用调度器均拒绝启动，不执行处理器。
     * @throws Exception 调度器或存储操作失败
     */
    @Test
    void refusesUnknownRelayTargetAndHandlerWithoutScheduler() throws Exception {
        assertThrows(RuntimeException.class, () -> context(false, true));
        var initial = context(true, true); var relay = initial.getBean("relayJobScheduler", Scheduler.class);
        relay.addJob(JobBuilder.newJob(QuartzDispatchJob.class).withIdentity("run.unregistered", GROUP)
                .usingJobData("jobCode", "unregistered").usingJobData("mode", "run").storeDurably().build(), false);
        initial.close();
        assertThrows(RuntimeException.class, () -> context(true, true));
        assertEquals(0, probe.calls.get());
    }

    /**
     * 两组实际调度任务同时运行时关闭上下文，均记录中断并等待共享工作线程退出。
     * @throws Exception 调度或并发协调失败
     */
    @Test
    void stopsBothSchedulersBeforeClosingSharedWorkers() throws Exception {
        probe.block = true; relayProbe.block = true; relayProbe.periodic = false;
        var context = context(true, true);
        var relay = context.getBean("relayJobScheduler", Scheduler.class);
        var ordinaryId = register();
        var relayId = UUID.randomUUID(); relayExecutions.add(relayId);
        var time = Instant.parse("2026-01-01T00:00:00Z");
        var definition = new JobDefinition("outbox.relay", JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        jobs.register(new Submission(relayId, definition, time, relayId.toString(), "{}", "relay-shutdown-test"));
        relay.start();
        assertTrue(probe.entered.await(10, TimeUnit.SECONDS));
        assertTrue(relayProbe.entered.await(10, TimeUnit.SECONDS));
        context.close();
        assertEquals("RETRY_WAIT", state(ordinaryId)); assertEquals("RETRY_WAIT", state(relayId));
        assertEquals(List.of("INTERRUPTED"), results(ordinaryId)); assertEquals(List.of("INTERRUPTED"), results(relayId));
        assertTrue(probe.threads.stream().noneMatch(Thread::isAlive));
        assertTrue(relayProbe.threads.stream().noneMatch(Thread::isAlive));
    }

    /** 两个真实Scheduler共享固定轮询，失败后按数据库退避再次执行，不创建每次重试触发器。 */
    @Test
    void drivesPersistedRetryAcrossTwoSchedulers() throws Exception {
        probe.failures.set(1);
        var first = context(); var second = context();
        var id = register();
        await().atMost(Duration.ofSeconds(15)).until(() -> "SUCCEEDED".equals(state(id)));
        first.getBean(Scheduler.class).standby(); second.getBean(Scheduler.class).standby();
        assertEquals(2, probe.calls.get());
        assertEquals(1, probe.maximum.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, id.toString()));
        assertEquals(List.of("FAILED", "SUCCEEDED"), jdbc.queryForList("SELECT result FROM job_attempt WHERE execution_id=? ORDER BY attempt_no", String.class, id.toString()));
        assertTrue(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,a.end_time,b.start_time) FROM job_attempt a JOIN job_attempt b ON a.execution_id=b.execution_id AND b.attempt_no=a.attempt_no+1 WHERE a.execution_id=?", Long.class, id.toString()) >= 100_000);
        assertEquals(1, first.getBean(Scheduler.class).getJobKeys(GroupMatcher.jobGroupEquals(GROUP)).size());
        assertEquals(1, first.getBean(Scheduler.class).getTriggerKeys(GroupMatcher.triggerGroupEquals(GROUP)).size());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM stratum_shedlock WHERE name=?", Integer.class, "lock:job:trigger:run." + probe.code) == 1);
    }

    /** 重建Spring和Quartz后复用同一持久化作业/触发器，原WAITING执行由新实例实际处理。 */
    @Test
    void restartsFromPersistedWaitingExecution() throws Exception {
        var original = context(); var scheduler = original.getBean(Scheduler.class);
        scheduler.standby();
        var id = register();
        original.close();
        assertEquals("WAITING", state(id));
        var restarted = context().getBean(Scheduler.class);
        await().atMost(Duration.ofSeconds(25)).until(() -> "SUCCEEDED".equals(state(id)));
        restarted.standby();
        assertEquals(1, probe.calls.get());
        assertEquals(1, restarted.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)).size());
    }

    /** UTC Cron实际触发登记原计划时刻幂等身份，两个实例不创建重复执行或正文参数。 */
    @Test
    void registersCronByOriginalScheduledTime() throws Exception {
        probe.periodic = true;
        var first = context().getBean(Scheduler.class); var second = context().getBean(Scheduler.class);
        await().atMost(Duration.ofSeconds(15)).until(() -> probe.calls.get() >= 2);
        first.standby(); second.standby();
        assertEquals(2, first.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)).size());
        var rows = jdbc.queryForList("SELECT idempotency_key,schedule_time,arguments FROM job_execution WHERE job_code=?", probe.code);
        assertTrue(rows.size() >= 2);
        for (var row : rows) {
            var time = ((java.sql.Timestamp) row.get("schedule_time")).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
            assertEquals(probe.code + ":" + time, row.get("idempotency_key"));
            assertEquals("{}", row.get("arguments"));
        }
        assertEquals(rows.size(), rows.stream().map(row -> row.get("idempotency_key")).distinct().count());
        assertEquals(1, probe.maximum.get());
    }

    /** 同键已存储定义不一致时启动失败，不覆盖未知目标或静默执行被替换的代码。 */
    @Test
    void rejectsConflictingPersistedDefinition() throws Exception {
        var original = context(); var scheduler = original.getBean(Scheduler.class);
        scheduler.standby();
        var key = new JobKey("run." + probe.code, GROUP);
        scheduler.deleteJob(key);
        scheduler.addJob(JobBuilder.newJob(QuartzDispatchJob.class).withIdentity(key).storeDurably()
                .usingJobData("jobCode", "unknown.code").usingJobData("mode", "run").build(), false);
        original.close();
        assertThrows(RuntimeException.class, this::context);
        assertEquals(0, probe.calls.get());
    }

    /** 移除处理器周期定义后拒绝带旧Cron启动，不留下持续失败或未确认的新执行。 */
    @Test
    void rejectsOrphanedPeriodicTrigger() throws Exception {
        probe.periodic = true;
        var original = context();
        original.getBean(Scheduler.class).standby(); original.close();
        probe.periodic = false;
        assertThrows(RuntimeException.class, this::context);
    }

    /** 真实Quartz借用线程被中断后，实际处理先提交中断记录，再退出调度与锁续期线程。 */
    @Test
    void closesRunningQuartzAndActualWorker() throws Exception {
        probe.block = true;
        var context = context(); var id = register();
        assertTrue(probe.entered.await(10, TimeUnit.SECONDS));
        context.close();
        assertEquals("RETRY_WAIT", state(id));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT result FROM job_attempt WHERE execution_id=?", String.class, id.toString()));
        assertTrue(probe.threads.stream().noneMatch(Thread::isAlive));
    }

    /** 真实调度连续失败达到死亡，人工原请求幂等恢复同一执行，保留累计尝试并关联解决死亡记录。 */
    @Test
    void recoversDeadExecutionThroughOriginalIdentity() throws Exception {
        probe.failures.set(3);
        context(); context();
        var id = register();
        await().atMost(Duration.ofSeconds(15)).until(() -> "DEAD".equals(state(id)));
        assertEquals(3, probe.calls.get());
        var request = replayRequest(id);
        assertEquals(id, jobs.replay(request));
        assertEquals(id, jobs.replay(request));
        await().atMost(Duration.ofSeconds(10)).until(() -> "SUCCEEDED".equals(state(id)));
        assertEquals(id, jobs.replay(request));
        assertEquals(4, probe.calls.get());
        assertEquals(1, probe.maximum.get());
        assertEquals(List.of("FAILED", "FAILED", "FAILED", "SUCCEEDED"), results(id));
        assertEquals(List.of("trigger-trace", "trigger-trace", "trigger-trace", "recovered-trigger-trace"),
                jdbc.queryForList("SELECT trace_id FROM job_attempt WHERE execution_id=? ORDER BY attempt_no", String.class, id.toString()));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT state FROM dead_job WHERE execution_id=?", String.class, id.toString()));
        assertEquals(1L, jdbc.queryForObject("SELECT cycle_no FROM job_execution WHERE execution_id=?", Long.class, id.toString()));
        assertEquals("{}", jdbc.queryForObject("SELECT arguments FROM job_execution WHERE execution_id=?", String.class, id.toString()));
        assertEquals("trigger-trace", jdbc.queryForObject("SELECT initial_trace_id FROM job_execution WHERE execution_id=?", String.class, id.toString()));
        assertThrows(RuntimeException.class, () -> jobs.replay(new ReplayRequest(UUID.randomUUID(), request.deadId(), "new-request")));
    }

    /** 真实Quartz触发的处理器达到一秒截止后中断并死亡，人工恢复成功而不覆盖原超时历史。 */
    @Test
    void replaysActuallyTimedOutExecution() throws Exception {
        probe.block = true;
        context(); context();
        var id = register(1, 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> "DEAD".equals(state(id)));
        assertEquals(List.of("TIMED_OUT"), results(id));
        probe.block = false;
        jobs.replay(replayRequest(id));
        await().atMost(Duration.ofSeconds(10)).until(() -> "SUCCEEDED".equals(state(id)));
        assertEquals(List.of("TIMED_OUT", "SUCCEEDED"), results(id));
        assertEquals(2, probe.calls.get());
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT state FROM dead_job WHERE execution_id=?", String.class, id.toString()));
    }

    /** 强制结束仅由本用例启动的真实JVM，存活节点等待真实ShedLock和执行租约到期后记录遗失并重试。 */
    @Test
    void recoversAfterOwnedProcessActuallyDies() throws Exception {
        var id = register(2, 3);
        try (var process = JobCrashProcess.start(probe.code, name)) {
            await().atMost(Duration.ofSeconds(45)).until(() -> {
                assertTrue(process.isAlive(), "独立任务JVM未能保持运行，检查本次临时进程日志");
                return process.started(id) && "RUNNING".equals(state(id));
            });
            process.crash();
            assertFalse(process.isAlive());
            assertEquals("RUNNING", state(id));
            context(); context();
            await().pollInterval(Duration.ofMillis(500)).atMost(Duration.ofSeconds(95))
                    .until(() -> "SUCCEEDED".equals(state(id)));
            assertEquals(List.of("LEASE_EXPIRED", "SUCCEEDED"), results(id));
            assertEquals(1, probe.calls.get());
            assertEquals(1, probe.maximum.get());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE execution_id=?", Integer.class, id.toString()));
        }
    }

    /**
     * 读取当前唯一死亡目标，创建独立幂等恢复请求。
     * @param id 自身执行身份
     * @return 保留原执行身份的恢复请求
     */
    private ReplayRequest replayRequest(UUID id) {
        return new ReplayRequest(UUID.randomUUID(), UUID.fromString(jdbc.queryForObject(
                "SELECT dead_id FROM dead_job WHERE execution_id=? AND state='DEAD'", String.class, id.toString())), "recovered-trigger-trace");
    }

    /**
     * 按累计尝试顺序查询自身固定技术结果。
     * @param id 原执行身份
     * @return 保留历史的结果列表
     */
    private List<String> results(UUID id) {
        return jdbc.queryForList("SELECT result FROM job_attempt WHERE execution_id=? ORDER BY attempt_no", String.class, id.toString());
    }

    /**
     * 构造完整生产Quartz固定触发装配；创建失败同样关闭已建立的实例。
     * @return 由本用例清理的独立上下文
     * @throws Exception Scheduler身份读取失败
     */
    private AnnotationConfigApplicationContext context() throws Exception {
        return context(false);
    }

    /**
     * 构造完整生产调度装配并可选创建待机Relay调度器。
     * @param relayEnabled 是否创建Relay调度资源
     * @return 由本用例清理的独立上下文
     * @throws Exception Scheduler身份读取失败
     */
    private AnnotationConfigApplicationContext context(boolean relayEnabled) throws Exception {
        return context(relayEnabled, false);
    }

    /**
     * 按测试参数创建真实调度装配和可选的固定Relay处理器。
     * @param relayEnabled 是否创建Relay调度器
     * @param relayHandler 是否登记Relay探针
     * @return 由本用例清理的独立上下文
     * @throws Exception Scheduler身份读取失败
     */
    private AnnotationConfigApplicationContext context(boolean relayEnabled, boolean relayHandler) throws Exception {
        var context = new AnnotationConfigApplicationContext(); contexts.add(context);
        context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("trigger-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", name, "stratum.jobs.workers", 2,
                "stratum.jobs.relay.enabled", relayEnabled)));
        context.registerBean(JobHandler.class, () -> probe);
        if (relayHandler) context.registerBean("relayTestHandler", JobHandler.class, () -> relayProbe);
        context.register(SchedulerConfiguration.class, JobRuntimeConfiguration.class, QuartzTriggerConfiguration.class);
        try {
            context.refresh();
            schedulerIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
            if (relayEnabled) schedulerIds.add(context.getBean("relayJobScheduler", Scheduler.class).getSchedulerInstanceId());
            return context;
        } catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /**
     * 登记一次性执行，处理资格和退避均由实际数据库维护。
     * @return 随机原执行UUID
     */
    private UUID register() {
        return register(30, 3);
    }

    /**
     * 登记有界超时和失败次数的一次性技术执行。
     * @param timeout 秒级处理预算
     * @param attempts 周期尝试上限
     * @return 原执行UUID
     */
    private UUID register(int timeout, int attempts) {
        Instant time = Instant.parse("2026-01-01T00:00:00Z"); UUID id = UUID.randomUUID();
        var source = definition(time);
        var input = new JobDefinition(source.jobCode(), source.jobType(), source.cron(), source.triggerAt(), timeout,
                new JobDefinition.RetryPolicy(attempts, source.retryPolicy().initialDelay(), source.retryPolicy().maxDelay()), source.idempotencyKeyRule());
        return jobs.register(new Submission(id, input, time, id.toString(), "{}", "trigger-trace"));
    }

    /**
     * 构造显式或周期定义，复用同一固定处理器代码。
     * @param time 空为周期，非空为一次性
     * @return 有界技术任务定义
     */
    private JobDefinition definition(Instant time) {
        return new JobDefinition(probe.code, time == null ? JobDefinition.Type.CRON : JobDefinition.Type.ONCE,
                time == null ? Optional.of("0/2 * * * * ?") : Optional.empty(), Optional.ofNullable(time), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1)),
                time == null ? JobDefinition.IdempotencyRule.SCHEDULE_TIME : JobDefinition.IdempotencyRule.EXPLICIT_KEY);
    }

    /**
     * 独立连接读取本用例执行状态。
     * @param id 原执行身份
     * @return 当前持久化状态
     */
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, id.toString()); }

    /** 固定Relay测试探针，仅记录线程和可中断的运行状态。 */
    private final class RelayProbe implements JobHandler {
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean periodic = true;
        private volatile boolean block;
        /** {@inheritDoc} */
        @Override public String jobCode() { return "outbox.relay"; }
        /** {@inheritDoc} */
        @Override public Optional<JobDefinition> periodicDefinition() {
            if (!periodic) return Optional.empty();
            return Optional.of(new JobDefinition("outbox.relay", JobDefinition.Type.CRON, Optional.of("0/5 * * * * ?"),
                    Optional.empty(), 30, new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                    JobDefinition.IdempotencyRule.SCHEDULE_TIME));
        }
        /** {@inheritDoc} */
        @Override public void execute(Submission submission) throws InterruptedException {
            threads.add(Thread.currentThread()); entered.countDown();
            if (block && !release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Relay探针等待超时");
        }
    }

    /** 只有实际调用次数、受控失败和中断阻塞，不替代生产调度器/锁/事务。 */
    private final class Probe implements JobHandler {
        private final String code;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean periodic;
        private volatile boolean block;

        /**
         * 绑定当前用例唯一代码。
         * @param code 随机固定代码
         */
        private Probe(String code) { this.code = code; }

        /** {@inheritDoc} */
        @Override
        public String jobCode() { return code; }

        /** {@inheritDoc} */
        @Override
        public Optional<JobDefinition> periodicDefinition() { return periodic ? Optional.of(definition(null)) : Optional.empty(); }

        /** {@inheritDoc} */
        @Override
        public void execute(Submission submission) throws InterruptedException {
            threads.add(Thread.currentThread()); calls.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            try {
                if (block) assertTrue(release.await(20, TimeUnit.SECONDS));
                if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) throw new IllegalStateException("controlled-trigger-failure");
            } finally { active.decrementAndGet(); }
        }
    }
}
