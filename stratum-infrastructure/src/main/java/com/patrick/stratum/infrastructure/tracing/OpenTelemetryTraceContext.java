package com.patrick.stratum.infrastructure.tracing;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.patrick.stratum.common.trace.StandardTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.stereotype.Component;

/**
 * 使用OpenTelemetry W3C传播器捕获和恢复标准上下文，不复制baggage。
 * <p>示例：注入{@link StandardTraceContext}，在异步工作范围内恢复原上下文。</p>
 */
@Component
public final class OpenTelemetryTraceContext implements StandardTraceContext {
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        /** {@inheritDoc} */
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        /** {@inheritDoc} */
        @Override public String get(Map<String, String> carrier, String key) { return carrier == null ? null : carrier.get(key); }
    };

    /** {@inheritDoc} */
    @Override public Optional<Carrier> capture() { return from(Context.current()); }

    /** {@inheritDoc} */
    @Override public Optional<Carrier> normalize(String traceparent, String tracestate) {
        if (traceparent == null || traceparent.length() > 1024) return Optional.empty();
        var fields = new HashMap<String, String>(); fields.put("traceparent", traceparent);
        String state = normalizeState(tracestate);
        if (!state.isEmpty()) fields.put("tracestate", state);
        return from(PROPAGATOR.extract(Context.root(), fields, GETTER));
    }

    /** {@inheritDoc} */
    @Override public TraceScope restore(Optional<Carrier> carrier) {
        var parent = carrier == null ? Optional.<Carrier>empty() : carrier.flatMap(value -> normalize(value.traceparent(), value.tracestate()));
        Context restored = Context.root();
        if (parent.isPresent()) {
            var value = parent.orElseThrow(); var fields = new HashMap<String, String>(); fields.put("traceparent", value.traceparent());
            if (!value.tracestate().isEmpty()) fields.put("tracestate", value.tracestate());
            restored = PROPAGATOR.extract(restored, fields, GETTER);
        }
        var scope = restored.makeCurrent(); return scope::close;
    }

    /**
     * 仅通过W3C传播器提取有效跨度并限制两个可保存字段。
     * @param context 源上下文
     * @return 标准字段或空值
     */
    private Optional<Carrier> from(Context context) {
        if (!Span.fromContext(context).getSpanContext().isValid()) return Optional.empty();
        var fields = new HashMap<String, String>();
        PROPAGATOR.inject(context, fields, (carrier, key, value) -> carrier.put(key, value));
        String parent = fields.get("traceparent"); String state = fields.get("tracestate");
        state = normalizeState(state);
        try { return Optional.of(new Carrier(parent, state)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /**
     * 限制状态大小并规范化成员周围的空格、水平制表符及空成员；其余语法交给传播器校验。
     * @param state 状态候选值
     * @return 有界规范文本，超界或含非法字符时为空串
     */
    private String normalizeState(String state) {
        if (state == null || state.length() > 512 || state.chars().anyMatch(c -> c != '\t' && (c < 32 || c > 126))) return "";
        String[] members = state.split(",", -1); if (members.length > 32) return "";
        var result = new java.util.StringJoiner(",");
        for (String member : members) {
            int start = 0; int end = member.length();
            while (start < end && (member.charAt(start) == ' ' || member.charAt(start) == '\t')) start++;
            while (end > start && (member.charAt(end - 1) == ' ' || member.charAt(end - 1) == '\t')) end--;
            if (start < end) result.add(member.substring(start, end));
        }
        return result.toString();
    }
}
