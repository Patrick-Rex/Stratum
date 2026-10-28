package com.patrick.stratum.infrastructure.messaging;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.infrastructure.messaging.RabbitTopologyConfiguration.*;

/** 在独立JVM和专用Broker空间中观察生产发布器与监听器的标准追踪上下文。 */
public final class RabbitTraceAgentFixture {
    /** 禁止实例化测试入口。 */
    private RabbitTraceAgentFixture() { }

    /**
     * 显式启动和关闭生产消息组件，输出有界追踪探针而不输出凭据。
     * @param args 不使用的命令行参数
     * @throws Exception 连接、监听或关闭失败
     */
    public static void main(String[] args) throws Exception {
        String vhost = required("STRATUM_IT_MQ_VHOST"); String user = required("STRATUM_IT_MQ_USERNAME");
        if (!vhost.matches("stratum-it-[a-f0-9]{32}") || !vhost.equals(user)) throw new IllegalStateException("必须使用独立测试消息空间");
        int port = Integer.parseInt(required("STRATUM_IT_MQ_PORT"));
        if (port < 1 || port > 65535) throw new IllegalStateException("测试端口无效");
        var values = new HashMap<String, Object>();
        values.put("stratum.messaging.enabled", "true"); values.put("stratum.messaging.consumer.enabled", "true");
        values.put("stratum.messaging.host", "127.0.0.1"); values.put("stratum.messaging.port", port);
        values.put("stratum.messaging.virtual-host", vhost); values.put("stratum.messaging.username", user);
        values.put("stratum.messaging.password", required("STRATUM_IT_MQ_PASSWORD"));
        var done = new CountDownLatch(1); var current = new AtomicReference<String>(); var header = new AtomicReference<String>();
        var headerState = new AtomicReference<String>(); var postCurrent = new AtomicReference<String>();
        var baggageVisible = new AtomicReference<Boolean>();
        var restoredWorkers = new AtomicInteger(); var badRestore = new AtomicBoolean();
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                /** {@inheritDoc} */
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if ("rabbitConsumerExecutor".equals(name) && bean instanceof ThreadPoolTaskExecutor executor) {
                        var stale = SpanContext.createFromRemoteParent("dddddddddddddddddddddddddddddddd", "eeeeeeeeeeeeeeee",
                                TraceFlags.getSampled(), TraceState.getDefault());
                        executor.setTaskDecorator(task -> () -> {
                            var baggage = Baggage.builder().put("fixture", "do-not-propagate").build();
                            try (var ignored = Context.root().with(Span.wrap(stale)).with(baggage).makeCurrent()) {
                                try { task.run(); }
                                finally {
                                    if ("dddddddddddddddddddddddddddddddd:eeeeeeeeeeeeeeee:true".equals(identity())
                                            && "do-not-propagate".equals(Baggage.current().getEntryValue("fixture"))) restoredWorkers.incrementAndGet();
                                    else badRestore.set(true);
                                }
                            }
                        });
                    }
                    return bean;
                }
            });
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("trace-broker-fixture", values));
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
            context.registerBean(OutboxConsumer.class, () -> (delivery, group) -> {
                current.set(identity()); baggageVisible.set(Baggage.current().getEntryValue("fixture") != null);
                done.countDown(); return OutboxConsumer.Outcome.CONSUMED;
            });
            context.register(RabbitMessagingConfiguration.class, RabbitTopologyConfiguration.class,
                    RabbitOutboxPublisherConfiguration.class, RabbitOutboxConsumerConfiguration.class);
            context.refresh();
            var listener = context.getBean("erpOutboxListener", SimpleMessageListenerContainer.class);
            listener.addAfterReceivePostProcessors(message -> {
                Object parentHeader = message.getMessageProperties().getHeader("traceparent");
                Object stateHeader = message.getMessageProperties().getHeader("tracestate");
                header.set(String.valueOf(parentHeader)); headerState.set(String.valueOf(stateHeader));
                postCurrent.set(identity()); return message;
            });
            var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                    Instant.parse("2026-10-28T00:00:00Z"), "{}", "trace-broker-probe");
            var trace = new OpenTelemetryTraceContext();
            String parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
            try (var ignored = trace.restore(trace.normalize(parent, "vendor=value"))) {
                if (context.getBean(OutboxPublisher.class).publish(new OutboxPublisher.Delivery(event, 1))
                        != OutboxPublisher.PublishResult.CONFIRMED) throw new IllegalStateException("测试发布未确认");
            }
            if (!done.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("测试监听器未消费消息");
            listener.stop();
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
        System.out.println("STRATUM_TRACE_PROBE|" + header.get() + "|" + headerState.get() + "|" + postCurrent.get()
                + "|" + current.get() + "|" + baggageVisible.get() + "|" + restoredWorkers.get() + "|" + badRestore.get());
    }

    /**
     * 提取当前跨度的有限标识，未启用时返回固定空标记。
     * @return 有效trace和span或空标记
     */
    private static String identity() {
        var span = Span.current().getSpanContext();
        return span.isValid() ? span.getTraceId() + ":" + span.getSpanId() + ":" + span.isRemote() : "none";
    }

    /**
     * 读取测试环境变量，不在错误中输出值。
     * @param name 固定变量名
     * @return 非空配置
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少消息测试配置：" + name);
        return value;
    }
}
