package com.patrick.stratum.gateway.filter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 在响应提交时记录一次安全访问事件，覆盖路由、拒绝及未知路径，不输出原始请求信息。 */
@Slf4j
@Component
public class GatewayAccessLogFilter implements WebFilter, Ordered {
    /** {@inheritDoc} */
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1; }

    /** {@inheritDoc} */
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime(); var logged = new AtomicBoolean();
        exchange.getResponse().beforeCommit(() -> {
            var status = exchange.getResponse().getStatusCode(); record(exchange, started, status == null ? 200 : status.value(), logged);
            return Mono.empty();
        });
        return chain.filter(exchange).doOnCancel(() -> record(exchange, started, 499, logged));
    }

    /**
     * 记录提交前耗时及固定字段，取消用499标记；原子标记避免取消与提交竞争产生双日志。
     * @param exchange 请求交换
     * @param started 单调起始时间
     * @param status 实际提交状态或取消标记
     * @param logged 本请求一次性标记
     */
    private void record(ServerWebExchange exchange, long started, int status, AtomicBoolean logged) {
        if (!logged.compareAndSet(false, true)) return;
        var peer = exchange.getRequest().getRemoteAddress();
        String clientIp = peer == null || peer.isUnresolved() ? "unknown" : peer.getAddress().getHostAddress();
        String traceId = exchange.getAttributeOrDefault(GatewayCorrelationFilter.TRACE_ATTRIBUTE, "");
        log.atInfo().addKeyValue("upstream", upstream(exchange)).addKeyValue("status", status)
                .addKeyValue("latencyMs", Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)))
                .addKeyValue("businessTraceId", traceId).addKeyValue("clientIp", clientIp).log("网关访问");
    }

    /**
     * 用固定路由名标识上游，不记录地址凭据、请求路径、查询或动态路由名称。
     * @param exchange 请求交换
     * @return 固定低基数上游标识
     */
    private String upstream(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null) return switch (route.getId()) { case "erp-stable" -> "stable"; case "erp-gray" -> "gray"; default -> "other"; };
        return exchange.getRequest().getPath().value().startsWith("/actuator/") ? "local" : "unmatched";
    }
}
