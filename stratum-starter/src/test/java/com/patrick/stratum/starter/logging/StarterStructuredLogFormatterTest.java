package com.patrick.stratum.starter.logging;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证JSON日志只输出安全字段并区分业务关联与真实标准追踪。 */
class StarterStructuredLogFormatterTest {
    private final StarterStructuredLogFormatter formatter = new StarterStructuredLogFormatter();
    private final JsonMapper json = JsonMapper.builder().build();

    /** 已有业务及标准标识保持独立；敏感的MDC和未登记键不输出。 */
    @Test
    void keepsIndependentTraceFieldsAndOmitsUnregisteredValues() {
        var event = event();
        String trace = "1234567890abcdef1234567890abcdef", span = "1234567890abcdef";
        when(event.getMDCPropertyMap()).thenReturn(Map.of("businessTraceId", "business-1", "trace_id", trace,
                "span_id", span, "Authorization", "mdc-secret"));
        when(event.getKeyValuePairs()).thenReturn(List.of(new KeyValuePair("traceId", "different-business"),
                new KeyValuePair("durationMs", 25L), new KeyValuePair("Authorization", "pair-secret"), new KeyValuePair("body", "body-secret")));
        String output = formatter.format(event); var row = json.readTree(output);
        assertEquals(1, output.lines().count()); assertEquals("business-1", row.path("businessTraceId").asText());
        assertEquals(trace, row.path("traceId").asText()); assertEquals(span, row.path("spanId").asText());
        assertEquals(25, row.path("durationMs").asLong()); assertEquals("stratum-starter", row.path("service").asText());
        assertFalse(output.contains("secret")); assertFalse(row.has("Authorization")); assertFalse(row.has("body"));
    }

    /** 无有效标准上下文时不伪造跨度；业务事件可从固定trace字段获得关联值。 */
    @Test
    void rejectsInvalidStandardIdsAndUsesBusinessEventTrace() {
        var event = event();
        when(event.getMDCPropertyMap()).thenReturn(Map.of("trace_id", "not-w3c", "span_id", "0000000000000000"));
        when(event.getKeyValuePairs()).thenReturn(List.of(new KeyValuePair("traceId", "event-42")));
        var row = json.readTree(formatter.format(event));
        assertEquals("event-42", row.path("businessTraceId").asText());
        assertEquals("", row.path("traceId").asText()); assertEquals("", row.path("spanId").asText());
    }

    /** 异常只输出类型与有界调用位置，不序列化异常消息或任意对象。 */
    @Test
    void recordsOnlyThrowableType() {
        var event = event(); var exception = mock(IThrowableProxy.class);
        when(exception.getClassName()).thenReturn("java.lang.IllegalStateException");
        when(exception.getStackTraceElementProxyArray()).thenReturn(new StackTraceElementProxy[] {
                new StackTraceElementProxy(new StackTraceElement("Example", "run", "Example.java", 7)) });
        when(event.getThrowableProxy()).thenReturn(exception);
        when(event.getKeyValuePairs()).thenReturn(List.of(new KeyValuePair("result", new Object())));
        var row = json.readTree(formatter.format(event));
        assertEquals("java.lang.IllegalStateException", row.path("exceptionType").asText()); assertFalse(row.has("result"));
        assertEquals("Example.run(Example.java:7)", row.path("exceptionFrames").get(0).asText());
        assertEquals("", row.path("businessTraceId").asText());
    }

    /**
     * 创建不包含请求内容的日志事件。
     * @return 可配置日志事件
     */
    private ILoggingEvent event() {
        var event = mock(ILoggingEvent.class); when(event.getInstant()).thenReturn(Instant.parse("2026-10-28T00:00:00Z"));
        when(event.getLevel()).thenReturn(Level.INFO); when(event.getLoggerName()).thenReturn("safe-test");
        when(event.getFormattedMessage()).thenReturn("固定测试事件"); return event;
    }
}
