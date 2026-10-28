package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.messaging.RabbitMessagingConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxPublisherConfiguration;
import com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxPublisher.PublishResult.*;
import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 完整Starter与真实DB/Redis/Broker验证Relay；故障只注入本用例连接、租约或专用拓扑。 */
@Import(OutboxRelayIntegrationTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final OutboxRelayRepository relayRepository;
    private final OutboxRelayApplicationService runner;
    private final JdbcTemplate jdbc;
    private final RabbitAdmin admin;
    private final CachingConnectionFactory factory;
    private final RecordingPublisher recorder;
    private final StringRedisTemplate redis;
    private final StandardTraceContext standardTrace;
    private final List<String> aggregates = new ArrayList<>();
    private final OutboxRelayRepository.RetryPolicy policy =
            new OutboxRelayRepository.RetryPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(30));

    /**
     * 注入真实链路及独立断言客户端。
     * @param unitOfWork 工作单元
     * @param outbox 事件追加端口
     * @param relayRepository 租约端口
     * @param runner 实际批次编排
     * @param jdbc 独立数据库断言
     * @param admin 专用拓扑管理员
     * @param factory 主/发布连接
     * @param recorder 包装真实发布器的提交可见性探针
     * @param redis 本轮独立Redis
     * @param standardTrace 实际标准追踪适配器
     */
    @Autowired
    OutboxRelayIntegrationTest(UnitOfWork unitOfWork, OutboxRepository outbox, OutboxRelayRepository relayRepository,
            OutboxRelayApplicationService runner, JdbcTemplate jdbc, RabbitAdmin admin,
            CachingConnectionFactory factory, RecordingPublisher recorder, StringRedisTemplate redis, StandardTraceContext standardTrace) {
        this.unitOfWork = unitOfWork; this.outbox = outbox; this.relayRepository = relayRepository;
        this.runner = runner; this.jdbc = jdbc; this.admin = admin; this.factory = factory;
        this.recorder = recorder; this.redis = redis; this.standardTrace = standardTrace;
    }

    /**
     * 只为本测试启用严格隔离的消息空间。
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        RabbitIntegrationSupport.connectionProperties().forEach((key, value) -> registry.add(key, () -> value));
    }

    /** 每用例恢复拓扑并实际PING独立Redis，清除仅测试使用的发布观察状态。 */
    @BeforeEach
    void prepare() {
        admin.initialize();
        recorder.ids.clear(); recorder.tokens.clear(); recorder.override = null; recorder.after = null;
        try (var connection = Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }

    /** 清理自身数据库事件/进度及独立空间拓扑，保持用户共享服务运行。 */
    @AfterEach
    void cleanup() {
        recorder.override = null; recorder.after = null;
        try {
            for (String aggregate : aggregates) {
                jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
                jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
            }
        } finally { RabbitIntegrationSupport.clearTopology(admin); }
    }

    /**
     * 实际发送已提交头事件并保留原值，Broker ACK本身不推进数据库消费进度。
     * @throws Exception AMQP读取失败
     */
    @Test
    void publishesCommittedHeadsAndWaitsForConsumptionEvidence() throws Exception {
        var first = seed(2); var other = seed(1);
        assertEquals(2, runner.runBatch(request(4), policy));
        assertEquals("SENT", status(first.getFirst()));
        assertEquals("PENDING", status(first.get(1)));
        assertEquals("SENT", status(other.getFirst()));
        var received = drain(factory, 2);
        assertEquals(2, received.size());
        for (var message : received) {
            var body = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build().readTree(message.getBody());
            assertEquals(message.getProps().getMessageId(), body.path("eventId").asText());
            assertEquals("relay-real-trace", body.path("traceId").asText());
            assertEquals(0, new BigDecimal("99999999999999999999999999.123456789012")
                    .compareTo(body.path("payload").path("quantity").decimalValue()));
        }
        assertEquals(0, runner.runBatch(request(4), policy));
        jdbc.update("UPDATE outbox_aggregate_progress SET consumed_sequence = 1 WHERE aggregate_id = ?",
                first.getFirst().aggregateId());
        assertEquals(1, runner.runBatch(request(4), policy));
        var last = drain(factory, 1).getFirst();
        assertEquals(first.get(1).eventId(), last.getProps().getMessageId());
        assertEquals(2L, ((Number) last.getProps().getHeaders().get("stratum-sequence")).longValue());
    }

    /**
     * 已落盘事件经过实际Relay及Broker后保留追踪头，历史事件不继承调用线程。
     * @throws Exception Broker读取失败
     */
    @Test
    void publishesPersistedTraceThroughRelayWithoutAgent() throws Exception {
        var traced = seed(1).getFirst(); var historical = seed(1).getFirst();
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
        String caller = "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01";
        jdbc.update("UPDATE outbox_event SET standard_traceparent=?,standard_tracestate=? WHERE event_id=?", parent, "vendor=value", traced.eventId());
        try (var ignored = standardTrace.restore(standardTrace.normalize(caller, null))) {
            assertEquals(2, runner.runBatch(request(2), policy));
            assertEquals(caller, standardTrace.capture().orElseThrow().traceparent());
        }
        var received = drain(factory, 2);
        assertEquals(2, received.size());
        var byId = received.stream().collect(java.util.stream.Collectors.toMap(message -> message.getProps().getMessageId(), message -> message));
        var tracedMessage = byId.get(traced.eventId()); var oldMessage = byId.get(historical.eventId());
        assertNotNull(tracedMessage); assertNotNull(oldMessage);
        assertEquals(parent, tracedMessage.getProps().getHeaders().get("traceparent").toString());
        assertEquals("vendor=value", tracedMessage.getProps().getHeaders().get("tracestate").toString());
        assertFalse(oldMessage.getProps().getHeaders().containsKey("traceparent"));
        assertFalse(oldMessage.getProps().getHeaders().containsKey("tracestate"));
        assertFalse(tracedMessage.getProps().getHeaders().containsKey("baggage"));
        assertEquals(7, JsonMapper.builder().build().readTree(tracedMessage.getBody()).size());
    }

    /** 发布、重试与历史事件逐条恢复原标准上下文，并在返回后恢复调用线程。 */
    @Test
    void isolatesEventContextsAndRestoresCallingThread() {
        var first = seed(1).getFirst(); var second = seed(1).getFirst(); var historical = seed(1).getFirst();
        String one = "00-11111111111111111111111111111111-1111111111111111-01";
        String two = "00-22222222222222222222222222222222-2222222222222222-01";
        String scheduler = "00-33333333333333333333333333333333-3333333333333333-01";
        jdbc.update("UPDATE outbox_event SET standard_traceparent=?,standard_tracestate=? WHERE event_id=?", one, "vendor=one", first.eventId());
        jdbc.update("UPDATE outbox_event SET standard_traceparent=?,standard_tracestate=? WHERE event_id=?", two, "vendor=two", second.eventId());
        var seen = new HashMap<String, Optional<StandardTraceContext.Carrier>>();
        recorder.override = delivery -> {
            assertEquals("relay-real-trace", MDC.get("businessTraceId"));
            seen.put(delivery.message().eventId(), standardTrace.capture()); return CONFIRMED;
        };
        MDC.put("businessTraceId", "scheduler-business");
        try (var ignored = standardTrace.restore(standardTrace.normalize(scheduler, "vendor=scheduler"))) {
            assertEquals(3, runner.runBatch(request(3), policy));
            assertEquals(one, seen.get(first.eventId()).orElseThrow().traceparent());
            assertEquals("vendor=one", seen.get(first.eventId()).orElseThrow().tracestate());
            assertEquals(two, seen.get(second.eventId()).orElseThrow().traceparent());
            assertEquals("vendor=two", seen.get(second.eventId()).orElseThrow().tracestate());
            assertTrue(seen.get(historical.eventId()).isEmpty());
            assertEquals(scheduler, standardTrace.capture().orElseThrow().traceparent());
            assertEquals("scheduler-business", MDC.get("businessTraceId"));
        } finally { MDC.remove("businessTraceId"); }
        assertTrue(standardTrace.capture().isEmpty());
    }

    /** 同一事件重试仍以原父上下文发布，异常分类与调用线程恢复保持。 */
    @Test
    void retriesFromOriginalContextEvenAfterPublisherFailure() {
        var event = seed(1).getFirst();
        String parent = "00-44444444444444444444444444444444-4444444444444444-01";
        jdbc.update("UPDATE outbox_event SET standard_traceparent=? WHERE event_id=?", parent, event.eventId());
        var seen = new ArrayList<Optional<StandardTraceContext.Carrier>>();
        recorder.override = delivery -> {
            seen.add(standardTrace.capture());
            if (seen.size() == 1) throw new IllegalStateException("private-publisher-error");
            return CONFIRMED;
        };
        assertEquals(1, runner.runBatch(request(1), policy)); assertEquals("PENDING", status(event));
        assertTrue(standardTrace.capture().isEmpty());
        ready(event); assertEquals(1, runner.runBatch(request(1), policy));
        assertEquals("SENT", status(event)); assertEquals(2, seen.size());
        assertEquals(parent, seen.getFirst().orElseThrow().traceparent()); assertEquals(seen.getFirst(), seen.getLast());
        assertTrue(standardTrace.capture().isEmpty());
    }

    /**
     * 两个Relay实例并发发布时各自使用事件上下文，退出后保留各自调用线程的上下文。
     * @throws Exception 并发测试超时或执行失败
     */
    @Test
    void isolatesContextsAcrossConcurrentRelayInstances() throws Exception {
        var first = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.EPOCH, "{}", "relay-real-trace");
        var second = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.EPOCH, "{}", "relay-real-trace");
        String one = "00-55555555555555555555555555555555-5555555555555555-01";
        String two = "00-66666666666666666666666666666666-6666666666666666-01";
        String caller = "00-77777777777777777777777777777777-7777777777777777-01";
        var seen = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var entered = new java.util.concurrent.CountDownLatch(2);
        OutboxPublisher publisher = delivery -> {
            seen.put(delivery.message().eventId(), standardTrace.capture().orElseThrow().traceparent());
            entered.countDown();
            try {
                if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("并发发布未就绪");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("并发发布中断", interrupted);
            }
            return CONFIRMED;
        };
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("publisher", publisher);
        var oneClaim = new OutboxRelayRepository.Claim(new OutboxPublisher.Delivery(first, 1), UUID.randomUUID(),
                Instant.now().plusSeconds(30), 0, standardTrace.normalize(one, null));
        var twoClaim = new OutboxRelayRepository.Claim(new OutboxPublisher.Delivery(second, 1), UUID.randomUUID(),
                Instant.now().plusSeconds(30), 0, standardTrace.normalize(two, null));
        var oneRunner = new OutboxRelayApplicationService(unitOfWork, new SingleClaim(oneClaim), beans.getBeanProvider(OutboxPublisher.class), standardTrace);
        var twoRunner = new OutboxRelayApplicationService(unitOfWork, new SingleClaim(twoClaim), beans.getBeanProvider(OutboxPublisher.class), standardTrace);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> runOne = () -> {
                try (var ignored = standardTrace.restore(standardTrace.normalize(caller, null))) {
                    int count = oneRunner.runBatch(request(1), policy);
                    assertEquals(caller, standardTrace.capture().orElseThrow().traceparent()); return count;
                }
            };
            java.util.concurrent.Callable<Integer> runTwo = () -> {
                try (var ignored = standardTrace.restore(standardTrace.normalize(caller, null))) {
                    int count = twoRunner.runBatch(request(1), policy);
                    assertEquals(caller, standardTrace.capture().orElseThrow().traceparent()); return count;
                }
            };
            var a = executor.submit(runOne); var b = executor.submit(runTwo);
            assertEquals(1, a.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, b.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(Map.of(first.eventId(), one, second.eventId(), two), seen);
        assertTrue(standardTrace.capture().isEmpty());
    }

    /** 发布中断时作用域仍关闭，租约留待到期恢复。 */
    @Test
    void restoresContextAfterPublisherInterruption() {
        var event = seed(1).getFirst();
        String parent = "00-88888888888888888888888888888888-8888888888888888-01";
        String caller = "00-99999999999999999999999999999999-9999999999999999-01";
        jdbc.update("UPDATE outbox_event SET standard_traceparent=? WHERE event_id=?", parent, event.eventId());
        recorder.override = delivery -> {
            assertEquals(parent, standardTrace.capture().orElseThrow().traceparent());
            Thread.currentThread().interrupt(); return UNAVAILABLE;
        };
        try (var ignored = standardTrace.restore(standardTrace.normalize(caller, null))) {
            try {
                assertEquals(0, runner.runBatch(request(1), policy));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(caller, standardTrace.capture().orElseThrow().traceparent());
            } finally { Thread.interrupted(); }
        }
        assertTrue(standardTrace.capture().isEmpty()); assertEquals("PROCESSING", status(event));
    }

    /** 恢复追踪失败时不发送、不推进状态，租约到期后仍发布原事件。 */
    @Test
    void leavesLeaseWhenTraceRestoreFailsAndRetriesAfterExpiry() {
        var event = seed(1).getFirst(); var next = seed(1).getFirst();
        String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-aaaaaaaaaaaaaaaa-01";
        jdbc.update("UPDATE outbox_event SET standard_traceparent=?,next_retry_at='2000-01-01 00:00:00' WHERE event_id=?", parent, event.eventId());
        jdbc.update("UPDATE outbox_event SET next_retry_at='2001-01-01 00:00:00' WHERE event_id=?", next.eventId());
        var broken = mock(StandardTraceContext.class);
        when(broken.restore(any())).thenThrow(new IllegalStateException("private-trace-failure"));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("publisher", recorder);
        var faulty = new OutboxRelayApplicationService(unitOfWork, relayRepository, beans.getBeanProvider(OutboxPublisher.class), broken);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OutboxRelayApplicationService.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        String previous = MDC.get("businessTraceId"); MDC.put("businessTraceId", "caller-business");
        try {
            assertEquals(0, faulty.runBatch(request(2), policy));
            assertEquals("caller-business", MDC.get("businessTraceId"));
            assertEquals("PROCESSING", status(event)); assertEquals(0, retryCount(event));
            assertEquals("PENDING", status(next)); assertTrue(recorder.ids.isEmpty());
            String token = jdbc.queryForObject("SELECT lease_token FROM outbox_event WHERE event_id=?", String.class, event.eventId());
            assertNotNull(token);
            var warnings = captured.list.stream().filter(log -> log.getFormattedMessage().contains("标准追踪作用域恢复失败")).toList();
            assertEquals(1, warnings.size()); assertNull(warnings.getFirst().getThrowableProxy());
            assertTrue(warnings.getFirst().getKeyValuePairs().stream().anyMatch(pair -> "errorType".equals(pair.key) && "IllegalStateException".equals(pair.value)));
            assertFalse(warnings.toString().contains("private-trace-failure"));
            jdbc.update("UPDATE outbox_event SET lease_until='2000-01-01 00:00:00' WHERE event_id=?", event.eventId());
            recorder.override = delivery -> {
                assertEquals(parent, standardTrace.capture().orElseThrow().traceparent()); return CONFIRMED;
            };
            assertEquals(1, runner.runBatch(request(1), policy));
            assertEquals("SENT", status(event)); assertEquals(List.of(event.eventId()), recorder.ids);
            assertNotEquals(token, recorder.tokens.getFirst()); assertEquals("caller-business", MDC.get("businessTraceId"));
        } finally {
            logger.detachAppender(captured); captured.stop();
            if (previous == null) MDC.remove("businessTraceId"); else MDC.put("businessTraceId", previous);
        }
    }

    /** 实际mandatory返回持久化重试；恢复拓扑后原事件及原序号再次发送成功。 */
    @Test
    void retriesUnroutableEventAfterTopologyRepair() {
        var event = seed(1).getFirst();
        factory.getPublisherConnectionFactory().createConnection();
        admin.deleteQueue(ERP_QUEUE);
        assertEquals(1, runner.runBatch(request(1), policy));
        assertEquals("PENDING", status(event));
        assertEquals(1, retryCount(event));
        admin.initialize();
        ready(event);
        assertEquals(1, runner.runBatch(request(1), policy));
        assertEquals("SENT", status(event));
        assertEquals(List.of(event.eventId(), event.eventId()), recorder.ids);
        assertEquals(1L, jdbc.queryForObject("SELECT event_sequence FROM outbox_event WHERE event_id = ?", Long.class, event.eventId()));
    }

    /**
     * 使用真实拒连端口制造发布失败，记录后恢复正常发布器并沿用同一身份。
     * @throws Exception 本机套接字不可用
     */
    @Test
    void retriesAfterActualConnectionRefusal() throws Exception {
        var event = seed(1).getFirst();
        try (var bound = new Socket()) {
            bound.bind(new InetSocketAddress("127.0.0.1", 0));
            try (var unavailable = messagingContext(Map.of("stratum.messaging.port", bound.getLocalPort()), false, false)) {
                recorder.override = unavailable.getBean(OutboxPublisher.class);
                try { assertEquals(1, runner.runBatch(request(1), policy)); }
                finally { recorder.override = null; }
            }
        }
        assertEquals("PENDING", status(event));
        assertEquals(1, retryCount(event));
        ready(event);
        assertEquals(1, runner.runBatch(request(1), policy));
        assertEquals("SENT", status(event));
        assertEquals(List.of(event.eventId(), event.eventId()), recorder.ids);
    }

    /**
     * 模拟确认后状态完成前租约已失效，接管后再次投递同一身份，展示至少一次语义。
     * @throws Exception AMQP读取失败
     */
    @Test
    void recoversExpiredLeaseAfterPublishWithoutChangingEventIdentity() throws Exception {
        var event = seed(1).getFirst();
        recorder.after = delivery -> jdbc.update(
                "UPDATE outbox_event SET lease_until = '2000-01-01 00:00:00' WHERE event_id = ?", delivery.message().eventId());
        assertEquals(0, runner.runBatch(request(1), policy));
        assertEquals("PROCESSING", status(event));
        recorder.after = null;
        assertEquals(1, runner.runBatch(request(1), policy));
        assertEquals("SENT", status(event));
        assertEquals(List.of(event.eventId(), event.eventId()), recorder.ids);
        assertNotEquals(recorder.tokens.get(0), recorder.tokens.get(1));
        var received = drain(factory, 2);
        assertEquals(2, received.size());
        assertTrue(received.stream().allMatch(message -> message.getProps().getMessageId().equals(event.eventId())));
    }

    /**
     * 容量否定确认进入待重试，排空并重建发布连接后同一事件可恢复成功。
     * @throws Exception AMQP读取失败
     */
    @Test
    void retriesActualBrokerNackAfterQueueCapacityRecovery() throws Exception {
        RabbitIntegrationSupport.clearTopology(admin);
        try (var limited = messagingContext(Map.of("stratum.messaging.topology.max-messages", "1"), true, true)) {
            var limitedRunner = limited.getBean(OutboxRelayApplicationService.class);
            var limitedFactory = limited.getBean(CachingConnectionFactory.class);
            OutboxMessage failed = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                var event = seed(1).getFirst();
                assertEquals(1, limitedRunner.runBatch(request(1), policy));
                if (status(event).equals("PENDING")) { failed = event; break; }
                assertEquals("SENT", status(event));
            }
            assertNotNull(failed, "容量拒绝必须在有界尝试中出现");
            assertEquals(1, retryCount(failed));
            assertFalse(drain(limitedFactory, 32).isEmpty());
            ((CachingConnectionFactory) limitedFactory.getPublisherConnectionFactory()).resetConnection();
            ready(failed);
            assertEquals(1, limitedRunner.runBatch(request(1), policy));
            assertEquals("SENT", status(failed));
            assertEquals(failed.eventId(), drain(limitedFactory, 1).getFirst().getProps().getMessageId());
        }
    }

    /**
     * 创建独立消息客户端上下文；需要时复用主应用事务/存储，绝不共享客户端销毁权。
     * @param overrides 连接或拓扑故障参数
     * @param topology 是否声明拓扑
     * @param withRunner 是否装配实际批次编排
     * @return 由用例关闭的上下文
     */
    private AnnotationConfigApplicationContext messagingContext(Map<String, Object> overrides, boolean topology, boolean withRunner) {
        var values = RabbitIntegrationSupport.connectionProperties();
        values.putAll(overrides);
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("relay-fault", values));
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
            context.register(RabbitMessagingConfiguration.class, RabbitOutboxPublisherConfiguration.class);
            if (topology) context.register(RabbitTopologyConfiguration.class);
            if (withRunner) {
                context.registerBean(UnitOfWork.class, () -> unitOfWork);
                context.registerBean(OutboxRelayRepository.class, () -> relayRepository);
                context.registerBean(StandardTraceContext.class, () -> standardTrace);
                context.register(OutboxRelayApplicationService.class);
            }
            context.refresh();
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    /**
     * 通过真实工作单元追加含高精度数据的有序探针。
     * @param count 同聚合事件数
     * @return 原始信封列表
     */
    private List<OutboxMessage> seed(int count) {
        String aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate);
        var events = new ArrayList<OutboxMessage>();
        for (int i = 0; i < count; i++) events.add(new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created",
                aggregate, Instant.now(), "{\"quantity\":99999999999999999999999999.123456789012}", "relay-real-trace"));
        unitOfWork.execute(() -> { events.forEach(outbox::append); return null; });
        return events;
    }

    /**
     * 有界实际批次请求。
     * @param size 最大尝试数
     * @return 30秒租期请求
     */
    private static OutboxRelayRepository.ClaimRequest request(int size) {
        return new OutboxRelayRepository.ClaimRequest(size, Duration.ofSeconds(30));
    }

    /**
     * 读取持久化状态。
     * @param event 原始事件
     * @return 当前状态
     */
    private String status(OutboxMessage event) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, event.eventId());
    }

    /**
     * 读取失败次数。
     * @param event 原始事件
     * @return 当前计数
     */
    private int retryCount(OutboxMessage event) {
        return jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE event_id = ?", Integer.class, event.eventId());
    }

    /**
     * 显式构造到期探针，不等待真实退避周期。
     * @param event 本用例事件
     */
    private void ready(OutboxMessage event) {
        jdbc.update("UPDATE outbox_event SET next_retry_at = '2000-01-01 00:00:00' WHERE event_id = ?", event.eventId());
    }

    /**
     * 从专用队列有界读取并ACK，不模拟数据库消费成功。
     * @param connectionFactory 当前拓扑所属工厂
     * @param maximum 最多读取条数
     * @return 实际消息
     * @throws Exception AMQP失败
     */
    private static List<GetResponse> drain(CachingConnectionFactory connectionFactory, int maximum) throws Exception {
        var messages = new ArrayList<GetResponse>();
        try (var channel = connectionFactory.createConnection().createChannel(false)) {
            for (int i = 0; i < maximum; i++) {
                var message = channel.basicGet(ERP_QUEUE, true);
                if (message == null) break;
                messages.add(message);
            }
        }
        return messages;
    }

    /** 并发追踪测试使用的单条领取替身，不模拟数据库竞争。 */
    private static final class SingleClaim implements OutboxRelayRepository {
        private final Claim claim;
        private boolean available = true;

        /**
         * 创建独立实例的固定领取证据。
         * @param claim 含原标准上下文的领取证据
         */
        private SingleClaim(Claim claim) { this.claim = claim; }

        /** {@inheritDoc} */
        @Override public List<Claim> claim(ClaimRequest request) {
            if (!available) return List.of(); available = false; return List.of(claim);
        }

        /** {@inheritDoc} */
        @Override public boolean finish(Claim claimed, OutboxPublisher.PublishResult result, RetryPolicy policy) {
            return claimed == claim && result == CONFIRMED;
        }
    }

    /** 仅本测试显式导入的观察配置，包装实际适配而非替换Broker行为。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /**
         * 通过Primary选择观察器，保留指定的实际生产发布器。
         * @param delegate 原发布端口
         * @param jdbc 外部提交可见性断言
         * @return 测试专用包装器
         */
        @Bean
        @Primary
        RecordingPublisher recordingPublisher(@Qualifier("outboxPublisher") OutboxPublisher delegate, JdbcTemplate jdbc) {
            return new RecordingPublisher(delegate, jdbc);
        }
    }

    /** 只记录有限测试身份，并断言真正发布发生在已提交领取之后。 */
    static final class RecordingPublisher implements OutboxPublisher {
        private final OutboxPublisher delegate;
        private final JdbcTemplate jdbc;
        private final List<String> ids = new ArrayList<>();
        private final List<String> tokens = new ArrayList<>();
        private OutboxPublisher override;
        private Consumer<Delivery> after;

        /**
         * 注入实际发布器和独立数据库客户端。
         * @param delegate 生产发布端口
         * @param jdbc 提交可见性断言
         */
        RecordingPublisher(OutboxPublisher delegate, JdbcTemplate jdbc) { this.delegate = delegate; this.jdbc = jdbc; }

        /** {@inheritDoc} */
        @Override
        public PublishResult publish(Delivery delivery) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var row = jdbc.queryForMap("SELECT status, lease_token FROM outbox_event WHERE event_id = ?", delivery.message().eventId());
            assertEquals("PROCESSING", row.get("status"));
            ids.add(delivery.message().eventId()); tokens.add((String) row.get("lease_token"));
            PublishResult result = (override == null ? delegate : override).publish(delivery);
            if (after != null && result == CONFIRMED) after.accept(delivery);
            return result;
        }
    }
}
