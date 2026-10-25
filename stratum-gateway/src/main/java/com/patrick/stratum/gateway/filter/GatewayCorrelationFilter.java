package com.patrick.stratum.gateway.filter;

import java.util.List;
import java.util.regex.Pattern;

import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 响应式入口关联过滤器，使用交换属性及Reactor上下文传播标识，不绑定事件线程。 */
@Component
public class GatewayCorrelationFilter implements WebFilter, Ordered {
    /** 请求级traceId属性及Reactor上下文键，供错误响应和观测读取。 */
    public static final String TRACE_ATTRIBUTE = GatewayCorrelationFilter.class.getName() + ".traceId";
    private static final Pattern TRACE_PARENT_PREFIX = Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_PARENT = "0".repeat(16);
    private static final int MAX_TRACE_PARENT_LENGTH = 1024;

    /** {@inheritDoc} */
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    /** {@inheritDoc} */
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var supplied = exchange.getRequest().getHeaders();
        String traceId = CorrelationIdPolicy.resolve(single(supplied, CorrelationIdPolicy.TRACE_HEADER));
        String suppliedRequestId = single(supplied, CorrelationIdPolicy.REQUEST_HEADER);
        String requestId = CorrelationIdPolicy.isValid(suppliedRequestId) ? suppliedRequestId : traceId;
        String parent = single(supplied, "traceparent"); boolean keepParent = validTraceParent(parent);
        exchange.getAttributes().put(TRACE_ATTRIBUTE, traceId);
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.set(CorrelationIdPolicy.TRACE_HEADER, traceId); headers.set(CorrelationIdPolicy.REQUEST_HEADER, requestId);
            if (!keepParent) { headers.remove("traceparent"); headers.remove("tracestate"); }
        }).build();
        exchange.getResponse().beforeCommit(() -> {
            var headers = exchange.getResponse().getHeaders();
            headers.set(CorrelationIdPolicy.TRACE_HEADER, traceId); headers.set(CorrelationIdPolicy.REQUEST_HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build()).contextWrite(context -> context.put(TRACE_ATTRIBUTE, traceId));
    }

    /**
     * 只接受单值关联头，重复值按缺失处理以消除入口歧义。
     * @param headers 入站头
     * @param name 固定头名
     * @return 唯一值或null
     */
    private String single(HttpHeaders headers, String name) {
        List<String> values = headers.get(name); return values != null && values.size() == 1 ? values.getFirst() : null;
    }

    /**
     * 校验有界W3C前缀、非零标识及版本长度，代理保留未来版本扩展，不创建或修改跨度。
     * @param value 单个traceparent值
     * @return 可原样透传时为true
     */
    private boolean validTraceParent(String value) {
        if (value == null || value.length() < 55 || value.length() > MAX_TRACE_PARENT_LENGTH
                || !TRACE_PARENT_PREFIX.matcher(value).region(0, 55).matches() || value.startsWith("ff-")
                || value.regionMatches(3, ZERO_TRACE, 0, 32) || value.regionMatches(36, ZERO_PARENT, 0, 16)) return false;
        for (int index = 55; index < value.length(); index++) if (value.charAt(index) < 33 || value.charAt(index) > 126) return false;
        return value.length() == 55 || (!value.startsWith("00-") && value.charAt(55) == '-');
    }
}
