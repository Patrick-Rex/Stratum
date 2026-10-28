package com.patrick.stratum.starter.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxConsumerConfiguration;
import com.patrick.stratum.job.QuartzRuntimeLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.DeserializationFeature;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.ERP_QUEUE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 独立数据库与Broker空间中验收真实HTTP、调度、发布和自动消费组合。 */
class DeadLetterReplayWorkflowIntegrationTest extends AdministrationHttpIntegrationTest {
    private static final String PATH = "/api/management/outbox/replay-tasks";
    private static final String SCHEDULER = "replay-http-" + UUID.randomUUID();
    @Autowired private UnitOfWork work;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxDeadLetterRepository deadLetters;
    @Autowired private RabbitAdmin rabbit;
    @Autowired private ApplicationContext parent;
    private final UUID eventId = UUID.randomUUID();
    private final UUID aggregateId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private UUID archiveId;
    private UUID taskId;
    private Set<String> existingRelayJobs;
    private boolean initialPause;

    /** 保存已有Relay任务与暂停状态，清理时仅回收本用例新增任务。 */
    @BeforeEach
    void snapshotRelay() {
        existingRelayJobs = new HashSet<>(jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code='outbox.relay'", String.class));
        initialPause = parent.getBean(OutboxRelayControlApplicationService.class).current().paused();
    }

    /**
     * 启用真实任务和发布，消费监听由测试在确认发布后启动以观察中间状态。
     * @param registry 测试配置注册器
     */
    @DynamicPropertySource
    static void configureWorkflow(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("stratum.jobs.enabled", () -> true);
        registry.add("stratum.jobs.name", () -> SCHEDULER);
        registry.add("stratum.jobs.replay.enabled", () -> true);
        registry.add("stratum.jobs.relay.enabled", () -> true);
        registry.add("stratum.jobs.relay.cron", () -> "0/1 * * * * ?");
        registry.add("stratum.messaging.consumer.enabled", () -> false);
    }

    /**
     * 停止本用例调度后，仅删除新增Relay任务、随机调度空间和业务数据。
     * @throws Exception 调度器退出失败
     */
    @AfterEach
    void cleanWorkflow() throws Exception {
        parent.getBean(QuartzRuntimeLifecycle.class).stop();
        for (var scheduler : parent.getBeansOfType(Scheduler.class).values()) scheduler.shutdown(true);
        var control = parent.getBean(OutboxRelayControlApplicationService.class);
        var state = control.current();
        if (state.paused() != initialPause) control.change(state.revision(), initialPause);
        if (existingRelayJobs != null) {
            for (String id : jdbc.queryForList("SELECT execution_id FROM job_execution WHERE job_code='outbox.relay'", String.class)) {
                if (existingRelayJobs.contains(id)) continue;
                jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", id);
                jdbc.update("DELETE FROM dead_job WHERE execution_id=?", id);
                jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", id);
                jdbc.update("DELETE FROM job_execution WHERE execution_id=?", id);
            }
        }
        for (String name : List.of(SCHEDULER, "relay:" + SCHEDULER)) {
            for (String table : List.of("SIMPLE_TRIGGERS", "CRON_TRIGGERS", "TRIGGERS", "JOB_DETAILS", "FIRED_TRIGGERS", "LOCKS", "SCHEDULER_STATE")) {
                jdbc.update("DELETE FROM STRATUM_QRTZ_" + table + " WHERE SCHED_NAME=?", name);
            }
        }
        if (archiveId != null) {
            jdbc.update("DELETE FROM outbox_replay_request WHERE dead_id=?", archiveId.toString());
            jdbc.update("DELETE FROM outbox_replay_delivery WHERE dead_id=?", archiveId.toString());
            jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id=?", archiveId.toString());
        }
        jdbc.update("DELETE FROM dead_letter_replay_task WHERE request_id=?", key.toString());
        if (taskId != null) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", taskId.toString());
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", taskId.toString());
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", taskId.toString());
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", taskId.toString());
        }
        jdbc.update("DELETE FROM outbox_consumption WHERE event_id=?", eventId.toString());
        jdbc.update("DELETE FROM outbox_event WHERE event_id=?", eventId.toString());
        jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id=?", aggregateId.toString());
        RabbitIntegrationSupport.clearTopology(rabbit);
    }

