package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MariaDB上验证生产调度装配、共享JDBC存储与ShedLock代理，所有探针只使用随机命名空间。 */
class SchedulerConfigurationIntegrationTest extends MariaDbIntegrationTest {
    private final ApplicationContext parent;
    private final JdbcTemplate jdbc;
    private final List<String> instanceIds = new ArrayList<>();

    /**
     * 使用已执行Flyway的父上下文和独立断言客户端。
     * @param parent 真实应用上下文
     * @param jdbc 技术表断言及随机数据清理客户端
     */
    @Autowired
    SchedulerConfigurationIntegrationTest(ApplicationContext parent, JdbcTemplate jdbc) {
        this.parent = parent;
        this.jdbc = jdbc;
    }

    /** 默认开关关闭时完整Starter及显式配置都没有调度器、锁提供者或工作线程。 */
    @Test
    void disabledConfigurationHasNoResources() {
        assertTrue(parent.getBeansOfType(Scheduler.class).isEmpty());
        assertTrue(parent.getBeansOfType(LockProvider.class).isEmpty());
        try (var context = new AnnotationConfigApplicationContext(SchedulerConfiguration.class)) {
            assertTrue(context.getBeansOfType(Scheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(LockProvider.class).isEmpty());
        }
    }

    /** 非法命名空间和无界线程容量在创建任何运行资源前拒绝。 */
    @Test
    void rejectsInvalidProperties() {
        assertThrows(IllegalArgumentException.class, () -> new SchedulerConfiguration.Properties("bad\nname", 1));
        assertThrows(IllegalArgumentException.class, () -> new SchedulerConfiguration.Properties("a".repeat(65), 1));
        assertThrows(IllegalArgumentException.class, () -> new SchedulerConfiguration.Properties("relay:ordinary", 1));
        assertEquals(64, new SchedulerConfiguration.Properties("a".repeat(64), 1).name().length());
        assertThrows(IllegalArgumentException.class, () -> new SchedulerConfiguration.Properties("probe", 0));
        assertThrows(IllegalArgumentException.class, () -> new SchedulerConfiguration.Properties("probe", 9));
    }

    /** 两个独立调度器从同一JobStore只领取一次触发；重建上下文仍可读到持久化Job。 */
    @Test
    void sharesClusterStoreAndSurvivesRestart() throws Exception {
        String name = name();
        var state = new ProbeState();
        JobKey key = new JobKey(UUID.randomUUID().toString(), "test");
        try {
            try (var first = context(name, state); var second = context(name, state)) {
                var one = first.getBean(Scheduler.class);
                var two = second.getBean(Scheduler.class);
                assertNotSame(one, two);
                assertNotEquals(one.getSchedulerInstanceId(), two.getSchedulerInstanceId());
                assertTrue(one.getMetaData().isJobStoreClustered());
                assertTrue(one.getMetaData().isJobStoreSupportsPersistence());
                one.addJob(JobBuilder.newJob(ProbeJob.class).withIdentity(key).storeDurably().build(), false);
                assertEquals(ProbeJob.class, two.getJobDetail(key).getJobClass());
                one.scheduleJob(TriggerBuilder.newTrigger().withIdentity("once", "test").forJob(key)
                        .startAt(new Date(System.currentTimeMillis() + 300)).build());
                assertTrue(state.done.await(10, TimeUnit.SECONDS));
                one.standby(); two.standby();
                assertEquals(1, state.calls.get());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stratum_shedlock WHERE name=?",
                        Integer.class, "lock:job:" + key.getName()));
            }
            try (var restarted = context(name, state)) {
                var scheduler = restarted.getBean(Scheduler.class);
                assertNotNull(scheduler.getJobDetail(key));
                scheduler.deleteJob(key);
            }
            awaitStopped(name);
        } finally {
            cleanup(name, key);
        }
    }

    /**
     * Relay集群使用独立持久命名空间，禁用实例只运行普通调度，两个启用实例共享Relay任务。
     * @throws Exception 调度器启动、执行或关闭失败
     */
    @Test
    void isolatesRelayClusterAndPreservesOrdinaryDisabledInstance() throws Exception {
        String name = name(); String relayName = "relay:" + name;
        var relayState = new ProbeState(); var normalState = new ProbeState();
        JobKey relayKey = new JobKey(UUID.randomUUID().toString(), "test");
        JobKey normalKey = new JobKey(UUID.randomUUID().toString(), "test");
        try {
            try (var first = context(name, relayState, true); var second = context(name, relayState, true);
                    var disabled = context(name, normalState, false)) {
                var relayOne = first.getBean("relayJobScheduler", Scheduler.class);
                var relayTwo = second.getBean("relayJobScheduler", Scheduler.class);
                var ordinaryOne = first.getBean(Scheduler.class);
                var ordinaryTwo = second.getBean(Scheduler.class);
                var ordinaryDisabled = disabled.getBean(Scheduler.class);
                assertEquals(name, ordinaryDisabled.getSchedulerName());
                assertEquals(relayName, relayOne.getSchedulerName()); assertEquals(relayName, relayTwo.getSchedulerName());
                assertTrue(relayOne.isInStandbyMode()); assertTrue(relayTwo.isInStandbyMode());
                assertFalse(disabled.containsBean("relayJobScheduler"));
                assertTrue(relayOne.getMetaData().isJobStoreClustered());
                assertTrue(relayOne.getMetaData().isJobStoreSupportsPersistence());
                relayOne.addJob(JobBuilder.newJob(ProbeJob.class).withIdentity(relayKey).storeDurably().build(), false);
                assertNotNull(relayTwo.getJobDetail(relayKey)); assertNull(ordinaryDisabled.getJobDetail(relayKey));
                relayOne.start(); relayTwo.start();
                relayOne.scheduleJob(TriggerBuilder.newTrigger().withIdentity("once-relay", "test").forJob(relayKey)
                        .startAt(new Date(System.currentTimeMillis() + 300)).build());
                assertTrue(relayState.done.await(10, TimeUnit.SECONDS)); assertEquals(1, relayState.calls.get());
                ordinaryOne.standby(); ordinaryTwo.standby();
                ordinaryDisabled.addJob(JobBuilder.newJob(ProbeJob.class).withIdentity(normalKey).storeDurably().build(), false);
                ordinaryDisabled.scheduleJob(TriggerBuilder.newTrigger().withIdentity("once-normal", "test").forJob(normalKey)
                        .startAt(new Date(System.currentTimeMillis() + 300)).build());
                assertTrue(normalState.done.await(10, TimeUnit.SECONDS)); assertEquals(1, normalState.calls.get());
            }
            try (var restarted = context(name, relayState, true)) {
                var relay = restarted.getBean("relayJobScheduler", Scheduler.class);
                assertTrue(relay.isInStandbyMode()); assertNotNull(relay.getJobDetail(relayKey));
                relay.deleteJob(relayKey);
                restarted.getBean(Scheduler.class).deleteJob(normalKey);
            }
            awaitStopped(name);
        } finally {
            cleanup(relayName, relayKey); cleanup(name, normalKey);
        }
    }

    /**
     * 采集组只加入显式启用的实例，不与普通及Relay调度器共享持久目标。
     * @throws Exception 调度器或数据库操作失败
     */
    @Test
    void isolatesStandbyBacklogSchedulerFromOtherInstances() throws Exception {
        String name = name(); String backlogName = "backlog:" + name;
        var state = new ProbeState(); JobKey key = new JobKey(UUID.randomUUID().toString(), "test");
        try {
            try (var first = context(name, state, true, true); var second = context(name, state, true, true);
                    var disabled = context(name, state, false, false)) {
                var one = first.getBean("deadLetterSamplingScheduler", Scheduler.class);
                var two = second.getBean("deadLetterSamplingScheduler", Scheduler.class);
                assertEquals(backlogName, one.getSchedulerName()); assertEquals(backlogName, two.getSchedulerName());
                assertNotEquals(one.getSchedulerInstanceId(), two.getSchedulerInstanceId());
                assertTrue(one.isInStandbyMode()); assertTrue(two.isInStandbyMode());
                assertTrue(one.getMetaData().isJobStoreClustered()); assertTrue(one.getMetaData().isJobStoreSupportsPersistence());
                assertFalse(disabled.containsLocalBean("deadLetterSamplingScheduler"));
                one.addJob(JobBuilder.newJob(ProbeJob.class).withIdentity(key).storeDurably().build(), false);
                assertNotNull(two.getJobDetail(key)); assertNull(disabled.getBean(Scheduler.class).getJobDetail(key));
                assertNull(first.getBean("relayJobScheduler", Scheduler.class).getJobDetail(key));
            }
            try (var restarted = context(name, state, false, true)) {
                var backlog = restarted.getBean("deadLetterSamplingScheduler", Scheduler.class);
                assertTrue(backlog.isInStandbyMode()); assertNotNull(backlog.getJobDetail(key)); backlog.deleteJob(key);
            }
            awaitStopped(name);
        } finally { cleanup(backlogName, key); cleanup("relay:" + name, key); cleanup(name, key); }
    }

    /** 两个Spring代理竞争同一数据库锁，未取得锁的调用跳过；持有者退出后锁可再次取得。 */
    @Test
    void appliesDatabaseLockAcrossIndependentSpringProxies() throws Exception {
        String name = name();
        var state = new ProbeState();
        state.release = new CountDownLatch(1);
        JobKey key = new JobKey(UUID.randomUUID().toString(), "test");
        var execution = mock(JobExecutionContext.class);
        when(execution.getJobDetail()).thenReturn(JobBuilder.newJob(ProbeJob.class).withIdentity(key).build());
        try {
            try (var first = context(name, state); var second = context(name, state);
                    var worker = Executors.newSingleThreadExecutor()) {
                var running = worker.submit(() -> {
                    first.getBean(ProbeJob.class).execute(execution);
                    return null;
                });
                try {
                    assertTrue(state.entered.await(5, TimeUnit.SECONDS));
                    second.getBean(ProbeJob.class).execute(execution);
                    assertEquals(1, state.calls.get());
                } finally {
                    state.release.countDown();
                }
                running.get(5, TimeUnit.SECONDS);
                second.getBean(ProbeJob.class).execute(execution);
                assertEquals(2, state.calls.get());
            }
            awaitStopped(name);
        } finally {
            state.release.countDown();
            cleanup(name, key);
        }
    }

    /**
     * 按生产配置构造独立调度器，父上下文仅共享数据源等基础资源。
     * @param name 测试唯一集群名称
     * @param state 跨探针的测试同步信号，不用于分布式互斥
     * @return 已启动且必须关闭的子上下文
     */
    private AnnotationConfigApplicationContext context(String name, ProbeState state) {
        return context(name, state, false);
    }

    /**
     * 构造独立上下文，并按测试参数选择是否创建Relay调度器。
     * @param name 普通调度器唯一名称
     * @param state 本实例探针状态
     * @param relayEnabled 是否启用Relay调度资源
     * @return 调用方负责关闭的上下文
     */
    private AnnotationConfigApplicationContext context(String name, ProbeState state, boolean relayEnabled) {
        return context(name, state, relayEnabled, false);
    }

    /**
     * 通过独立开关选择待机的采集调度器，调用方负责关闭。
     * @param name 普通调度器唯一名称
     * @param state 本实例探针状态
     * @param relayEnabled 是否启用Relay资源
     * @param backlogEnabled 是否创建采集调度器
     * @return 测试子上下文
     */
    private AnnotationConfigApplicationContext context(String name, ProbeState state, boolean relayEnabled, boolean backlogEnabled) {
        var context = new AnnotationConfigApplicationContext();
        context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scheduler-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", name, "stratum.jobs.workers", 1,
                "stratum.jobs.relay.enabled", relayEnabled, "stratum.jobs.deadletter-sampling.enabled", backlogEnabled)));
        context.registerBean(ProbeJob.class, () -> new ProbeJob(state));
        context.register(SchedulerConfiguration.class);
        try {
            context.refresh();
            instanceIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
            if (relayEnabled) instanceIds.add(context.getBean("relayJobScheduler", Scheduler.class).getSchedulerInstanceId());
            if (backlogEnabled) instanceIds.add(context.getBean("deadLetterSamplingScheduler", Scheduler.class).getSchedulerInstanceId());
            return context;
        } catch (Exception failure) {
            context.close();
            throw new IllegalStateException("调度测试上下文启动失败", failure);
        }
    }

    /**
     * 等待该命名空间的调度/集群线程退出；有明确五秒预算。
     * @param name 测试唯一集群名称
     */
    private void awaitStopped(String name) {
        await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && (thread.getName().contains(name)
                        || instanceIds.stream().anyMatch(id -> thread.getName().contains(id)))));
    }

    /**
     * 清理自身命名空间，不删除任何其他调度器和生产锁。
     * @param name 测试唯一集群名称
     * @param key 测试作业键
     */
    private void cleanup(String name, JobKey key) {
        jdbc.update("DELETE FROM STRATUM_QRTZ_SIMPLE_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_JOB_DETAILS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_FIRED_TRIGGERS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_LOCKS WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM STRATUM_QRTZ_SCHEDULER_STATE WHERE SCHED_NAME=?", name);
        jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", "lock:job:" + key.getName());
    }

    /**
     * 创建符合生产名称约束的随机命名空间。
     * @return 测试专用名称
     */
    private String name() {
        return "it-" + UUID.randomUUID();
    }

    /** 仅用于断言真实调度次数和有界等待，不替代数据库锁。 */
    private static final class ProbeState {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(0);
    }

    /** 测试用Spring组件，Quartz必须调用其ShedLock注解代理。 */
    public static class ProbeJob implements Job {
        private final ProbeState state;

        /**
         * 创建无业务副作用的调度探针。
         * @param state 本测试共享断言信号
         */
        ProbeJob(ProbeState state) {
            this.state = state;
        }

        /** {@inheritDoc} */
        @Override
        @SchedulerLock(name = "#{'lock:job:' + #p0.jobDetail.key.name}", lockAtMostFor = "PT30S")
        public void execute(JobExecutionContext context) throws JobExecutionException {
            LockAssert.assertLocked();
            state.calls.incrementAndGet();
            state.entered.countDown();
            try {
                if (!state.release.await(10, TimeUnit.SECONDS)) {
                    throw new JobExecutionException("测试等待超时");
                }
                state.done.countDown();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new JobExecutionException("测试作业被中断");
            }
        }
    }
}
