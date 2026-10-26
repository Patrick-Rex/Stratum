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
import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import com.patrick.stratum.job.JobRuntimeConfiguration;
import com.patrick.stratum.job.QuartzDispatchJob;
import com.patrick.stratum.job.QuartzTriggerConfiguration;
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
        probe.release.countDown();
        contexts.reversed().forEach(AnnotationConfigApplicationContext::close);
        await().atMost(Duration.ofSeconds(5)).until(() -> probe.threads.stream().noneMatch(Thread::isAlive));
        await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && (t.getName().contains(name) || schedulerIds.stream().anyMatch(id -> t.getName().contains(id)))));
        var ids = jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code=?", String.class, probe.code);
        for (var id : ids) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id);
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id);
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id);
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id);
        }
        jdbc.update("DELETE FROM STRATUM_QRTZ_SIMPLE_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_CRON_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_JOB_DETAILS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_FIRED_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_LOCKS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_SCHEDULER_STATE WHERE SCHED_NAME=?", name);
        for (String prefix : List.of("lock:job:", "lock:job:trigger:run.", "lock:job:trigger:schedule.")) {
            jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", prefix + probe.code);
        }
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

    /**
     * 构造完整生产Quartz固定触发装配；创建失败同样关闭已建立的实例。
     * @return 由本用例清理的独立上下文
     * @throws Exception Scheduler身份读取失败
     */
    private AnnotationConfigApplicationContext context() throws Exception {
        var context = new AnnotationConfigApplicationContext(); contexts.add(context);
        context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("trigger-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", name, "stratum.jobs.workers", 2)));
        context.registerBean(JobHandler.class, () -> probe);
        context.register(SchedulerConfiguration.class, JobRuntimeConfiguration.class, QuartzTriggerConfiguration.class);
        try {
            context.refresh();
            schedulerIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
            return context;
        } catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /**
     * 登记一次性执行，处理资格和退避均由实际数据库维护。
     * @return 随机原执行UUID
     */
    private UUID register() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z"); UUID id = UUID.randomUUID();
        return jobs.register(new Submission(id, definition(time), time, id.toString(), "{}", "trigger-trace"));
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
