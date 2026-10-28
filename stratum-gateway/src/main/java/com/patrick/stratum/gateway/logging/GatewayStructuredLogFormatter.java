package com.patrick.stratum.gateway.logging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import tools.jackson.databind.json.JsonMapper;

/** 使用Boot结构化日志扩展输出统一JSON字段，只收集已登记的访问属性，不展开请求或异常对象。 */
public class GatewayStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ACCESS_FIELDS = List.of("upstream", "status", "latencyMs", "clientIp");
    private static final String ENVIRONMENT = System.getenv().getOrDefault("STRATUM_ENV", "local");

    /** {@inheritDoc} */
    @Override public String format(ILoggingEvent event) {
        Map<String, String> context = event.getMDCPropertyMap(); if (context == null) context = Map.of();
        var fields = new LinkedHashMap<String, Object>();
        fields.put("timestamp", event.getInstant().toString()); fields.put("level", event.getLevel().toString());
        fields.put("service", "stratum-gateway"); fields.put("env", ENVIRONMENT);
        fields.put("traceId", standardId(context.get("trace_id"), 32));
        fields.put("spanId", standardId(context.get("span_id"), 16));
        String business = "";
        fields.put("message", event.getFormattedMessage()); fields.put("logger", event.getLoggerName());
        var pairs = event.getKeyValuePairs();
        if (pairs != null) for (var pair : pairs) {
            if ("businessTraceId".equals(pair.key) && pair.value instanceof String candidate && CorrelationIdPolicy.isValid(candidate)) business = candidate;
            else if (ACCESS_FIELDS.contains(pair.key) && (pair.value instanceof String || pair.value instanceof Number)) fields.put(pair.key, pair.value);
        }
        fields.put("businessTraceId", business);
        return JSON.writeValueAsString(fields) + System.lineSeparator();
    }
    /**
     * 仅保留Agent提供的非零小写十六进制标准标识。
     * @param value MDC候选值
     * @param length 协议要求的长度
     * @return 有效标识或空字符串
     */
    private static String standardId(String value, int length) {
        if (value == null || value.length() != length || !value.matches("[0-9a-f]+") || value.chars().allMatch(character -> character == '0')) return "";
        return value;
    }
}
