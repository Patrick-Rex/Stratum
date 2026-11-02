package com.patrick.stratum.starter.integration;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRequester;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.messaging.RabbitOutboxConsumerConfiguration;
import com.patrick.stratum.starter.StratumStarterApplication;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group.ERP_AUDIT;
import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过独立 Agent 进程核验数据库、Relay、Broker 与实际消费事务的追踪。
 * <p>示例：由 Gradle 的 otelIntegrationTest 在专用测试资源中运行。</p>
 */
@Tag("otel-agent")
public class MessageTraceAgentIntegrationTest {
    private static final String NORMAL = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
    private static final String RETRY = "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01";
    private static final String REPLAY = "00-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee-ffffffffffffffff-01";
    private static final String OPERATOR = "00-99999999999999999999999999999999-8888888888888888-01";

    /**
     * 使用真实 Agent 核验正常、历史、等待重投与人工重放的发布和消费跨度。
     * @throws Exception 子进程或输出读取失败
     */
    @Test
    void tracesRealOutboxTransactionsAndBroker() throws Exception {
        var observed = runFixture();
        var normal = observed.sample("normal", 1); var historical = observed.sample("historical", 1);
        var retry = observed.sample("retry", 1); var retryAgain = observed.sample("retry", 2);
        var replay = observed.sample("replay", 1);
        verifySpans(observed, normal, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "a=one");
        verifySpans(observed, retry, "cccccccccccccccccccccccccccccccc", "dddddddddddddddd", "c=two");
        verifySpans(observed, retryAgain, "cccccccccccccccccccccccccccccccc", "dddddddddddddddd", "c=two");
        verifySpans(observed, replay, "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ffffffffffffffff", "r=three");
        assertEquals(retry.parent(), retryAgain.parent());
        assertNotEquals(retry.current(), retryAgain.current());
        assertTrue(historical.parent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
        String root = historical.parent().substring(3, 35);
        assertNotEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", root);
        assertNotEquals("cccccccccccccccccccccccccccccccc", root);
        assertNotEquals("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", root);
        assertNotEquals("99999999999999999999999999999999", root);
        verifySpans(observed, historical, root, "", "null");
    }

    /**
     * 核对原始父节点、实际发布跨度和消费跨度的直接父链。
     * @param observed 消费采样与实际导出
     * @param sample 一次真实消费
     * @param trace 预期标准追踪标识
     * @param originalParent 原事件父跨度，历史事件为空
     * @param state 预期传输状态
     */
    private void verifySpans(Observation observed, Sample sample, String trace, String originalParent, String state) {
        assertTrue(sample.parent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
        assertEquals(trace, sample.parent().substring(3, 35)); assertEquals(state, sample.state());
        String producerId = sample.parent().substring(36, 52); String[] current = sample.current().split(":");
        assertEquals(3, current.length); assertEquals(trace, current[0]); assertEquals("false", current[2]);
        var producer = observed.spans().get(producerId); var consumer = observed.spans().get(current[1]);
        assertNotNull(producer); assertNotNull(consumer);
        assertEquals(trace, producer.trace()); assertEquals("stratum.topic publish", producer.name());
        assertEquals(originalParent, producer.parent());
        assertEquals(trace, consumer.trace()); assertEquals("erp.probe.created process", consumer.name());
        assertEquals(producerId, consumer.parent());
    }

    /**
     * 启动隔离 Agent JVM 并读取有限测试采样及实际 OTLP 输出。
     * @return 本次采样及跨度
     * @throws Exception 子进程或文件操作失败
     */
    private Observation runFixture() throws Exception {
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        assertTrue(Files.isRegularFile(Path.of(agent)));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path directory = Files.createTempDirectory("stratum-message-trace-");
        Path arguments = directory.resolve("java.args"); Path stdout = directory.resolve("stdout.log"); Path stderr = directory.resolve("stderr.log");
        Process process = null;
        try {
            Files.writeString(arguments, "-Xms32m\n-Xmx384m\n-XX:MaxDirectMemorySize=64m\n-XX:ActiveProcessorCount=2\n"
                    + quote("-javaagent:" + agent) + "\n-cp\n" + quote(classpath()) + "\n" + getClass().getName() + "\n", StandardCharsets.UTF_8);
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments);
            var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("STRATUM_") || name.startsWith("SPRING_") || name.startsWith("OTEL_")
                    || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            for (String name : List.of("STRATUM_IT_DB_URL", "STRATUM_IT_DB_USERNAME", "STRATUM_IT_DB_PASSWORD", "STRATUM_IT_REDIS_PORT",
                    "STRATUM_IT_MQ_PORT", "STRATUM_IT_MQ_VHOST", "STRATUM_IT_MQ_USERNAME", "STRATUM_IT_MQ_PASSWORD")) {
                environment.put(name, required(name));
            }
            environment.put("OTEL_TRACES_EXPORTER", "logging-otlp"); environment.put("OTEL_METRICS_EXPORTER", "none");
            environment.put("OTEL_LOGS_EXPORTER", "none"); environment.put("OTEL_TRACES_SAMPLER", "always_on");
            environment.put("OTEL_BSP_SCHEDULE_DELAY", "100"); environment.put("OTEL_INSTRUMENTATION_JDBC_ENABLED", "false");
            builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()); process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(90)), "消息追踪测试进程未退出");
            assertEquals(0, process.exitValue(), "消息追踪测试进程失败");
            return parse(read(stdout), read(stderr));
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(Duration.ofSeconds(5))) {
                        process.destroyForcibly();
                        if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("消息追踪测试进程未退出");
                    }
                }
            } finally {
                try { Files.deleteIfExists(stdout); } finally {
                    try { Files.deleteIfExists(stderr); } finally {
                        try { Files.deleteIfExists(arguments); } finally { Files.deleteIfExists(directory); }
                    }
                }
            }
        }
    }

    /**
     * 提取已登记场景采样与 Agent 实际导出的有限跨度身份。
     * @param stdout 测试进程标准输出
     * @param stderr Agent 诊断输出
     * @return 可核验的采样和跨度
     */
    private Observation parse(String stdout, String stderr) {
        var samples = new HashMap<String, Sample>(); var spans = new HashMap<String, SpanIdentity>();
        assertEquals(1, stdout.lines().filter("STRATUM_MESSAGE_DONE"::equals).count());
        for (String line : stdout.split("\\R")) {
            if (!line.startsWith("STRATUM_MESSAGE|")) continue;
            var fields = List.of(line.split("\\|", -1)); assertEquals(8, fields.size());
            assertEquals("flow-" + fields.get(1), fields.get(6)); assertEquals("false", fields.get(7));
            assertNull(samples.put(fields.get(1) + ":" + fields.get(2), new Sample(fields.get(3), fields.get(4), fields.get(5))));
        }
        assertEquals(5, samples.size()); var json = JsonMapper.builder().build();
        for (String line : stderr.split("\\R")) {
            int offset = line.indexOf("{\"resource\""); if (offset < 0) continue;
            for (var scope : json.readTree(line.substring(offset)).path("scopeSpans")) for (var span : scope.path("spans")) {
                String name = span.path("name").asText();
                if (name.equals("stratum.topic publish") || name.equals("erp.probe.created process")) {
                    assertNull(spans.put(span.path("spanId").asText(), new SpanIdentity(span.path("traceId").asText(), name,
                            span.path("parentSpanId").asText(""))));
                }
            }
        }
        return new Observation(Map.copyOf(samples), Map.copyOf(spans));
    }

    /**
     * 独立 JVM 入口：真实应用事务、Relay 和自动监听器连接专用数据库及 Broker。
     * @param args 不使用的命令行参数
     * @throws Exception 应用或消息处理失败
     * @apiNote 仅由本类的 Agent 集成测试启动，并提供专用本机资源。
     */
    public static void main(String[] args) throws Exception {
        String url = required("STRATUM_IT_DB_URL"); String vhost = required("STRATUM_IT_MQ_VHOST");
        if (!url.matches("jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/stratum_it")
                || !vhost.matches("stratum-it-[a-f0-9]{32}") || !vhost.equals(required("STRATUM_IT_MQ_USERNAME"))) {
            throw new IllegalStateException("消息追踪验收必须使用独立本机测试资源");
        }
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.datasource.username", required("STRATUM_IT_DB_USERNAME"));
        System.setProperty("spring.datasource.password", required("STRATUM_IT_DB_PASSWORD"));
        System.setProperty("spring.data.redis.host", "127.0.0.1"); System.setProperty("spring.data.redis.port", required("STRATUM_IT_REDIS_PORT"));
        System.setProperty("stratum.messaging.host", "127.0.0.1"); System.setProperty("stratum.messaging.port", required("STRATUM_IT_MQ_PORT"));
        System.setProperty("stratum.messaging.virtual-host", vhost); System.setProperty("stratum.messaging.username", vhost);
        System.setProperty("stratum.messaging.password", required("STRATUM_IT_MQ_PASSWORD"));
        try (var context = new SpringApplicationBuilder(StratumStarterApplication.class).run(
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--stratum.messaging.enabled=true",
                "--stratum.messaging.consumer.enabled=false", "--stratum.messaging.topology.retry-min=100ms",
                "--stratum.messaging.topology.retry-max=100ms", "--spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")) {
            var jdbc = context.getBean(JdbcTemplate.class); var work = context.getBean(UnitOfWork.class);
            var outbox = context.getBean(OutboxRepository.class); var trace = context.getBean(StandardTraceContext.class);
            var events = Map.of("normal", event("normal"), "historical", event("historical"), "retry", event("retry"), "replay", event("replay"));
            UUID archive = null; var admin = context.getBean(RabbitAdmin.class);
            try {
                save(work, outbox, trace, events.get("normal"), NORMAL, "a=one");
                save(work, outbox, trace, events.get("historical"), null, null);
                save(work, outbox, trace, events.get("retry"), RETRY, "c=two");
                save(work, outbox, trace, events.get("replay"), REPLAY, "r=three");
                verifyStored(jdbc, events.get("normal"), NORMAL, "a=one");
                verifyStored(jdbc, events.get("historical"), null, null);
                verifyStored(jdbc, events.get("retry"), RETRY, "c=two");
                verifyStored(jdbc, events.get("replay"), REPLAY, "r=three");
                archive = archive(work, context.getBean(OutboxDeadLetterRepository.class), jdbc, events.get("replay"));
                OutboxReplayRepository.Receipt receipt;
                try (var ignored = trace.restore(trace.normalize(OPERATOR, "operator=other"))) {
                    receipt = context.getBean(OutboxReplayRequester.class).request(new OutboxReplayRepository.Request(
                            UUID.randomUUID(), archive, "manual-replay-operation"));
                }
                if (receipt.outcome() != OutboxReplayRepository.Outcome.QUEUED) throw new IllegalStateException("重放意图未登记");
                var samples = new CopyOnWriteArrayList<String>(); var headers = new ConcurrentHashMap<String, String>();
                var counts = new ConcurrentHashMap<String, AtomicInteger>(); var labels = new HashMap<String, String>();
                events.forEach((label, value) -> labels.put(value.eventId(), label));
                try (var listeners = new AnnotationConfigApplicationContext()) {
                    listeners.setParent(context);
                    listeners.getEnvironment().getPropertySources().addFirst(new MapPropertySource("message-trace-fixture", Map.of(
                            "stratum.messaging.enabled", true, "stratum.messaging.consumer.enabled", true)));
                    var actualConsumer = context.getBean(OutboxConsumer.class);
                    listeners.registerBean("traceProbeConsumer", OutboxConsumer.class, () -> (delivery, group) -> {
                        String label = Objects.requireNonNull(labels.get(delivery.message().eventId()));
                        int attempt = counts.computeIfAbsent(label, ignored -> new AtomicInteger()).incrementAndGet();
                        var span = Span.current().getSpanContext();
                        String current = span.isValid() ? span.getTraceId() + ":" + span.getSpanId() + ":" + span.isRemote() : "none";
                        samples.add("STRATUM_MESSAGE|" + label + "|" + attempt + "|" + headers.get(delivery.message().eventId())
                                + "|" + current + "|" + MDC.get("businessTraceId") + "|" + (Baggage.current().getEntryValue("fixture") != null));
                        if (label.equals("retry") && attempt == 1) return OutboxConsumer.Outcome.WAITING;
                        return actualConsumer.consume(delivery, group);
                    }, definition -> definition.setPrimary(true));
                    listeners.register(RabbitOutboxConsumerConfiguration.class); listeners.refresh();
                    var erpListener = listeners.getBean("erpOutboxListener", SimpleMessageListenerContainer.class);
                    erpListener.stop();
                    erpListener.addAfterReceivePostProcessors(message -> {
                        var properties = message.getMessageProperties();
                        Object parent = properties.getHeader("traceparent"); Object state = properties.getHeader("tracestate");
                        headers.put(properties.getMessageId(), String.valueOf(parent) + "|" + String.valueOf(state));
                        return message;
                    });
                    try (var ambient = trace.restore(trace.normalize(OPERATOR, "operator=other"));
                         var baggage = Context.current().with(Baggage.builder().put("fixture", "private").build()).makeCurrent()) {
                        int sent = context.getBean(OutboxRelayApplicationService.class).runBatch(
                                new OutboxRelayRepository.ClaimRequest(8, Duration.ofSeconds(30)),
                                new OutboxRelayRepository.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(1)));
                        if (sent != 4 || !OPERATOR.equals(trace.capture().orElseThrow().traceparent())) {
                            throw new IllegalStateException("发布数量或调用方上下文不匹配");
                        }
                    }
                    erpListener.start();
                    UUID deliveryId = receipt.deliveryId().orElseThrow();
                    await().atMost(Duration.ofSeconds(20)).until(() -> events.values().stream().allMatch(value -> receiptCount(jdbc, value) == 1)
                            && "RESOLVED".equals(jdbc.queryForObject("SELECT status FROM outbox_replay_delivery WHERE delivery_id=?", String.class, deliveryId.toString())));
                }
                if (samples.size() != 5 || counts.get("retry").get() != 2) throw new IllegalStateException("消费采样次数不匹配");
                samples.forEach(System.out::println); System.out.println("STRATUM_MESSAGE_DONE");
            } finally { cleanup(jdbc, admin, events.values(), archive); }
        }
    }

    /**
     * 在原事件上下文的真实工作单元中保存事件。
     * @param work 工作单元
     * @param outbox 事件仓储
     * @param trace 追踪边界
     * @param event 本次事件
     * @param parent 原父头，历史事件为空
     * @param state 可选标准状态
     */
    private static void save(UnitOfWork work, OutboxRepository outbox, StandardTraceContext trace, OutboxMessage event, String parent, String state) {
        try (var ignored = trace.restore(trace.normalize(parent, state))) { work.execute(() -> { outbox.append(event); return null; }); }
    }

    /**
     * 核验实际提交的标准上下文。
     * @param jdbc 专用数据库客户端
     * @param event 本次事件
     * @param parent 预期父头
     * @param state 预期标准状态
     * @throws IllegalStateException 持久化字段不匹配
     */
    private static void verifyStored(JdbcTemplate jdbc, OutboxMessage event, String parent, String state) {
        var row = jdbc.queryForMap("SELECT standard_traceparent,standard_tracestate FROM outbox_event WHERE event_id=?", event.eventId());
        if (!Objects.equals(parent, row.get("standard_traceparent")) || !Objects.equals(state, row.get("standard_tracestate"))) {
            throw new IllegalStateException("事件持久化上下文不匹配");
        }
    }

    /**
     * 构造已发送且存在归档的前置状态，后续重放和消费使用真实应用入口。
     * @param work 工作单元
     * @param deadLetters 归档仓储
     * @param jdbc 专用数据库客户端
     * @param event 本次原事件
     * @return 真实归档身份
     */
    private static UUID archive(UnitOfWork work, OutboxDeadLetterRepository deadLetters, JdbcTemplate jdbc, OutboxMessage event) {
        jdbc.update("UPDATE outbox_event SET status='SENT' WHERE event_id=?", event.eventId());
        var delivery = new Delivery(event, 1); var json = JsonMapper.builder().build();
        byte[] body = json.writeValueAsBytes(Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateId", event.aggregateId(),
                "occurredAt", event.occurredAt().toString(), "traceId", event.traceId(), "sequence", 1, "payload", json.readTree(event.payload())));
        var letter = new OutboxDeadLetterRepository.Letter(UUID.randomUUID(), ERP_AUDIT, body, "{}", Optional.of(delivery),
                OutboxDeadLetterRepository.Category.RETRIES_EXHAUSTED, 1);
        return work.execute(() -> deadLetters.append(letter));
    }

    /**
     * 读取本次事件的真实消费凭据数量。
     * @param jdbc 专用数据库客户端
     * @param event 本次事件
     * @return 已提交的凭据数量
     */
    private static int receiptCount(JdbcTemplate jdbc, OutboxMessage event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id=?", Integer.class, event.eventId());
    }

    /**
     * 删除本次随机事件及经校验虚拟主机中的测试拓扑。
     * @param jdbc 专用数据库客户端
     * @param admin 专用虚拟主机管理器
     * @param events 本次事件
     * @param archive 本次归档，可为空
     */
    private static void cleanup(JdbcTemplate jdbc, RabbitAdmin admin, java.util.Collection<OutboxMessage> events, UUID archive) {
        if (archive != null) {
            for (String table : List.of("outbox_replay_request", "outbox_replay_delivery", "outbox_dead_letter")) {
                jdbc.update("DELETE FROM " + table + " WHERE dead_id=?", archive.toString());
            }
        }
        for (var event : events) {
            for (String table : List.of("outbox_consumption", "outbox_event", "outbox_aggregate_progress")) {
                jdbc.update("DELETE FROM " + table + " WHERE aggregate_id=?", event.aggregateId());
            }
        }
        for (String queue : List.of(CACHE_QUEUE, ERP_QUEUE, CACHE_QUEUE + ".dlq", ERP_QUEUE + ".dlq")) admin.deleteQueue(queue);
        admin.deleteExchange(EXCHANGE); admin.deleteExchange(DEAD_EXCHANGE);
    }

    /**
     * 构造独立聚合的测试事件。
     * @param label 固定场景名称
     * @return 新事件
     */
    private static OutboxMessage event(String label) {
        return new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.parse("2026-10-30T00:00:00Z"), "{}", "flow-" + label);
    }

    /**
     * 获取非空测试配置，异常不包含原值。
     * @param name 环境变量名称
     * @return 配置值
     * @throws IllegalStateException 配置缺失
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少测试配置：" + name);
        return value;
    }

    /**
     * 有界读取本次测试输出。
     * @param path 输出文件
     * @return UTF-8 文本
     * @throws Exception 读取失败或输出超界
     */
    private String read(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("消息追踪输出超界");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 编码 Java 参数文件中的带空格参数。
     * @param value 原始参数
     * @return 转义后的参数
     */
    private String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /**
     * 收集测试运行时实际类路径，避免 Windows 命令行长度限制。
     * @return 已去重的文件类路径
     * @throws Exception 类路径 URI 无效
     */
    private String classpath() throws Exception {
        var entries = new LinkedHashSet<String>();
        for (var loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
        }
        entries.addAll(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        return String.join(java.io.File.pathSeparator, entries);
    }

    /**
     * 消费消息头与当前标准跨度。
     * @param parent 传播父头
     * @param state 标准状态
     * @param current 当前跨度
     */
    private record Sample(String parent, String state, String current) { }

    /**
     * 实际导出跨度身份。
     * @param trace 标准标识
     * @param name 跨度名
     * @param parent 父跨度
     */
    private record SpanIdentity(String trace, String name, String parent) { }

    /**
     * 本次消费采样及导出跨度。
     * @param samples 按场景及次数索引
     * @param spans 按跨度 ID 索引
     */
    private record Observation(Map<String, Sample> samples, Map<String, SpanIdentity> spans) {
        /**
         * 获取必需采样。
         * @param label 场景名
         * @param attempt 实际尝试次数
         * @return 对应采样
         * @throws NullPointerException 采样缺失
         */
        Sample sample(String label, int attempt) { return Objects.requireNonNull(samples.get(label + ":" + attempt)); }
    }
}
