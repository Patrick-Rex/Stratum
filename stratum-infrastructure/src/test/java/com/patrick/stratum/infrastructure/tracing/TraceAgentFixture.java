package com.patrick.stratum.infrastructure.tracing;

import java.util.Optional;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/** 仅供独立Agent测试运行的入口，不启动Spring或外部服务。 */
public final class TraceAgentFixture {
    /** 禁止实例化测试入口。 */
    private TraceAgentFixture() { }

    /**
     * 创建真实Agent跨度，验证捕获、恢复和根上下文后结束所有跨度。
     * @param args 未使用的启动参数
     */
    public static void main(String[] args) {
        var tracer = GlobalOpenTelemetry.getTracer("stratum-test-bridge");
        var bridge = new OpenTelemetryTraceContext();
        var parent = tracer.spanBuilder("stratum-test-parent").setNoParent().startSpan();
        if (!parent.getSpanContext().isValid()) throw new IllegalStateException("Agent未提供真实跨度");
        var baggage = Baggage.builder().put("fixture", "private-fixture").build();
        try (var outer = Context.root().with(parent).with(baggage).makeCurrent()) {
            var saved = bridge.capture().orElseThrow();
            if (!saved.traceparent().contains(parent.getSpanContext().getTraceId())
                    || !saved.traceparent().contains(parent.getSpanContext().getSpanId())) throw new IllegalStateException("捕获上下文不匹配");
            try (var scope = bridge.restore(Optional.of(saved))) {
                if (Baggage.current().getEntryValue("fixture") != null) throw new IllegalStateException("恢复不应携带baggage");
                var child = tracer.spanBuilder("stratum-test-restored").startSpan(); child.end();
            }
            try (var root = bridge.restore(Optional.empty())) {
                if (Span.current().getSpanContext().isValid() || Baggage.current().getEntryValue("fixture") != null) {
                    throw new IllegalStateException("空历史上下文不应继承原跨度");
                }
                var standalone = tracer.spanBuilder("stratum-test-root").startSpan(); standalone.end();
            }
            if (!Span.current().getSpanContext().equals(parent.getSpanContext())
                    || !"private-fixture".equals(Baggage.current().getEntryValue("fixture"))) throw new IllegalStateException("作用域未恢复原线程上下文");
        } finally { parent.end(); }
    }
}
