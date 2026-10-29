package com.patrick.stratum.infrastructure.messaging;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.rabbitmq.client.Channel;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

/** 不连接Broker，以实际Agent跨度核验消费适配器保留当前跨度与确认语义。 */
public final class RabbitTraceDirectAgentFixture {
    /** 禁止实例化测试入口。 */
    private RabbitTraceDirectAgentFixture() { }

    /**
     * 在真实Agent当前跨度内消费有效信封并检查业务、日志及单条确认。
     * @param args 未使用的命令行参数
     * @throws Exception 编码或确认调用失败
     */
    public static void main(String[] args) throws Exception {
        var span = GlobalOpenTelemetry.getTracer("stratum-test-consumer").spanBuilder("stratum-consumer-probe").setNoParent().startSpan();
        if (!span.getSpanContext().isValid() || span.getSpanContext().isRemote()) throw new IllegalStateException("Agent未创建本地跨度");
        String id = UUID.randomUUID().toString(); String aggregate = UUID.randomUUID().toString();
        String business = "consumer-business"; var json = JsonMapper.builder().build();
        var body = json.writeValueAsBytes(Map.of("eventId", id, "eventType", "erp.probe.created", "aggregateId", aggregate,
                "occurredAt", "2026-10-29T00:00:00Z", "payload", Map.of(), "traceId", business, "sequence", 1));
        var properties = new MessageProperties(); properties.setMessageId(id); properties.setCorrelationId(business);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON); properties.setContentEncoding("UTF-8");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT); properties.setDeliveryTag(7);
        properties.setReceivedExchange(RabbitTopologyConfiguration.EXCHANGE); properties.setReceivedRoutingKey("erp.probe.created");
        properties.setHeader("X-Trace-Id", business); properties.setHeader("stratum-aggregate-id", aggregate);
        properties.setHeader("stratum-event-type", "erp.probe.created"); properties.setHeader("stratum-sequence", 1L);
        properties.setHeader("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        var acked = new AtomicInteger(); var consumed = new AtomicInteger();
        var channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class}, (proxy, method, parameters) -> {
            if (method.getName().equals("basicAck") && parameters[0].equals(7L) && parameters[1].equals(false)) {
                acked.incrementAndGet(); return null;
            }
            throw new IllegalStateException("出现意外确认调用");
        });
        OutboxConsumer consumer = (delivery, group) -> {
            if (group != Group.ERP_AUDIT || !id.equals(delivery.message().eventId())
                    || !span.getSpanContext().equals(Span.current().getSpanContext())
                    || !business.equals(MDC.get("businessTraceId"))) throw new IllegalStateException("消费作用域不匹配");
            consumed.incrementAndGet(); return OutboxConsumer.Outcome.CONSUMED;
        };
        var adapter = new RabbitOutboxConsumerAdapter(new RabbitOutboxMessageCodec(json), consumer, Group.ERP_AUDIT,
                new MessageMetrics(null), new UnavailableTrace());
        MDC.put("businessTraceId", "prior-business");
        try (var ignored = span.makeCurrent()) {
            adapter.onMessage(new Message(body, properties), channel);
            if (!span.getSpanContext().equals(Span.current().getSpanContext()) || !"prior-business".equals(MDC.get("businessTraceId"))) {
                throw new IllegalStateException("消费退出后调用方作用域未恢复");
            }
            if (consumed.get() != 1 || acked.get() != 1) throw new IllegalStateException("业务或确认次数不正确");
        } finally { MDC.remove("businessTraceId"); span.end(); }
        System.out.println("STRATUM_DIRECT_AGENT_OK");
    }

    /** Agent已有当前消费跨度时不得调用的受控追踪组件故障替身。 */
    private static final class UnavailableTrace implements StandardTraceContext {
        /** {@inheritDoc} */
        @Override public Optional<Carrier> capture() { throw new IllegalStateException("不可调用"); }
        /** {@inheritDoc} */
        @Override public Optional<Carrier> normalize(String parent, String state) { throw new IllegalStateException("不可调用"); }
        /** {@inheritDoc} */
        @Override public TraceScope restore(Optional<Carrier> carrier) { throw new IllegalStateException("不可调用"); }
    }
}
