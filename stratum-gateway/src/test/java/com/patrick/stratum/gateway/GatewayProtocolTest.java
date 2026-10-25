package com.patrick.stratum.gateway;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.gateway.filter.GatewayCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证关联和业务协议透传，响应提交覆盖错误上游关联头，并发不依赖线程状态。 */
class GatewayProtocolTest extends GatewayHttpIntegrationTest {
    private static final String PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /**
     * 合法W3C及未来版本扩展原样透传，Bearer/版本/幂等键不改写，关联响应与发给上游的值一致。
     * @throws Exception HTTP或启动失败
     */
    @Test
    void preservesCorrelationW3cAndBusinessHeaders() throws Exception {
        try (var stable = upstream("stable"); var gray = upstream("gray"); var gateway = gateway(stable, gray, true, false, "")) {
            for (String parent : List.of(PARENT, "01" + PARENT.substring(2) + "-future")) {
                var supplied = Map.of(CorrelationIdPolicy.TRACE_HEADER, "trace-001", CorrelationIdPolicy.REQUEST_HEADER, "request-001",
                        "traceparent", parent, "tracestate", "vendor=value", "Authorization", "Bearer test-access",
                        "X-API-Version", "1.0.0", "Idempotency-Key", "request-key-001");
                var response = request(gateway, "/api/test", supplied, "payload"); assertEquals(200, response.statusCode());
                var headers = JsonMapper.builder().build().readTree(response.body()).path("headers");
                for (var entry : supplied.entrySet()) assertEquals(entry.getValue(), headers.path(entry.getKey()).asText());
                assertEquals("trace-001", response.headers().firstValue(CorrelationIdPolicy.TRACE_HEADER).orElseThrow());
                assertEquals("request-001", response.headers().firstValue(CorrelationIdPolicy.REQUEST_HEADER).orElseThrow());
            }
        }
    }

    /**
     * 缺失或非法关联值替换成UUID，重复头不采信；无效W3C和孤立tracestate不向下游传播。
     * @throws Exception HTTP或启动失败
     */
    @Test
    void replacesAmbiguousIdsAndRemovesInvalidTraceParents() throws Exception {
        try (var stable = upstream("stable"); var gray = upstream("gray"); var gateway = gateway(stable, gray, true, false, "")) {
            var absent = request(gateway, "/api/test", Map.of("tracestate", "orphan=value"), null);
            String generated = absent.headers().firstValue(CorrelationIdPolicy.TRACE_HEADER).orElseThrow();
            assertEquals(generated, UUID.fromString(generated).toString());
            assertEquals(generated, absent.headers().firstValue(CorrelationIdPolicy.REQUEST_HEADER).orElseThrow());
            var received = JsonMapper.builder().build().readTree(absent.body()).path("headers");
            assertEquals(generated, received.path(CorrelationIdPolicy.TRACE_HEADER).asText()); assertEquals("", received.path("tracestate").asText());
            var invalid = request(gateway, "/api/test", Map.of(CorrelationIdPolicy.TRACE_HEADER, "a".repeat(65), CorrelationIdPolicy.REQUEST_HEADER, "bad value"), null);
            String replaced = invalid.headers().firstValue(CorrelationIdPolicy.TRACE_HEADER).orElseThrow();
            assertEquals(replaced, UUID.fromString(replaced).toString()); assertEquals(replaced, invalid.headers().firstValue(CorrelationIdPolicy.REQUEST_HEADER).orElseThrow());
            var duplicated = HttpRequest.newBuilder(gatewayUri(gateway, "/api/test")).timeout(Duration.ofSeconds(10))
                    .header(CorrelationIdPolicy.TRACE_HEADER, "first").header(CorrelationIdPolicy.TRACE_HEADER, "second")
                    .header(CorrelationIdPolicy.REQUEST_HEADER, "left").header(CorrelationIdPolicy.REQUEST_HEADER, "right")
                    .header("traceparent", PARENT).header("traceparent", PARENT).GET().build();
            var duplicateResponse = send(duplicated); String unique = duplicateResponse.headers().firstValue(CorrelationIdPolicy.TRACE_HEADER).orElseThrow();
            assertEquals(unique, UUID.fromString(unique).toString());
            var duplicateHeaders = JsonMapper.builder().build().readTree(duplicateResponse.body()).path("headers");
            assertEquals(unique, duplicateHeaders.path(CorrelationIdPolicy.REQUEST_HEADER).asText()); assertEquals("", duplicateHeaders.path("traceparent").asText());
            for (String parent : List.of("invalid", "ff" + PARENT.substring(2), PARENT.replace("4bf92f3577b34da6a3ce929d0e0e4736", "0".repeat(32)),
                    PARENT.replace("00f067aa0ba902b7", "0".repeat(16)), PARENT.toUpperCase(java.util.Locale.ROOT), PARENT + "-invalid",
                    "01" + PARENT.substring(2) + "-" + "x".repeat(1024))) {
                var response = request(gateway, "/api/test", Map.of("traceparent", parent, "tracestate", "vendor=value"), null);
                var headers = JsonMapper.builder().build().readTree(response.body()).path("headers");
                assertEquals("", headers.path("traceparent").asText()); assertEquals("", headers.path("tracestate").asText());
            }
        }
    }

