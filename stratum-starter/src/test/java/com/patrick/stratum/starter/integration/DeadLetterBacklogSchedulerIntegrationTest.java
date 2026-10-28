package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import com.patrick.stratum.infrastructure.messaging.DeadLetterBacklogMetricsConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration;
import com.patrick.stratum.job.DeadLetterBacklogJobConfiguration;
import com.patrick.stratum.job.JobRuntimeConfiguration;
import com.patrick.stratum.job.QuartzTriggerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库、Broker与Redis验证独立采集调度集群及禁用实例。 */
class DeadLetterBacklogSchedulerIntegrationTest extends MariaDbIntegrationTest {
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<String> SNAPSHOT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    local generation = redis.call('get', KEYS[1])
                    if not generation or generation == ARGV[1] then return '' end
                    if redis.call('pttl', KEYS[2]) <= 0 or redis.call('pttl', KEYS[3]) <= 0 then return '' end
                    if redis.call('get', KEYS[2]) ~= generation .. '|' .. ARGV[2] then return '' end
                    if redis.call('get', KEYS[3]) ~= generation .. '|' .. ARGV[3] then return '' end
                    return generation
                    """, String.class);
    private final ApplicationContext parent;
    private final JdbcTemplate jdbc;
    private final String schedulerName = "backlog-it-" + UUID.randomUUID();
    private final String namespace = "it-" + UUID.randomUUID();
    private final List<AnnotationConfigApplicationContext> instances = new ArrayList<>();
    private final List<String> schedulerIds = new ArrayList<>();

    /**
     * 使用独立已迁移数据库和父应用基础设施。
     * @param parent 父上下文
     * @param jdbc 测试数据库客户端
     */
    @Autowired DeadLetterBacklogSchedulerIntegrationTest(ApplicationContext parent, JdbcTemplate jdbc) {
        this.parent = parent; this.jdbc = jdbc;
    }

    /**
     * 两个采集实例竞争同一Quartz组，只读禁用实例读取共享结果；关闭一个后另一实例继续。
     * @throws Exception Broker、调度器或数据库失败
     */
    @Test void runsSharedSamplingAndSurvivesOneInstanceClosing() throws Exception {
        Set<String> before = new HashSet<>(jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code=?", String.class, "deadletter.backlog"));
        Set<String> locksBefore = new HashSet<>(jdbc.queryForList("SELECT name FROM stratum_shedlock WHERE name LIKE ?", String.class, "%deadletter.backlog"));
        assertTrue(before.isEmpty(), "本测试要求独占数据库内的采集执行身份");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM STRATUM_QRTZ_JOB_DETAILS WHERE JOB_NAME IN (?,?)", Integer.class,
                "run.deadletter.backlog", "schedule.deadletter.backlog"), "本测试要求没有其他采集调度目标");
        try (var broker = broker()) {
            var admin = broker.getBean(RabbitAdmin.class); var factory = broker.getBean(CachingConnectionFactory.class);
            try {
                try (var channel = factory.createConnection().createChannel(false)) {
                    channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "one".getBytes(StandardCharsets.UTF_8));
                    channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "two".getBytes(StandardCharsets.UTF_8));
                    channel.basicPublish(DEAD_EXCHANGE, CACHE_QUEUE, null, "cache".getBytes(StandardCharsets.UTF_8));
                }
                var first = instance(broker, true); var second = instance(broker, true); var disabled = instance(broker, false);
                assertFalse(disabled.containsLocalBean("deadLetterSamplingScheduler"));
                var one = first.getBean("deadLetterSamplingScheduler", Scheduler.class);
                var two = second.getBean("deadLetterSamplingScheduler", Scheduler.class);
                assertEquals("backlog:" + schedulerName, one.getSchedulerName()); assertEquals(one.getSchedulerName(), two.getSchedulerName());
                assertNotEquals(one.getSchedulerInstanceId(), two.getSchedulerInstanceId());
                var firstStore = first.getBean(DeadLetterBacklogStore.class); var disabledStore = disabled.getBean(DeadLetterBacklogStore.class);
                await().atMost(Duration.ofSeconds(30)).until(() -> firstStore.read(Group.ERP_AUDIT).orElse(-1) == 2
                        && disabledStore.read(Group.CACHE_INVALIDATION).orElse(-1) == 1 && succeeded() >= 1);
                var gauge = disabled.getBean(MeterRegistry.class).get("stratum.messaging.deadletter.visible").tag("group", "erp_audit").gauge();
                await().atMost(Duration.ofSeconds(5)).until(() -> gauge.value() == 2);
                first.close(); int beforeClose = succeeded();
                await().atMost(Duration.ofSeconds(20)).until(() -> succeeded() > beforeClose);
                var keys = jdbc.queryForList("SELECT idempotency_key FROM job_execution WHERE job_code=?", String.class, "deadletter.backlog");
                assertEquals(keys.size(), keys.stream().distinct().count());
                assertTrue(disabled.getBean(Scheduler.class).isStarted());
            } finally {
                cleanupResources(admin, before, locksBefore);
            }
        }
    }

    /**
     * 真实周期查询遇到队列缺失时仅该组不可用，恢复后刷新；采集停止后结果过期。
     * @throws Exception Broker、调度器或数据库失败
     */
    @Test void marksMissingQueueUnavailableAndRecoversThenExpires() throws Exception {
        Set<String> before = new HashSet<>(jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code=?", String.class, "deadletter.backlog"));
        Set<String> locksBefore = new HashSet<>(jdbc.queryForList("SELECT name FROM stratum_shedlock WHERE name LIKE ?", String.class, "%deadletter.backlog"));
        assertTrue(before.isEmpty(), "本测试要求独占数据库内的采集执行身份");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM STRATUM_QRTZ_JOB_DETAILS WHERE JOB_NAME IN (?,?)", Integer.class,
                "run.deadletter.backlog", "schedule.deadletter.backlog"), "本测试要求没有其他采集调度目标");
        try (var broker = broker()) {
            var admin = broker.getBean(RabbitAdmin.class); var factory = broker.getBean(CachingConnectionFactory.class);
            try {
                try (var channel = factory.createConnection().createChannel(false)) {
                    channel.basicPublish(DEAD_EXCHANGE, ERP_QUEUE, null, "erp".getBytes(StandardCharsets.UTF_8));
                    channel.basicPublish(DEAD_EXCHANGE, CACHE_QUEUE, null, "cache".getBytes(StandardCharsets.UTF_8));
                }
                var sampling = instance(broker, true, "4s"); var reader = instance(broker, false, "4s");
                var store = reader.getBean(DeadLetterBacklogStore.class);
                var registry = reader.getBean(PrometheusMeterRegistry.class);
                var initial = new java.util.concurrent.atomic.AtomicReference<String>();
                await().atMost(Duration.ofSeconds(25)).until(() -> {
                    initial.set(snapshot("", "1", "1")); return !initial.get().isEmpty();
                });
                assertTrue(admin.deleteQueue(ERP_QUEUE + ".dlq"));
                var failed = new java.util.concurrent.atomic.AtomicReference<String>();
                await().atMost(Duration.ofSeconds(20)).until(() -> {
                    failed.set(snapshot(initial.get(), "-1", "1")); return !failed.get().isEmpty();
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    String unavailable = registry.scrape();
                    return unavailable.contains("stratum_messaging_deadletter_visible{group=\"erp_audit\"} NaN")
                            && unavailable.contains("stratum_messaging_deadletter_visible{group=\"cache_invalidation\"} 1.0");
                });
                admin.initialize();
                await().atMost(Duration.ofSeconds(20)).until(() -> !snapshot(failed.get(), "0", "1").isEmpty());
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    String recovered = registry.scrape();
                    return recovered.contains("stratum_messaging_deadletter_visible{group=\"erp_audit\"} 0.0")
                            && recovered.contains("stratum_messaging_deadletter_visible{group=\"cache_invalidation\"} 1.0");
                });
                sampling.close();
                var redis = parent.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
                String prefix = namespace + ":{deadletter-backlog}:";
                await().atMost(Duration.ofSeconds(12)).until(() -> Boolean.FALSE.equals(redis.hasKey(prefix + "erp_audit"))
                        && Boolean.FALSE.equals(redis.hasKey(prefix + "cache_invalidation")));
                assertTrue(Boolean.TRUE.equals(redis.hasKey(prefix + "generation")));
                assertTrue(store.read(Group.ERP_AUDIT).isEmpty()); assertTrue(store.read(Group.CACHE_INVALIDATION).isEmpty());
                String expired = registry.scrape();
                assertTrue(expired.contains("stratum_messaging_deadletter_visible{group=\"erp_audit\"} NaN"));
                assertTrue(expired.contains("stratum_messaging_deadletter_visible{group=\"cache_invalidation\"} NaN"));
            } finally { cleanupResources(admin, before, locksBefore); }
        }
    }

    /**
     * 原子核验两个未过期快照属于同一新采样代号，避免采样中间态被误判。
     * @param previous 必须排除的旧代号，空串表示不排除
     * @param erp ERP组预期原始值
     * @param cache 缓存组预期原始值
     * @return 匹配时的代号，否则为空串
     */
    private String snapshot(String previous, String erp, String cache) {
        var redis = parent.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        String prefix = namespace + ":{deadletter-backlog}:";
        String result = redis.execute(SNAPSHOT, List.of(prefix + "generation", prefix + "erp_audit", prefix + "cache_invalidation"),
                previous, erp, cache);
        return result == null ? "" : result;
    }

    /**
     * 逐项关闭测试实例并清理自有资源，单项失败不跳过其余清理。
     * @param admin 隔离虚拟主机管理员
     * @param before 已存在执行身份
     * @param locksBefore 已存在锁名称
     */
    private void cleanupResources(RabbitAdmin admin, Set<String> before, Set<String> locksBefore) {
        var failures = new ArrayList<Throwable>();
        for (var instance : instances.reversed()) {
            try { instance.close(); } catch (RuntimeException failure) { failures.add(failure); }
        }
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.isAlive() && schedulerIds.stream().anyMatch(id -> thread.getName().contains(id))));
        } catch (RuntimeException | AssertionError failure) { failures.add(failure); }
        try { RabbitIntegrationSupport.clearTopology(admin); } catch (RuntimeException failure) { failures.add(failure); }
        try {
            var redis = parent.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
            String prefix = namespace + ":{deadletter-backlog}:";
            redis.delete(List.of(prefix + "generation", prefix + "erp_audit", prefix + "cache_invalidation"));
        } catch (RuntimeException failure) { failures.add(failure); }
        try { cleanup(before, locksBefore); } catch (RuntimeException failure) { failures.add(failure); }
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("采集调度测试资源清理失败");
            failures.forEach(failure::addSuppressed); throw failure;
        }
    }

    /**
     * 创建已核验独立虚拟主机的消息基础设施父上下文。
     * @return 调用方负责关闭的上下文
     */
    private AnnotationConfigApplicationContext broker() {
        var context = new AnnotationConfigApplicationContext(); context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-broker", RabbitIntegrationSupport.connectionProperties()));
        context.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /**
     * 创建独立Spring实例，关闭采集的实例仍能读取共享指标。
     * @param broker 已隔离消息父上下文
     * @param sampling 是否加入采集调度组
     * @return 必须关闭的子上下文
     * @throws Exception 调度身份读取失败
     */
    private AnnotationConfigApplicationContext instance(AnnotationConfigApplicationContext broker, boolean sampling) throws Exception {
        return instance(broker, sampling, "10s");
    }

    /**
     * 创建带指定有效期的独立调度实例。
     * @param broker 已隔离消息父上下文
     * @param sampling 是否加入采集调度组
     * @param ttl 有界测试快照有效期
     * @return 必须关闭的子上下文
     * @throws Exception 调度身份读取失败
     */
    private AnnotationConfigApplicationContext instance(AnnotationConfigApplicationContext broker, boolean sampling, String ttl) throws Exception {
        var context = new AnnotationConfigApplicationContext(); instances.add(context); context.setParent(broker);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("backlog-scheduler-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", schedulerName, "stratum.jobs.workers", 2,
                "stratum.jobs.deadletter-sampling.enabled", sampling, "stratum.jobs.deadletter-sampling.cron", "0/2 * * * * ?",
                "stratum.jobs.deadletter-sampling.ttl", ttl, "stratum.jobs.deadletter-sampling.timeout-sec", 20,
                "stratum.messaging.enabled", sampling, "stratum.observability.deadletter.enabled", true,
                "stratum.observability.deadletter.namespace", namespace)));
        context.registerBean("localMeterRegistry", PrometheusMeterRegistry.class,
                () -> new PrometheusMeterRegistry(PrometheusConfig.DEFAULT), definition -> definition.setPrimary(true));
        context.register(SchedulerConfiguration.class, JobRuntimeConfiguration.class, QuartzTriggerConfiguration.class,
                DeadLetterBacklogMetricsConfiguration.class, DeadLetterBacklogJobConfiguration.class);
        try {
            context.refresh(); schedulerIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
            if (sampling) schedulerIds.add(context.getBean("deadLetterSamplingScheduler", Scheduler.class).getSchedulerInstanceId());
            return context;
        } catch (Exception failure) { context.close(); throw failure; }
    }

    /**
     * 读取固定处理器成功执行数，不把任务成功解释为每组均可用。
     * @return 本隔离数据库中的成功数
     */
    private int succeeded() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE job_code=? AND state='SUCCEEDED'", Integer.class, "deadletter.backlog");
    }

    /**
     * 清理仅本测试新建的执行、锁及两个随机调度命名空间。
     * @param before 测试前已有执行身份集合
     * @param locksBefore 测试前已有锁名称集合
     */
    private void cleanup(Set<String> before, Set<String> locksBefore) {
        for (String id : jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code=?", String.class, "deadletter.backlog")) {
            if (before.contains(id)) continue;
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id);
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id);
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id);
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id);
        }
        for (String scheduler : List.of(schedulerName, "backlog:" + schedulerName)) {
            for (String table : List.of("STRATUM_QRTZ_SIMPLE_TRIGGERS", "STRATUM_QRTZ_CRON_TRIGGERS", "STRATUM_QRTZ_TRIGGERS",
                    "STRATUM_QRTZ_JOB_DETAILS", "STRATUM_QRTZ_FIRED_TRIGGERS", "STRATUM_QRTZ_LOCKS", "STRATUM_QRTZ_SCHEDULER_STATE")) {
                jdbc.update("DELETE FROM " + table + " WHERE SCHED_NAME=?", scheduler);
            }
        }
        for (String prefix : List.of("lock:job:", "lock:job:trigger:run.", "lock:job:trigger:schedule.")) {
            String lockName = prefix + "deadletter.backlog";
            if (!locksBefore.contains(lockName)) jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", lockName);
        }
    }
}
