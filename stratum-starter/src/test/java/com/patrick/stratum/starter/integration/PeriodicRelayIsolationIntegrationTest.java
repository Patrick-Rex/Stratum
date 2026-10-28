package com.patrick.stratum.starter.integration;

import java.sql.SQLException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.base.cache.CacheInvalidation;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.infrastructure.job.SchedulerConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxPublisherConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxConsumerConfiguration;
import com.patrick.stratum.job.JobRuntimeConfiguration;
import com.patrick.stratum.job.OutboxRelayJobConfiguration;
import com.patrick.stratum.job.QuartzTriggerConfiguration;
import com.patrick.stratumtest.job.RelayCrashProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.listeners.JobListenerSupport;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.ERP_QUEUE;
import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.CACHE_QUEUE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 独占数据库与专用Broker中验证真实周期Relay的集群暂停、顺序和隔离。 */
@Tag("integration")
@ExtendWith({PeriodicRelayIsolationIntegrationTest.FixtureCleanup.class, SpringExtension.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PeriodicRelayIsolationIntegrationTest {
    private static IsolatedMariaDbFixture database;
    private static ConfigurableApplicationContext application;
    private final ApplicationContext parent;
    private final JdbcTemplate jdbc;
    private final UnitOfWork work;
    private final OutboxRepository outbox;
    private final OutboxConsumer consumer;
    private final OutboxRelayControlApplicationService control;
    private final JobExecutionApplicationService jobs;
    private final RabbitAdmin admin;
    private final CachingConnectionFactory factory;
    private final NormalProbe normal = new NormalProbe();
    private final List<AnnotationConfigApplicationContext> children = new ArrayList<>();
    private final List<String> schedulerIds = new ArrayList<>();
    private final String schedulerName = "relaytest-" + UUID.randomUUID();
    private BlockingPublisher publisher;
    private boolean verified;

    /**
     * 绑定独占上下文的实际业务与技术组件。
     * @param parent 独占数据库的Spring上下文
     * @param jdbc 数据库断言客户端
     * @param work Application工作单元
     * @param outbox 原事件追加端口
     * @param consumer 实际消费应用入口
     * @param control 共享暂停入口
     * @param jobs 任务执行入口
     * @param admin 专用Broker拓扑管理器
     * @param factory 专用Broker连接工厂
     * @param actualPublisher 真实发布端口
     */
    @Autowired
    PeriodicRelayIsolationIntegrationTest(ApplicationContext parent, JdbcTemplate jdbc, UnitOfWork work,
            OutboxRepository outbox, OutboxConsumer consumer, OutboxRelayControlApplicationService control,
            JobExecutionApplicationService jobs, RabbitAdmin admin, CachingConnectionFactory factory, OutboxPublisher actualPublisher) {
        this.parent = parent; application = (ConfigurableApplicationContext) parent;
        this.jdbc = jdbc; this.work = work; this.outbox = outbox; this.consumer = consumer; this.control = control;
        this.jobs = jobs; this.admin = admin; this.factory = factory; this.publisher = new BlockingPublisher(actualPublisher);
    }

    /**
     * 创建仅本测试拥有的随机数据库和已验证的专用Broker配置。
     * @param registry Spring测试属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (database == null) database = IsolatedMariaDbFixture.start();
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> required("STRATUM_IT_REDIS_PORT"));
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /**
     * 确认JDBC真实连接属于本类随机独占数据库，才允许整库范围的测试清理。
     * @throws SQLException 数据库元数据不可读
     */
    @BeforeEach
    void verifyIsolation() throws SQLException {
        String expected = database.jdbcUrl().substring(database.jdbcUrl().lastIndexOf('/') + 1);
        assertTrue(expected.matches("stratum_it_[a-f0-9]{32}"));
        try (var connection = jdbc.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL(); int query = url.indexOf('?');
            assertEquals(database.jdbcUrl(), query < 0 ? url : url.substring(0, query));
            assertEquals(expected, connection.getCatalog());
        }
        verified = true; admin.initialize();
    }

    /** 关闭本类创建的调度上下文，再清理独占库和隔离Broker拓扑。 */
    @AfterEach
    void cleanup() {
        publisher.release.countDown();
        RuntimeException closeFailure = null;
        for (var child : children.reversed()) {
            try { child.close(); }
            catch (RuntimeException failure) {
                if (closeFailure == null) closeFailure = failure; else closeFailure.addSuppressed(failure);
            }
        }
        if (closeFailure != null) throw closeFailure;
        await().atMost(Duration.ofSeconds(10)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && schedulerIds.stream().anyMatch(id -> thread.getName().contains(id))));
        if (!verified) return;
        try {
            var state = control.current();
            if (!state.paused()) control.change(state.revision(), true);
            jdbc.update("DELETE FROM job_replay_request"); jdbc.update("DELETE FROM dead_job");
            jdbc.update("DELETE FROM job_attempt"); jdbc.update("DELETE FROM job_execution");
            jdbc.update("DELETE FROM outbox_consumption"); jdbc.update("DELETE FROM outbox_event");
            jdbc.update("DELETE FROM outbox_aggregate_progress");
        } finally { RabbitIntegrationSupport.clearTopology(admin); }
    }

    /** Spring关闭其数据源之后，只移除本类创建的数据库容器。 */
    static final class FixtureCleanup implements AfterAllCallback {
        /** {@inheritDoc} */
        @Override public void afterAll(ExtensionContext context) {
            try { if (application != null && application.isActive()) application.close(); }
            finally { if (database != null) database.close(); }
        }
    }

    /**
     * 双Relay实例与禁用实例共享控制；暂停期间无计划，已准入批次完成，消费后按序继续。
     * @throws Exception 并发、数据库或Broker操作失败
     */
    @Test
    void coordinatesRealInstancesPauseAndOrderedPublication() throws Exception {
        var stale = events(1).getFirst();
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(new Delivery(stale, 1), Group.ERP_AUDIT));
        jdbc.update("UPDATE outbox_event SET status='PENDING',consumed_from_status=NULL WHERE event_id=?", stale.eventId());
        var pair = events(2);
        var initial = control.current(); assertTrue(initial.paused());
        var first = instance(true); var second = instance(true); var disabled = instance(false);
        assertNotSame(first.getBean(OutboxRelayApplicationService.class), second.getBean(OutboxRelayApplicationService.class));
        assertFalse(disabled.containsLocalBean("relayJobScheduler"));
        var relayOne = first.getBean("relayJobScheduler", Scheduler.class);
        var relayTwo = second.getBean("relayJobScheduler", Scheduler.class);
        assertEquals(relayOne.getSchedulerName(), relayTwo.getSchedulerName());
        var tick = new CountDownLatch(1); watch(relayOne, tick); watch(relayTwo, tick);
        assertTrue(tick.await(10, TimeUnit.SECONDS));
        assertEquals("PENDING", status(pair.getFirst()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE job_code='outbox.relay'", Integer.class));
        first.getBean(Scheduler.class).standby(); second.getBean(Scheduler.class).standby();
        UUID normalId = registerNormal();
        disabled.getBean(Scheduler.class).triggerJob(new JobKey("run." + normal.jobCode(), "stratum.fixed.jobs"));
        await().atMost(Duration.ofSeconds(25)).until(() -> "SUCCEEDED".equals(jobStatus(normalId)));
        assertEquals(1, normal.calls.get());

        var active = control.change(initial.revision(), false);
        try {
            assertTrue(publisher.entered.await(20, TimeUnit.SECONDS));
            var paused = control.change(active.revision(), true);
            assertThrows(StratumException.class, () -> control.change(active.revision(), false));
            assertEquals("CONSUMED", status(stale));
            assertEquals("PROCESSING", status(pair.getFirst())); assertEquals("PENDING", status(pair.get(1)));
            publisher.release.countDown();
            await().atMost(Duration.ofSeconds(15)).until(() -> "SENT".equals(status(pair.getFirst())));
            assertEquals(pair.getFirst().eventId(), readMessage());
            assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(new Delivery(pair.getFirst(), 1), Group.ERP_AUDIT));
            var pausedTick = new CountDownLatch(1); watch(relayOne, pausedTick); watch(relayTwo, pausedTick);
            assertTrue(pausedTick.await(10, TimeUnit.SECONDS)); assertEquals("PENDING", status(pair.get(1)));
            control.change(paused.revision(), false);
            await().atMost(Duration.ofSeconds(15)).until(() -> "SENT".equals(status(pair.get(1))));
            assertEquals(pair.get(1).eventId(), readMessage());
        } finally { publisher.release.countDown(); }
    }

    /**
     * 关闭一个启用实例并重建后，持久暂停不能被启动配置覆盖，剩余启用实例恢复实际发布。
     * @throws Exception Quartz或Broker操作失败
     */
    @Test
    void keepsPauseAcrossRestartAndAllowsEnabledPeerToProgress() throws Exception {
        publisher.release.countDown();
        var first = instance(true); var second = instance(true); var disabled = instance(false);
        var relayOne = first.getBean("relayJobScheduler", Scheduler.class);
        var relayTwo = second.getBean("relayJobScheduler", Scheduler.class);
        String disabledId = disabled.getBean(Scheduler.class).getSchedulerInstanceId();
        assertNotEquals(disabledId, relayOne.getSchedulerInstanceId());
        assertNotEquals(disabledId, relayTwo.getSchedulerInstanceId());
        var initial = control.current(); var active = control.change(initial.revision(), false);
        var original = events(1).getFirst();
        await().atMost(Duration.ofSeconds(20)).until(() -> "SENT".equals(status(original)));
        assertEquals(original.eventId(), readMessage());
        var paused = control.change(active.revision(), true);
        first.close();
        var restarted = instance(true);
        assertEquals(paused, control.current());
        assertThrows(StratumException.class, () -> control.change(active.revision(), false));
        assertFalse(disabled.containsLocalBean("relayJobScheduler"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM STRATUM_QRTZ_SCHEDULER_STATE WHERE SCHED_NAME=? AND INSTANCE_NAME=?",
                Integer.class, relayTwo.getSchedulerName(), disabledId));
        var waiting = events(1).getFirst();
        var tick = new CountDownLatch(1); watch(relayTwo, tick); watch(restarted.getBean("relayJobScheduler", Scheduler.class), tick);
        assertTrue(tick.await(15, TimeUnit.SECONDS)); assertEquals("PENDING", status(waiting));
        control.change(paused.revision(), false);
        await().atMost(Duration.ofSeconds(35)).until(() -> "SENT".equals(status(waiting)));
        assertEquals(waiting.eventId(), readMessage());
    }

    /**
     * 独立JVM真实领取事件后死亡，暂停跨实例保持至真实租约到期；恢复后存活节点发布原事件。
     * @throws Exception 子进程、Quartz或Broker操作失败
     */
    @Test
    void recoversOriginalEventAfterRelayProcessDiesWhilePaused() throws Exception {
        publisher.release.countDown(); var event = events(1).getFirst();
        var initial = control.current(); assertTrue(initial.paused());
        try (var child = RelayCrashProcess.start(database.jdbcUrl(), database.username(), database.password(), schedulerName)) {
            var active = control.change(initial.revision(), false);
            await().atMost(Duration.ofSeconds(50)).until(() -> {
                assertTrue(child.isAlive(), "独立Relay进程提前退出，检查本次临时进程日志");
                return child.started(event.eventId()) && "PROCESSING".equals(status(event));
            });
            String lease = jdbc.queryForObject("SELECT lease_token FROM outbox_event WHERE event_id=?", String.class, event.eventId());
            assertNotNull(lease); var paused = control.change(active.revision(), true);
            var peer = instance(true); var disabled = instance(false);
            assertFalse(disabled.containsLocalBean("relayJobScheduler"));
            child.crash(); assertFalse(child.isAlive());
            var tick = new CountDownLatch(1); watch(peer.getBean("relayJobScheduler", Scheduler.class), tick);
            assertTrue(tick.await(30, TimeUnit.SECONDS)); assertEquals(paused, control.current());
            assertThrows(StratumException.class, () -> control.change(active.revision(), false));
            assertEquals("PROCESSING", status(event));
            assertEquals(lease, jdbc.queryForObject("SELECT lease_token FROM outbox_event WHERE event_id=?", String.class, event.eventId()));
            await().atMost(Duration.ofSeconds(140)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT lease_until <= UTC_TIMESTAMP(6) FROM outbox_event WHERE event_id=?", Boolean.class, event.eventId())));
            assertEquals("PROCESSING", status(event)); assertEquals(paused, control.current());
            control.change(paused.revision(), false);
            await().atMost(Duration.ofSeconds(80)).until(() -> "SENT".equals(status(event)));
            assertEquals(event.eventId(), readMessage());
        }
    }

    /**
     * 实际连接拒绝超过发布失败阈值仍可重试，Broker恢复后沿用原事件身份。
     * @throws Exception 本机端口或Broker操作失败
     */
    @Test
    void keepsRetryingActualConnectionFailureUntilBrokerRecovers() throws Exception {
        publisher.release.countDown(); var event = events(1).getFirst();
        try (var socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            try (var faulty = faultContext(socket.getLocalPort())) {
                publisher.override = faulty.getBean(OutboxPublisher.class);
                try {
                    instance(true); var state = control.current(); control.change(state.revision(), false);
                    for (int count = 1; count <= 6; count++) {
                        int expected = count;
                        await().atMost(Duration.ofSeconds(15)).until(() -> failures(event) >= expected);
                        assertEquals("PENDING", status(event));
                        jdbc.update("UPDATE outbox_event SET next_retry_at='2000-01-01 00:00:00' WHERE event_id=?", event.eventId());
                    }
                } finally { publisher.override = null; }
            }
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> "SENT".equals(status(event)));
        assertEquals(event.eventId(), readMessage()); assertTrue(failures(event) >= 6);
    }

    /**
     * 周期Relay通过真实Broker和自动监听完成顺序留痕及缓存版本屏障。
     * @throws Exception Quartz或监听器退出失败
     */
    @Test
    void automaticallyConsumesOrderedEventsAndAppliesCacheFence() throws Exception {
        publisher.release.countDown();
        var cache = parent.getBean(VersionedCache.class); var redis = parent.getBean(StringRedisTemplate.class);
        var policy = new VersionedCache.Policy(Duration.ofMinutes(5), Duration.ZERO);
        String key = "biz:erp:periodic:" + UUID.randomUUID();
        var pair = events(2);
        String payload = parent.getBean(JsonMapper.class).writeValueAsString(new CacheInvalidation(key, 2, policy.ttl().toMillis(), 0));
        var cacheEvent = new OutboxMessage(UUID.randomUUID().toString(), CacheInvalidation.EVENT_TYPE, UUID.randomUUID().toString(),
                Instant.parse("2026-10-26T10:00:00.123456Z"), payload, "periodic-relay-test");
        work.execute(() -> { outbox.append(cacheEvent); return null; });
        var listeners = new AnnotationConfigApplicationContext(); children.add(listeners);
        try {
            assertTrue(cache.putIfNewer(key, new VersionedCache.Value(1, "old"), policy));
            listeners.setParent(parent);
            listeners.getEnvironment().getPropertySources().addFirst(new MapPropertySource("periodic-consumer-test", Map.of(
                    "stratum.messaging.enabled", true, "stratum.messaging.consumer.enabled", true)));
            listeners.register(RabbitOutboxConsumerConfiguration.class); listeners.refresh();
            var pool = listeners.getBean("rabbitConsumerExecutor", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
            assertTrue(listeners.getBean("erpOutboxListener", SimpleMessageListenerContainer.class).isRunning());
            assertTrue(listeners.getBean("cacheOutboxListener", SimpleMessageListenerContainer.class).isRunning());
            var first = instance(true); var second = instance(true); var disabled = instance(false);
            assertFalse(disabled.containsLocalBean("relayJobScheduler"));
            var initial = control.current(); assertTrue(initial.paused());
            var tick = new CountDownLatch(1); watch(first.getBean("relayJobScheduler", Scheduler.class), tick);
            watch(second.getBean("relayJobScheduler", Scheduler.class), tick); assertTrue(tick.await(15, TimeUnit.SECONDS));
            assertEquals("PENDING", status(pair.getFirst())); assertEquals("PENDING", status(cacheEvent));
            assertEquals(0, receiptCount(pair.getFirst()) + receiptCount(pair.get(1)) + receiptCount(cacheEvent));
            var active = control.change(initial.revision(), false);
            await().atMost(Duration.ofSeconds(40)).until(() -> receiptCount(pair.getFirst()) == 1
                    && receiptCount(pair.get(1)) == 1 && receiptCount(cacheEvent) == 1);
            var paused = control.change(active.revision(), true);
            assertTrue(paused.paused());
            assertEquals(List.of(1L, 2L), jdbc.queryForList(
                    "SELECT event_sequence FROM outbox_consumption WHERE aggregate_id=? ORDER BY event_sequence", Long.class, pair.getFirst().aggregateId()));
            assertEquals("ERP_AUDIT", jdbc.queryForObject("SELECT consumer_group FROM outbox_consumption WHERE event_id=?", String.class, pair.getFirst().eventId()));
            assertEquals("CACHE_INVALIDATION", jdbc.queryForObject("SELECT consumer_group FROM outbox_consumption WHERE event_id=?", String.class, cacheEvent.eventId()));
            assertNull(redis.opsForValue().get(key));
            assertFalse(cache.putIfNewer(key, new VersionedCache.Value(1, "late"), policy));
            assertTrue(cache.putIfNewer(key, new VersionedCache.Value(3, "new"), policy));
            listeners.close(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, admin.getQueueInfo(ERP_QUEUE).getMessageCount());
            assertEquals(0, admin.getQueueInfo(CACHE_QUEUE).getMessageCount());
        } finally {
            try { listeners.close(); }
            finally { redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4))); }
        }
    }

    /**
     * 读取本测试事件的真实消费凭据数。
     * @param event 本次独占库事件
     * @return 消费记录数
     */
    private int receiptCount(OutboxMessage event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id=?", Integer.class, event.eventId());
    }

    /**
     * 创建只用于本机拒连故障的独立发布上下文，不更改真实Broker。
     * @param port 已绑定但未监听的本机端口
     * @return 调用方负责关闭的上下文
     */
    private AnnotationConfigApplicationContext faultContext(int port) {
        var properties = RabbitIntegrationSupport.connectionProperties(); properties.put("stratum.messaging.port", port);
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("periodic-publisher-fault", properties));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.register(RabbitMessagingConfiguration.class, RabbitOutboxPublisherConfiguration.class);
        try { context.refresh(); return context; }
        catch (RuntimeException failure) { context.close(); throw failure; }
    }

    /**
     * 创建独立Spring调度实例，每个启用实例具有自己的Relay应用服务。
     * @param relayEnabled 是否加入Relay调度组
     * @return 本测试负责关闭的上下文
     */
    private AnnotationConfigApplicationContext instance(boolean relayEnabled) {
        var context = new AnnotationConfigApplicationContext(); children.add(context); context.setParent(parent);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("periodic-relay-test", Map.of(
                "stratum.jobs.enabled", true, "stratum.jobs.name", schedulerName, "stratum.jobs.workers", 2,
                "stratum.jobs.relay.enabled", relayEnabled, "stratum.jobs.relay.cron", "0/1 * * * * ?",
                "stratum.jobs.relay.batch-size", 2, "stratum.jobs.relay.reconciliation-limit", 4,
                "stratum.messaging.enabled", true)));
        context.registerBean("normalProbe", JobHandler.class, () -> normal);
        if (relayEnabled) {
            context.registerBean("relayProbePublisher", OutboxPublisher.class, () -> publisher, definition -> definition.setPrimary(true));
            context.registerBean("outboxRelayApplicationService", OutboxRelayApplicationService.class,
                    () -> new OutboxRelayApplicationService(work, parent.getBean(OutboxRelayRepository.class), context.getBeanProvider(OutboxPublisher.class),
                            parent.getBean(com.patrick.stratum.common.trace.StandardTraceContext.class)));
            context.register(OutboxRelayJobConfiguration.class);
        }
        context.register(SchedulerConfiguration.class, JobRuntimeConfiguration.class, QuartzTriggerConfiguration.class);
        try {
            context.refresh(); schedulerIds.add(context.getBean(Scheduler.class).getSchedulerInstanceId());
            if (relayEnabled) schedulerIds.add(context.getBean("relayJobScheduler", Scheduler.class).getSchedulerInstanceId());
            return context;
        } catch (RuntimeException | SchedulerException failure) { context.close(); throw new IllegalStateException("隔离调度实例启动失败", failure); }
    }

    /**
     * 在同一事务创建指定数量的同聚合事件。
     * @param count 事件数量
     * @return 原信封顺序
     */
    private List<OutboxMessage> events(int count) {
        String aggregate = UUID.randomUUID().toString(); var values = new ArrayList<OutboxMessage>();
        for (int index = 0; index < count; index++) values.add(new OutboxMessage(UUID.randomUUID().toString(),
                "erp.probe.created", aggregate, Instant.parse("2026-10-26T10:00:00.123456Z"), "{}", "periodic-relay-test"));
        work.execute(() -> { values.forEach(outbox::append); return null; }); return values;
    }

    /**
     * 登记唯一普通任务以验证Relay禁用实例仍能执行。
     * @return 普通任务UUID
     */
    private UUID registerNormal() {
        UUID id = UUID.randomUUID(); Instant time = Instant.parse("2026-01-01T00:00:00Z");
        var definition = new JobDefinition(normal.jobCode(), JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return jobs.register(new Submission(id, definition, time, id.toString(), "{}", "normal-relay-test"));
    }

    /**
     * 监听一次真正的Relay轮询结束，用于验证暂停后仍有调度周期。
     * @param scheduler 本用例调度器
     * @param tick 轮询结束信号
     * @throws SchedulerException 监听器登记失败
     */
    private void watch(Scheduler scheduler, CountDownLatch tick) throws SchedulerException {
        scheduler.getListenerManager().addJobListener(new JobListenerSupport() {
            private final String name = "relay-test-" + UUID.randomUUID();
            /** {@inheritDoc} */
            @Override public String getName() { return name; }
            /** {@inheritDoc} */
            @Override public void jobWasExecuted(JobExecutionContext context, JobExecutionException failure) {
                if ("run.outbox.relay".equals(context.getJobDetail().getKey().getName())) tick.countDown();
            }
        });
    }

    /**
     * 从专用队列读取真实消息的原身份；数据库消费仍由显式应用入口完成。
     * @return 原事件标识
     * @throws Exception Broker读取失败
     */
    private String readMessage() throws Exception {
        try (var channel = factory.createConnection().createChannel(false)) {
            var message = channel.basicGet(ERP_QUEUE, true); assertNotNull(message);
            return message.getProps().getMessageId();
        }
    }

    /**
     * 读取本用例原事件状态。
     * @param event 原事件
     * @return 持久化状态
     */
    private String status(OutboxMessage event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, event.eventId()); }

    /**
     * 读取原事件累计发布失败次数。
     * @param event 本测试事件
     * @return 当前失败次数
     */
    private int failures(OutboxMessage event) {
        return jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE event_id=?", Integer.class, event.eventId());
    }

    /**
     * 读取本用例普通任务状态。
     * @param id 任务UUID
     * @return 持久化状态
     */
    private String jobStatus(UUID id) { return jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, id.toString()); }

    /**
     * 获取必须显式传入的本机测试配置。
     * @param key 环境变量名称
     * @return 非空值
     */
    private static String required(String key) {
        var value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException("缺少测试配置：" + key); return value;
    }

    /** 用真实发布器发送，首次调用可控等待，以验证暂停对已准入批次的影响。 */
    private static final class BlockingPublisher implements OutboxPublisher {
        private final OutboxPublisher delegate;
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile OutboxPublisher override;
        /**
         * 包装实际生产发布器，不改变消息身份。
         * @param delegate 当前专用Broker发布端口
         */
        private BlockingPublisher(OutboxPublisher delegate) { this.delegate = delegate; }
        /** {@inheritDoc} */
        @Override public PublishResult publish(Delivery delivery) {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("测试发布等待超时"); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); return PublishResult.UNAVAILABLE; }
            }
            var selected = override; return (selected == null ? delegate : selected).publish(delivery);
        }
    }

    /** 随机普通任务探针，只记录实际执行次数。 */
    private static final class NormalProbe implements JobHandler {
        private final String code = "normal." + UUID.randomUUID();
        private final AtomicInteger calls = new AtomicInteger();
        /** {@inheritDoc} */
        @Override public String jobCode() { return code; }
        /** {@inheritDoc} */
        @Override public void execute(Submission submission) { calls.incrementAndGet(); }
    }
}