    /**
     * 固定16个并发请求各自保持关联值，不因事件线程复用串入其他请求。
     * @throws Exception HTTP、启动或工作线程失败
     */
    @Test
    void isolatesConcurrentRequestCorrelation() throws Exception {
        try (var stable = upstream("stable"); var gray = upstream("gray"); var gateway = gateway(stable, gray, true, false, "");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var responses = IntStream.range(0, 16).mapToObj(index -> executor.submit(() -> request(gateway, "/api/test",
                    Map.of(CorrelationIdPolicy.TRACE_HEADER, "trace-" + index, CorrelationIdPolicy.REQUEST_HEADER, "request-" + index), null))).toList();
            for (int index = 0; index < responses.size(); index++) {
                var response = responses.get(index).get(10, TimeUnit.SECONDS); assertEquals(200, response.statusCode());
                var headers = JsonMapper.builder().build().readTree(response.body()).path("headers");
                assertEquals("trace-" + index, headers.path(CorrelationIdPolicy.TRACE_HEADER).asText());
                assertEquals("request-" + index, headers.path(CorrelationIdPolicy.REQUEST_HEADER).asText());
                assertEquals("trace-" + index, response.headers().firstValue(CorrelationIdPolicy.TRACE_HEADER).orElseThrow());
                assertEquals("request-" + index, response.headers().firstValue(CorrelationIdPolicy.REQUEST_HEADER).orElseThrow());
            }
        }
    }

    /** Reactor上下文和交换属性携带相同标识，已有线程上下文保持不变。 */
    @Test
    void usesReactiveContextWithoutChangingThreadState() {
        var previous = TraceIdContext.getTraceId(); TraceIdContext.setTraceId("thread-before");
        try {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").header(CorrelationIdPolicy.TRACE_HEADER, "reactive-trace"));
            new GatewayCorrelationFilter().filter(exchange, next -> Mono.deferContextual(context -> {
                assertEquals("reactive-trace", context.get(GatewayCorrelationFilter.TRACE_ATTRIBUTE));
                assertEquals("reactive-trace", next.getAttribute(GatewayCorrelationFilter.TRACE_ATTRIBUTE));
                assertEquals("reactive-trace", next.getRequest().getHeaders().getFirst(CorrelationIdPolicy.TRACE_HEADER));
                return Mono.empty();
            })).block(Duration.ofSeconds(1));
            assertEquals("thread-before", TraceIdContext.getTraceId().orElseThrow());
        } finally { if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow()); else TraceIdContext.clear(); }
    }
}