    /**
     * HTTP登记经真实Quartz执行、Relay发布到RabbitMQ，自动消费前后状态符合凭据事实。
     * @throws Exception HTTP、监听或线程退出失败
     */
    @Test
    void completesOnlyAfterRealBrokerConsumption() throws Exception {
        var event = new OutboxMessage(eventId.toString(), "erp.probe.created", aggregateId.toString(),
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"quantity\":12.50}", "original-replay-trace");
        var delivery = new Delivery(event, 1);
        work.execute(() -> { outbox.append(event); return null; });
        // 本用例构造原投递已结束及对应归档；重放本身使用真实调度和Broker。
        jdbc.update("UPDATE outbox_event SET status='SENT' WHERE event_id=?", eventId.toString());
        byte[] body = json.writeValueAsBytes(Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateId", event.aggregateId(),
                "occurredAt", event.occurredAt().toString(), "traceId", event.traceId(), "sequence", 1,
                "payload", json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(event.payload())));
        var letter = new OutboxDeadLetterRepository.Letter(UUID.randomUUID(), Group.ERP_AUDIT, body, "{}", Optional.of(delivery),
                OutboxDeadLetterRepository.Category.RETRIES_EXHAUSTED, 6);
        archiveId = work.execute(() -> deadLetters.append(letter));

        var created = register(); taskId = UUID.fromString(data(created, 202).path("taskId").asText());
        assertEquals(PATH + "/" + taskId, created.headers().firstValue("Location").orElseThrow());
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT state FROM job_execution WHERE execution_id=?", String.class, taskId.toString())));
        assertEquals("RUNNING", status()); assertEquals(0, receiptCount());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_replay_delivery WHERE dead_id=?", String.class, archiveId.toString()));

        var control = data(request("GET", "/api/management/outbox/relay", accessToken(), null), 200);
        assertTrue(control.path("paused").asBoolean());
        data(request("PUT", "/api/management/outbox/relay", accessToken(), Map.of("revision", control.path("revision").asLong(), "paused", false)), 200);
        await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(jdbc.queryForObject(
                "SELECT status FROM outbox_replay_delivery WHERE dead_id=?", String.class, archiveId.toString())));
        assertEquals("RUNNING", status()); assertEquals(0, receiptCount());
        assertTrue(rabbit.getQueueInfo(ERP_QUEUE).getMessageCount() >= 1);

        try (var listeners = new AnnotationConfigApplicationContext()) {
            listeners.setParent(parent);
            listeners.getEnvironment().getPropertySources().addFirst(new MapPropertySource("workflow-consumer", Map.of(
                    "stratum.messaging.enabled", true, "stratum.messaging.consumer.enabled", true)));
            listeners.register(RabbitOutboxConsumerConfiguration.class); listeners.refresh();
            var pool = listeners.getBean("rabbitConsumerExecutor", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
            assertTrue(listeners.getBean("erpOutboxListener", SimpleMessageListenerContainer.class).isRunning());
            await().atMost(Duration.ofSeconds(30)).until(() -> receiptCount() == 1);
            await().atMost(Duration.ofSeconds(10)).until(() -> "SUCCEEDED".equals(status()));
            var row = data(request("GET", PATH + "/" + taskId, accessToken(), null), 200);
            assertEquals(100, row.path("progress").asInt()); assertTrue(row.path("lastError").isNull());
            assertEquals("workflow-http", row.path("traceId").asText());
            assertEquals("ERP_AUDIT", jdbc.queryForObject("SELECT consumer_group FROM outbox_consumption WHERE event_id=?", String.class, eventId.toString()));
            assertEquals("original-replay-trace", jdbc.queryForObject("SELECT trace_id FROM outbox_consumption WHERE event_id=?", String.class, eventId.toString()));
            assertEquals(1L, jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id=?", Long.class, aggregateId.toString()));
            var active = data(request("GET", "/api/management/outbox/relay", accessToken(), null), 200);
            data(request("PUT", "/api/management/outbox/relay", accessToken(), Map.of("revision", active.path("revision").asLong(), "paused", true)), 200);
            listeners.close(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 发送带独立幂等身份的真实登记请求。
     * @return HTTP响应
     * @throws Exception 网络失败
     */
    private HttpResponse<String> register() throws Exception {
        var request = HttpRequest.newBuilder(endpoint(PATH)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-API-Version", "1")
                .header("X-Trace-Id", "workflow-http").header("Idempotency-Key", key.toString())
                .header("Authorization", "Bearer " + accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("archiveId", archiveId)))).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 从管理HTTP读取实际任务状态。
     * @return 状态名称
     * @throws Exception HTTP通信失败
     */
    private String status() throws Exception { return data(request("GET", PATH + "/" + taskId, accessToken(), null), 200).path("status").asText(); }

    /**
     * 读取本测试事件的消费凭据数量。
     * @return 凭据数量
     */
    private int receiptCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id=?", Integer.class, eventId.toString()); }
}
