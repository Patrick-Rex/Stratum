package com.patrick.stratum.starter.integration;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxPublisherConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.ERP_QUEUE;
import static org.junit.jupiter.api.Assertions.*;

/** 专用数据库及Broker空间验证原事件故障恢复、管理员入口和真实发布顺序。 */
class OutboxRecoveryBrokerIntegrationTest extends AdministrationHttpIntegrationTest {
    @LocalServerPort private int upstreamPort;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UnitOfWork work;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxRelayRepository relay;
    @Autowired private OutboxRelayApplicationService runner;
    @Autowired private OutboxConsumer consumer;
    @Autowired private RabbitAdmin admin;
    @Autowired private CachingConnectionFactory factory;
    private final List<String> aggregates = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final OutboxRelayRepository.RetryPolicy policy =
            new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(1));

    /**
     * 启用严格校验过的本机随机Broker空间。
     * @param registry Spring测试属性注册器
     */
    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** 每项测试在专用虚拟主机中声明自身拓扑。 */
    @BeforeEach
    void prepareBroker() { admin.initialize(); }

    /** 仅清理本测试请求、事件、聚合和专用虚拟主机拓扑。 */
    @AfterEach
    void cleanupRecovery() {
        try {
            for (String id : requests) jdbc.update("DELETE FROM outbox_original_recovery_request WHERE request_id=?", id);
            for (String id : events) jdbc.update("DELETE FROM outbox_consumption WHERE event_id=?", id);
            for (String id : events) jdbc.update("DELETE FROM outbox_event WHERE event_id=?", id);
            for (String id : aggregates) jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id=?", id);
        } finally { RabbitIntegrationSupport.clearTopology(admin); }
    }

    /**
     * 真实拒连超过原阈值仍重试，恢复后以原身份发布并等待消费后序。
     * @throws Exception 网络或Broker操作失败
     */
    @Test
    void retriesActualUnavailableBeyondThresholdAndPreservesOrder() throws Exception {
        var pair = seed(2);
        try (var unavailablePort = new Socket()) {
            unavailablePort.bind(new InetSocketAddress("127.0.0.1", 0));
            try (var faulty = faultContext(unavailablePort.getLocalPort())) {
                var unavailable = faulty.getBean(OutboxRelayApplicationService.class);
                for (int attempt = 1; attempt <= 3; attempt++) {
                    assertEquals(1, unavailable.runBatch(claim(), policy));
                    assertEquals("PENDING", status(pair.getFirst()));
                    assertEquals(attempt, failures(pair.getFirst()));
                    assertEquals("PENDING", status(pair.get(1)));
                    ready(pair.getFirst());
                }
            }
        }
        assertEquals(1, runner.runBatch(claim(), policy));
        assertEquals("SENT", status(pair.getFirst()));
        assertEquals(pair.getFirst().eventId(), readMessage());
        assertEquals(0, runner.runBatch(claim(), policy));
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(new Delivery(pair.getFirst(), 1), Group.ERP_AUDIT));
        assertEquals(1, runner.runBatch(claim(), policy));
        assertEquals(pair.get(1).eventId(), readMessage());
    }

    /**
     * 真实不可路由导致FAILED，其他聚合继续；管理员通过HTTP恢复同一事件并按序消费。
     * @throws Exception HTTP或Broker操作失败
     */
    @Test
    void administratorRestoresUnroutableHeadWithoutSkippingSequence() throws Exception {
        var pair = seed(2); var key = UUID.randomUUID(); requests.add(key.toString());
        admin.deleteQueue(ERP_QUEUE);
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertEquals(1, runner.runBatch(claim(), policy));
            assertEquals(attempt, failures(pair.getFirst()));
            if (attempt == 1) { assertEquals("PENDING", status(pair.getFirst())); ready(pair.getFirst()); }
        }
        assertEquals("FAILED", status(pair.getFirst()));
        assertEquals("PENDING", status(pair.get(1)));
        admin.initialize();
        var other = seed(1).getFirst();
        assertEquals(1, runner.runBatch(claim(), policy));
        assertEquals(other.eventId(), readMessage());
        assertEquals("FAILED", status(pair.getFirst()));
        try (var gateway = GatewayProcessFixture.start(upstreamPort, redis)) {
            var response = recover(pair.getFirst(), key, gateway);
            assertEquals(200, response.statusCode());
            assertEquals("RECOVERED", json.readTree(response.body()).path("data").path("outcome").asText());
        }
        assertEquals(2, failures(pair.getFirst()));
        assertEquals(1, runner.runBatch(claim(), policy));
        assertEquals(pair.getFirst().eventId(), readMessage());
        assertEquals(0, runner.runBatch(claim(), policy));
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(new Delivery(pair.getFirst(), 1), Group.ERP_AUDIT));
        assertEquals(1, runner.runBatch(claim(), policy));
        assertEquals(pair.get(1).eventId(), readMessage());
    }

    /**
     * 构建连接本机拒连端口的独立发布上下文，关闭时不影响真实Broker连接。
     * @param port 本机未监听端口
     * @return 调用方负责关闭的上下文
     */
    private AnnotationConfigApplicationContext faultContext(int port) {
        var properties = RabbitIntegrationSupport.connectionProperties(); properties.put("stratum.messaging.port", port);
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery-fault", properties));
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
            context.register(RabbitMessagingConfiguration.class, RabbitOutboxPublisherConfiguration.class);
            context.registerBean(UnitOfWork.class, () -> work);
            context.registerBean(OutboxRelayRepository.class, () -> relay);
            context.register(OutboxRelayApplicationService.class); context.refresh(); return context;
        } catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }

    /**
     * 在一个真实工作单元建立同聚合事件。
     * @param count 事件数量
     * @return 按顺序排列的原信封
     */
    private List<OutboxMessage> seed(int count) {
        String aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate);
        var result = new ArrayList<OutboxMessage>();
        for (int index = 0; index < count; index++) {
            var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                    Instant.parse("2026-10-26T10:00:00.123456Z"), "{}", "broker-recovery-test");
            events.add(event.eventId()); result.add(event);
        }
        work.execute(() -> { result.forEach(outbox::append); return null; }); return result;
    }

    /**
     * 通过真实管理员HTTP入口恢复原事件。
     * @param event 原事件
     * @param request 请求UUID
     * @param gateway 当前用例独立网关进程
     * @return HTTP回执
     * @throws Exception HTTP通信失败
     */
    private HttpResponse<String> recover(OutboxMessage event, UUID request, GatewayProcessFixture gateway) throws Exception {
        var message = HttpRequest.newBuilder(gateway.endpoint("/api/management/outbox/events/" + event.eventId() + "/recoveries"))
                .timeout(Duration.ofSeconds(10)).header("X-API-Version", "1").header("Idempotency-Key", request.toString())
                .header("Authorization", "Bearer " + accessToken()).POST(HttpRequest.BodyPublishers.noBody()).build();
        try (var client = HttpClient.newHttpClient()) { return client.send(message, HttpResponse.BodyHandlers.ofString()); }
    }

    /**
     * 从独立队列读取并确认一个真实消息，不伪造数据库消费。
     * @return 消息原始身份
     * @throws Exception Broker读取失败
     */
    private String readMessage() throws Exception {
        try (var channel = factory.createConnection().createChannel(false)) {
            var message = channel.basicGet(ERP_QUEUE, true); assertNotNull(message);
            return message.getProps().getMessageId();
        }
    }

    /**
     * 构造单项领取配置。
     * @return 单项领取及30秒租期
     */
    private OutboxRelayRepository.ClaimRequest claim() { return new OutboxRelayRepository.ClaimRequest(1, Duration.ofSeconds(30)); }
    /**
     * 读取原事件状态。
     * @param event 原事件
     * @return 当前状态
     */
    private String status(OutboxMessage event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, event.eventId()); }
    /**
     * 读取累计发布失败次数。
     * @param event 原事件
     * @return 累计失败次数
     */
    private int failures(OutboxMessage event) { return jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE event_id=?", Integer.class, event.eventId()); }
    /**
     * 将本用例重试时刻设为到期。
     * @param event 原事件
     */
    private void ready(OutboxMessage event) { jdbc.update("UPDATE outbox_event SET next_retry_at='2000-01-01 00:00:00' WHERE event_id=?", event.eventId()); }
}
