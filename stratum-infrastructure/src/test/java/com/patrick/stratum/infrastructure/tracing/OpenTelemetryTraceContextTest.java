package com.patrick.stratum.infrastructure.tracing;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.common.trace.StandardTraceContext.Carrier;
import com.patrick.stratum.common.trace.TraceIdContext;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 校验标准上下文提取、范围恢复和无Agent退化，不将模拟跨度视为导出验收。 */
class OpenTelemetryTraceContextTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private static final String SPAN = "1234567890abcdef";
    private static final String PARENT = "00-" + TRACE + "-" + SPAN + "-03";
    private final OpenTelemetryTraceContext bridge = new OpenTelemetryTraceContext();

    /** 无当前跨度时不伪造；捕获只保留W3C两个字段及完整标志，不保存baggage。 */
    @Test void capturesOnlyValidTraceFields() {
        try (var root = Context.root().makeCurrent()) { assertTrue(bridge.capture().isEmpty()); }
        var span = Span.wrap(SpanContext.create(TRACE, SPAN, TraceFlags.fromByte((byte) 3),
                TraceState.builder().put("vendor", "value").build()));
        var baggage = Baggage.builder().put("secret", "private-value").build();
        try (var scope = Context.root().with(span).with(baggage).makeCurrent()) {
            var captured = bridge.capture().orElseThrow();
            assertEquals(PARENT, captured.traceparent()); assertEquals("vendor=value", captured.tracestate());
            assertFalse(captured.toString().contains("private-value")); assertFalse(captured.toString().contains(TRACE));
        }
    }

    /** 非法parent被拒绝；非法、超长或非ASCII的state被丢弃但保留合法parent。 */
    @Test void normalizesBoundedUntrustedInputs() {
        assertTrue(bridge.normalize(null, null).isEmpty());
        assertTrue(bridge.normalize("x".repeat(1025), "vendor=value").isEmpty());
        assertTrue(bridge.normalize("00-" + "0".repeat(32) + "-" + SPAN + "-01", "").isEmpty());
        assertTrue(bridge.normalize("00-" + TRACE + "-" + "0".repeat(16) + "-01", "").isEmpty());
        assertTrue(bridge.normalize("ff-" + TRACE + "-" + SPAN + "-01", "").isEmpty());
        assertEquals("00-" + TRACE + "-" + SPAN + "-01", bridge.normalize("01-" + TRACE + "-" + SPAN + "-01-extra", "").orElseThrow().traceparent());
        assertTrue(bridge.normalize("00-" + TRACE.toUpperCase() + "-" + SPAN + "-01", "").isEmpty());
        assertTrue(bridge.normalize(PARENT.substring(0, PARENT.length() - 1), "").isEmpty());
        for (String invalid : List.of("broken", "vendor=bad\nvalue", "x".repeat(513), "name=中文")) {
            var result = bridge.normalize(PARENT, invalid).orElseThrow();
            assertEquals(PARENT, result.traceparent()); assertEquals("", result.tracestate());
        }
        String limit = "a=" + "v".repeat(256) + ",b=" + "w".repeat(251);
        assertEquals(512, limit.length()); assertEquals(limit, bridge.normalize(PARENT, limit).orElseThrow().tracestate());
        assertEquals("", bridge.normalize(PARENT, limit + "x").orElseThrow().tracestate());
        String tooMany = java.util.stream.IntStream.range(0, 33).mapToObj(index -> "k" + index + "=v").collect(java.util.stream.Collectors.joining(","));
        assertEquals("", bridge.normalize(PARENT, tooMany).orElseThrow().tracestate());
        assertEquals("", bridge.normalize(PARENT, "same=one,same=two").orElseThrow().tracestate());
        assertEquals("vendor=one,other=two", bridge.normalize(PARENT, " \tvendor=one,\t, other=two \t").orElseThrow().tracestate());
        assertEquals("vendor=one,other=two", bridge.normalize(PARENT, "vendor=one,\tother=two").orElseThrow().tracestate());
        assertEquals("vendor=one,other=two", bridge.normalize(PARENT, "vendor=one,,other=two").orElseThrow().tracestate());
        assertEquals("vendor=one two", bridge.normalize(PARENT, "vendor=one two").orElseThrow().tracestate());
        assertEquals("", bridge.normalize(PARENT, "vendor=bad\tvalue").orElseThrow().tracestate());
        var valid = bridge.normalize(PARENT, "tenant=value,other=ok").orElseThrow();
        assertEquals(PARENT, valid.traceparent()); assertEquals("tenant=value,other=ok", valid.tracestate());
        assertThrows(IllegalArgumentException.class, () -> new Carrier(PARENT, "x".repeat(513)));
    }

    /** 恢复从根上下文开始，作用域内无先前baggage，嵌套及异常退出均恢复原线程上下文。 */
    @Test void scopesRestorePriorContextAndExcludeBaggage() {
        var original = Span.wrap(SpanContext.create("a".repeat(32), "b".repeat(16), TraceFlags.getSampled(), TraceState.getDefault()));
        var baggage = Baggage.builder().put("tenant", "original").build();
        try (var outer = Context.root().with(original).with(baggage).makeCurrent()) {
            assertThrows(IllegalStateException.class, () -> {
                try (var scope = bridge.restore(bridge.normalize(PARENT, "vendor=value"))) {
                    assertEquals(TRACE, Span.current().getSpanContext().getTraceId());
                    assertTrue(Span.current().getSpanContext().isRemote()); assertNull(Baggage.current().getEntryValue("tenant"));
                    try (var root = bridge.restore(Optional.empty())) {
                        assertFalse(Span.current().getSpanContext().isValid()); assertNull(Baggage.current().getEntryValue("tenant"));
                    }
                    assertEquals(TRACE, Span.current().getSpanContext().getTraceId());
                    throw new IllegalStateException("controlled");
                }
            });
            assertEquals("a".repeat(32), Span.current().getSpanContext().getTraceId());
            assertEquals("original", Baggage.current().getEntryValue("tenant"));
        }
        assertFalse(Span.current().getSpanContext().isValid());
    }

    /** 标准跨度作用域不改变既有业务关联ID，即使工作抛出异常。 */
    @Test void neverChangesBusinessCorrelation() {
        String previous = TraceIdContext.getTraceId().orElse(null);
        TraceIdContext.setTraceId("business-trace-test");
        try {
            assertThrows(IllegalStateException.class, () -> {
                try (var scope = bridge.restore(bridge.normalize(PARENT, "vendor=value"))) {
                    assertEquals("business-trace-test", TraceIdContext.getTraceId().orElseThrow());
                    throw new IllegalStateException("controlled");
                }
            });
            assertEquals("business-trace-test", TraceIdContext.getTraceId().orElseThrow());
        } finally { if (previous == null) TraceIdContext.clear(); else TraceIdContext.setTraceId(previous); }
    }

    /**
     * 并发虚拟线程各自恢复独立跨度，关闭后均为空上下文。
     * @throws Exception 并发协调或工作线程失败
     */
    @Test void isolatesConcurrentVirtualThreads() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> observe("1".repeat(32), entered, release));
            var second = executor.submit(() -> observe("2".repeat(32), entered, release));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals("1".repeat(32), first.get(5, TimeUnit.SECONDS));
            assertEquals("2".repeat(32), second.get(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 在独立线程保持指定标准上下文并验证关闭恢复。
     * @param trace 本线程唯一trace ID
     * @param entered 两线程已进入作用域信号
     * @param release 允许继续的信号
     * @return 本线程观察到的trace ID
     * @throws Exception 等待失败
     */
    private String observe(String trace, CountDownLatch entered, CountDownLatch release) throws Exception {
        String parent = "00-" + trace + "-" + SPAN + "-01"; String observed;
        try (var scope = bridge.restore(bridge.normalize(parent, ""))) {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            observed = Span.current().getSpanContext().getTraceId();
        }
        assertFalse(Span.current().getSpanContext().isValid()); return observed;
    }
}
