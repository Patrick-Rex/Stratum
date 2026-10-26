package com.patrick.stratum.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.patrick.stratum.gateway.filter.GatewayAccessLogFilter;
import com.patrick.stratum.gateway.filter.GatewayCorrelationFilter;
import com.patrick.stratum.gateway.logging.GatewayStructuredLogFormatter;
import com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证实际控制台JSON、安全字段及提交/取消生命周期，不记录任何原始请求内容。 */
@ExtendWith(OutputCaptureExtension.class)
class GatewayAccessLogTest extends GatewayHttpIntegrationTest {
    /**
     * 成功、灰度、来源拒绝、未知、限流和代理故障均使用最终状态及请求关联字段。
     * @param output 实际控制台捕获
     * @throws Exception 本机HTTP失败
     */
    @Test void logsActualFinalStatusesWithoutCredentialsOrRequestData(CapturedOutput output) throws Exception {
        try (var stable = upstream(exchange -> {
            exchange.getRequestBody().readNBytes(4096);
            if (exchange.getRequestURI().getPath().equals("/api/fault")) return;
            exchange.getResponseHeaders().set("Set-Cookie", "cookie-secret"); exchange.sendResponseHeaders(204, -1);
        }); var gray = upstream("gray"); var gateway = gateway(stable, gray, true, true, "",
                "--stratum.gateway.management.allowed-cidrs=192.0.2.0/24",
                "--logging.level.com.patrick.stratum.gateway.filter.GatewayAccessLogFilter=INFO")) {
            assertEquals(204, request(gateway, "/api/private?token=query-secret", Map.of("X-Trace-Id", "log-stable", "Authorization", "Bearer token-secret",
                    "Cookie", "cookie-input-secret", "Idempotency-Key", "key-secret"), "password=body-secret").statusCode());
            assertEquals(200, request(gateway, "/api/read", Map.of("X-Trace-Id", "log-gray", "X-Stratum-Release", "v2.0"), null).statusCode());
            assertEquals(403, request(gateway, "/api/auth/accounts", Map.of("X-Trace-Id", "log-denied"), null).statusCode());
            assertEquals(404, request(gateway, "/unknown?password=query-secret", Map.of("X-Trace-Id", "log-missing"), null).statusCode());
            assertEquals(200, request(gateway, "/actuator/health", Map.of("X-Trace-Id", "log-health"), null).statusCode());
            assertEquals(502, request(gateway, "/api/fault", Map.of("X-Trace-Id", "log-fault"), "body-secret").statusCode());
            var limiter = gateway.getBean(RedisGatewayRateLimiter.class);
            when(limiter.consume(any(java.net.InetAddress.class))).thenReturn(Mono.just(new RedisGatewayRateLimiter.Decision(false, 0, 1000, false)));
            assertEquals(429, request(gateway, "/api/rate", Map.of("X-Trace-Id", "log-rate"), null).statusCode());
        }
        var events = output.getOut().lines().filter(line -> line.startsWith("{"))
                .map(line -> JsonMapper.builder().build().readTree(line)).filter(event -> event.path("message").asText().equals("网关访问")).toList();
        assertEquals(7, events.size());
        var expected = Map.of("log-stable", 204, "log-gray", 200, "log-denied", 403, "log-missing", 404,
                "log-health", 200, "log-fault", 502, "log-rate", 429);
        for (var event : events) {
            assertEquals(expected.get(event.path("traceId").asText()), event.path("status").asInt());
            assertEquals("stratum-gateway", event.path("service").asText()); assertEquals("INFO", event.path("level").asText());
            assertTrue(event.has("env")); assertTrue(event.has("spanId")); assertDoesNotThrow(() -> Instant.parse(event.path("timestamp").asText()));
            assertEquals("127.0.0.1", event.path("clientIp").asText()); assertTrue(event.path("latencyMs").asLong() >= 0);
            assertTrue(List.of("stable", "gray", "local", "unmatched").contains(event.path("upstream").asText()));
            assertFalse(event.toString().contains("secret")); assertFalse(event.has("Authorization")); assertFalse(event.has("body"));
        }
        assertEquals("gray", events.stream().filter(event -> event.path("traceId").asText().equals("log-gray")).findFirst().orElseThrow().path("upstream").asText());
    }

    /** 格式器只收集登记属性，安全转义换行和引号，并读取已有OTel MDC而不生成虚假跨度。 */
    @Test void formatterUsesSafeFieldsAndExistingSpanContext() {
        var event = mock(ILoggingEvent.class); when(event.getInstant()).thenReturn(Instant.parse("2026-10-26T00:00:00Z"));
        when(event.getLevel()).thenReturn(Level.INFO); when(event.getLoggerName()).thenReturn("gateway-test"); when(event.getFormattedMessage()).thenReturn("固定访问事件");
        when(event.getMDCPropertyMap()).thenReturn(Map.of("trace_id", "existing-trace", "span_id", "existing-span", "Authorization", "mdc-secret"));
        when(event.getKeyValuePairs()).thenReturn(List.of(new KeyValuePair("traceId", "safe\"line\nnext"), new KeyValuePair("status", 200),
                new KeyValuePair("Authorization", "pair-secret"), new KeyValuePair("body", "body-secret")));
        String json = new GatewayStructuredLogFormatter().format(event);
        assertEquals(1, json.lines().count()); var parsed = JsonMapper.builder().build().readTree(json);
        assertEquals("safe\"line\nnext", parsed.path("traceId").asText()); assertEquals("existing-span", parsed.path("spanId").asText());
        assertEquals(200, parsed.path("status").asInt()); assertFalse(json.contains("secret"));
    }

    /** 取消后记录499，稍后的提交回调不能重复记录同一请求。 */
    @Test void cancellationAndLaterCommitProduceOnlyOneEvent() {
        var logger = (Logger) LoggerFactory.getLogger(GatewayAccessLogFilter.class); Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender); logger.setLevel(Level.INFO);
        try {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/cancel"));
            exchange.getAttributes().put(GatewayCorrelationFilter.TRACE_ATTRIBUTE, "cancelled-trace");
            var request = new GatewayAccessLogFilter().filter(exchange, ignored -> Mono.never()).subscribe(); request.dispose();
            exchange.getResponse().setComplete().block(Duration.ofSeconds(2));
            assertEquals(1, appender.list.size());
            var event = JsonMapper.builder().build().readTree(new GatewayStructuredLogFormatter().format(appender.list.getFirst()));
            assertEquals(499, event.path("status").asInt()); assertEquals("cancelled-trace", event.path("traceId").asText());
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }
}
