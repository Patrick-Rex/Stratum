package com.patrick.stratum.starter.logging;

import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.interfaceadapter.web.RequestTraceFilter;
import io.opentelemetry.api.trace.Span;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import tools.jackson.databind.json.JsonMapper;

/** Starter结构化日志，仅输出登记字段和真实标准追踪上下文，不展开任意MDC、异常消息或对象。 */
public final class StarterStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final String TRACE_THREAD_MDC_KEY = "stratumTraceThreadId";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ENVIRONMENT = System.getenv().getOrDefault("STRATUM_ENV", "local");
    private static final Set<String> FIELDS = Set.of("queryCode", "durationMs", "resultCount", "result", "jobCode", "executionId",
            "attempt", "scheduleTime", "startTime", "endTime", "stateUpdated", "errorType", "activeTasks", "scanned", "reconciled",
            "updatedDeliveries", "eventId", "messageId", "consumerGroup", "outcome", "category", "requestId", "archiveId", "deliveryId",
            "taskId", "cacheKey", "hit", "loadDurationMs", "lockKey", "acquireResult", "releaseResult", "waitMs", "holdMs");

    /** {@inheritDoc} */
    @Override public String format(ILoggingEvent event) {
        Map<String, String> context = event.getMDCPropertyMap(); if (context == null) context = Map.of();
        String business = context.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY);
        String standardTrace = standardId(context.get("trace_id"), 32);
        String standardSpan = standardId(context.get("span_id"), 16);
        if ((standardTrace.isEmpty() || standardSpan.isEmpty())
                && Long.toString(Thread.currentThread().threadId()).equals(context.get(TRACE_THREAD_MDC_KEY))) {
            try {
                var current = Span.current().getSpanContext();
                if (current.isValid()) { standardTrace = current.getTraceId(); standardSpan = current.getSpanId(); }
            } catch (RuntimeException ignored) { /* 追踪组件故障不阻止日志输出。 */ }
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("timestamp", event.getInstant().toString()); fields.put("level", event.getLevel().toString());
        fields.put("service", "stratum-starter"); fields.put("env", ENVIRONMENT);
        fields.put("traceId", standardTrace);
        fields.put("spanId", standardSpan);
        fields.put("message", event.getFormattedMessage()); fields.put("logger", event.getLoggerName());
        var pairs = event.getKeyValuePairs();
        if (pairs != null) for (var pair : pairs) {
            if ("traceId".equals(pair.key) && business == null && pair.value instanceof String candidate) business = candidate;
            else if (FIELDS.contains(pair.key) && safeValue(pair.value)) {
                Object value = pair.value;
                fields.put(pair.key, value instanceof UUID || value instanceof TemporalAccessor ? value.toString() : value);
            }
        }
        fields.put("businessTraceId", CorrelationIdPolicy.isValid(business) ? business : "");
        if (event.getThrowableProxy() != null) {
            var failure = event.getThrowableProxy(); fields.put("exceptionType", failure.getClassName());
            var stack = failure.getStackTraceElementProxyArray();
            if (stack != null) fields.put("exceptionFrames", Arrays.stream(stack).limit(32).map(frame -> frame.getStackTraceElement().toString()).toList());
        }
        return JSON.writeValueAsString(fields) + System.lineSeparator();
    }

    /**
     * 仅保留非零、固定长度的小写十六进制标准标识，不生成替代跨度。
     * @param value MDC中的候选值
     * @param length 协议要求长度
     * @return 有效标识或空串
     */
    private static String standardId(String value, int length) {
        if (value == null || value.length() != length || !value.matches("[0-9a-f]+") || value.chars().allMatch(character -> character == '0')) return "";
        return value;
    }

    /**
     * 只接受固定字段中的标量，禁止隐式序列化任意对象。
     * @param value 候选字段值
     * @return 是否为可输出标量
     */
    private static boolean safeValue(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof UUID || value instanceof TemporalAccessor;
    }
}
