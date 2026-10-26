package com.patrick.stratum.gateway.logging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import tools.jackson.databind.json.JsonMapper;

/** 使用Boot结构化日志扩展输出统一JSON字段，只收集已登记的访问属性，不展开请求或异常对象。 */
public class GatewayStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ACCESS_FIELDS = List.of("upstream", "status", "latencyMs", "traceId", "clientIp");
    private static final String ENVIRONMENT = System.getenv().getOrDefault("STRATUM_ENV", "local");

    /** {@inheritDoc} */
    @Override public String format(ILoggingEvent event) {
        Map<String, String> context = event.getMDCPropertyMap(); if (context == null) context = Map.of();
        var fields = new LinkedHashMap<String, Object>();
        fields.put("timestamp", event.getInstant().toString()); fields.put("level", event.getLevel().toString());
        fields.put("service", "stratum-gateway"); fields.put("env", ENVIRONMENT);
        fields.put("traceId", context.getOrDefault("traceId", context.getOrDefault("trace_id", "")));
        fields.put("spanId", context.getOrDefault("spanId", context.getOrDefault("span_id", "")));
        fields.put("message", event.getFormattedMessage()); fields.put("logger", event.getLoggerName());
        var pairs = event.getKeyValuePairs();
        if (pairs != null) for (var pair : pairs) {
            if (ACCESS_FIELDS.contains(pair.key) && (pair.value instanceof String || pair.value instanceof Number)) fields.put(pair.key, pair.value);
        }
        return JSON.writeValueAsString(fields) + System.lineSeparator();
    }
}
