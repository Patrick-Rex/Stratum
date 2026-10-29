package com.patrick.stratum.infrastructure.messaging;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;

/** 独立JVM中以受控消费端口观察真实Broker正常、历史与重投消息的追踪。 */
public final class RabbitTraceFlowFixture {
    /** 禁止实例化测试入口。 */
    private RabbitTraceFlowFixture() { }

    /**
     * 启动真实发布器及监听器，输出有限追踪探针，并关闭本次创建的资源。
     * @param args 不使用的命令行参数
     * @throws Exception Broker或监听操作失败
     * @apiNote 示例：由集成测试以独立JVM和专用Broker空间启动。
     */
    public static void main(String[] args) throws Exception {
        String vhost = required("STRATUM_IT_MQ_VHOST"); String user = required("STRATUM_IT_MQ_USERNAME");
        if (!vhost.matches("stratum-it-[a-f0-9]{32}") || !vhost.equals(user)) throw new IllegalStateException("必须使用独立测试消息空间");
        int port = Integer.parseInt(required("STRATUM_IT_MQ_PORT"));
        if (port < 1 || port > 65535) throw new IllegalStateException("测试端口无效");
        var properties = new HashMap<String, Object>();
        properties.put("stratum.messaging.enabled", "true"); properties.put("stratum.messaging.consumer.enabled", "true");
        properties.put("stratum.messaging.host", "127.0.0.1"); properties.put("stratum.messaging.port", port);
        properties.put("stratum.messaging.virtual-host", vhost); properties.put("stratum.messaging.username", user);
        properties.put("stratum.messaging.password", required("STRATUM_IT_MQ_PASSWORD"));
        properties.put("stratum.messaging.topology.retry-min", "100ms"); properties.put("stratum.messaging.topology.retry-max", "100ms");
        var normal = event("normal"); var historical = event("historical"); var retry = event("retry");
        var labels = Map.of(normal.eventId(), "normal", historical.eventId(), "historical", retry.eventId(), "retry");
        var headers = new ConcurrentHashMap<String, String>(); var samples = new CopyOnWriteArrayList<String>();
        var completed = new CountDownLatch(3); var retries = new AtomicInteger();
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("trace-flow-fixture", properties));
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
            context.registerBean(OutboxConsumer.class, () -> (delivery, group) -> {
                String label = labels.get(delivery.message().eventId());
                if (label == null) throw new IllegalStateException("收到非测试消息");
                int attempt = label.equals("retry") ? retries.incrementAndGet() : 1;
                samples.add("STRATUM_FLOW|" + label + "|" + attempt + "|" + headers.get(delivery.message().eventId())
                        + "|" + identity() + "|" + MDC.get("businessTraceId") + "|"
                        + (Baggage.current().getEntryValue("fixture") != null));
                if (label.equals("retry") && attempt == 1) return OutboxConsumer.Outcome.WAITING;
                completed.countDown(); return OutboxConsumer.Outcome.CONSUMED;
            });
            context.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class,
                    RabbitOutboxPublisherConfiguration.class, RabbitOutboxConsumerConfiguration.class);
            context.refresh();
            var listener = context.getBean("erpOutboxListener", SimpleMessageListenerContainer.class);
            listener.addAfterReceivePostProcessors(message -> {
                var messageProperties = message.getMessageProperties();
                Object parent = messageProperties.getHeader("traceparent"); Object state = messageProperties.getHeader("tracestate");
                headers.put(messageProperties.getMessageId(), String.valueOf(parent) + "|" + String.valueOf(state));
                return message;
            });
            var trace = new OpenTelemetryTraceContext(); var publisher = context.getBean(OutboxPublisher.class);
            var stale = SpanContext.createFromRemoteParent("dddddddddddddddddddddddddddddddd", "eeeeeeeeeeeeeeee",
                    TraceFlags.getSampled(), TraceState.getDefault());
            try (var ambient = Context.root().with(Span.wrap(stale))
                    .with(Baggage.builder().put("fixture", "do-not-propagate").build()).makeCurrent()) {
                send(publisher, normal, trace, trace.normalize("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01", "a=one"));
                send(publisher, historical, trace, Optional.empty());
                send(publisher, retry, trace, trace.normalize("00-cccccccccccccccccccccccccccccccc-ffffffffffffffff-01", "c=two"));
                if (!"dddddddddddddddddddddddddddddddd:eeeeeeeeeeeeeeee:true".equals(identity())
                        || !"do-not-propagate".equals(Baggage.current().getEntryValue("fixture"))) {
                    throw new IllegalStateException("发布作用域未恢复调用线程");
                }
            }
            if (!completed.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("测试消息未全部消费");
            listener.stop();
            if (retries.get() != 2 || samples.size() != 4) throw new IllegalStateException("测试消费次数不匹配");
        } finally {
            try {
                if (context.isActive()) {
                    context.getBeansOfType(SimpleMessageListenerContainer.class).values().forEach(SimpleMessageListenerContainer::stop);
                    var admin = context.getBean(RabbitAdmin.class);
                    for (String queue : List.of(CACHE_QUEUE, ERP_QUEUE, CACHE_QUEUE + ".dlq", ERP_QUEUE + ".dlq")) admin.deleteQueue(queue);
                    admin.deleteExchange(EXCHANGE); admin.deleteExchange(DEAD_EXCHANGE);
                }
            } finally { context.close(); }
        }
        samples.forEach(System.out::println);
    }

    /**
     * 从给定原始上下文发布测试事件。
     * @param publisher 真实消息发布端口
     * @param event 本次事件
     * @param trace 标准追踪边界
     * @param carrier 原始上下文，空值模拟历史事件
     */
    private static void send(OutboxPublisher publisher, OutboxMessage event, OpenTelemetryTraceContext trace,
            Optional<com.patrick.stratum.common.trace.StandardTraceContext.Carrier> carrier) {
        try (var ignored = trace.restore(carrier)) {
            if (publisher.publish(new OutboxPublisher.Delivery(event, 1)) != OutboxPublisher.PublishResult.CONFIRMED) {
                throw new IllegalStateException("测试发布未确认");
            }
        }
    }

    /**
     * 构造独立聚合的固定业务关联事件。
     * @param label 场景标记
     * @return 测试事件
     */
    private static OutboxMessage event(String label) {
        return new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.parse("2026-10-30T00:00:00Z"), "{}", "flow-" + label);
    }

    /**
     * 返回当前跨度的有限身份。
     * @return 有效trace和span或空标记
     */
    private static String identity() {
        var span = Span.current().getSpanContext();
        return span.isValid() ? span.getTraceId() + ":" + span.getSpanId() + ":" + span.isRemote() : "none";
    }

    /**
     * 读取非空环境配置，不输出配置值。
     * @param name 配置名称
     * @return 配置值
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少消息测试配置：" + name);
        return value;
    }
}
