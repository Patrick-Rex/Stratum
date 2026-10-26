package com.patrick.stratum.gateway.filter;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.cloud.gateway.filter.headers.observation.DefaultGatewayObservationConvention;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayObservationConvention;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationConvention;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 在最终响应提交时计时，并在创建原生HTTP指标ID之前约束方法与URI标签。 */
@Component
@RequiredArgsConstructor
public class GatewayMetricsFilter implements WebFilter, Ordered {
    private static final Set<String> URI_TAGS = Set.of("/api/**", "/auth/**", "/v3/api-docs/**", "/doc.html", "/webjars/**", "/swagger-ui/**",
            "/actuator/health", "/actuator/prometheus", "UNKNOWN", "NOT_FOUND", "REDIRECTION");
    private final MeterRegistry registry;

    /** {@inheritDoc} */
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 2; }

    /** {@inheritDoc} */
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime(); var observed = new AtomicBoolean();
        exchange.getResponse().beforeCommit(() -> {
            var status = exchange.getResponse().getStatusCode(); record(exchange, started, status == null ? 200 : status.value(), observed);
            return Mono.empty();
        });
        return chain.filter(exchange).doOnCancel(() -> record(exchange, started, 499, observed));
    }

    /**
     * 原生HTTP观测在注册任何Meter.Id前归并可变方法/URI，避免仅映射导出标签仍积累原ID缓存。
     * @return 保留标准状态/结果/异常语义的有界观测约定
     * @apiNote 示例：由Boot自动收集；动态URL统一OTHER，不能依赖指标定位某个业务对象。
     */
    @Bean
    public static ServerRequestObservationConvention gatewayHttpObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            /** {@inheritDoc} */
            @Override protected KeyValue method(ServerRequestObservationContext context) {
                var request = context.getCarrier(); return KeyValue.of("method", methodTag(request == null ? "" : request.getMethod().name()));
            }
            /** {@inheritDoc} */
            @Override protected KeyValue uri(ServerRequestObservationContext context) {
                var original = super.uri(context); String value = original.getValue();
                return KeyValue.of(original.getKey(), URI_TAGS.contains(value) ? value : "OTHER");
            }
        };
    }

    /**
     * Gateway出站观测也在注册前归并方法与上游别名，不把实际地址或请求URL作为指标/追踪属性。
     * @return 继续保留出站观测及传播功能的有界约定
     * @apiNote 示例：出站http.client.requests只出现固定路由别名，原始URI不会进入标签。
     */
    @Bean
    public static GatewayObservationConvention gatewayClientObservationConvention() {
        return new DefaultGatewayObservationConvention() {
            /** {@inheritDoc} */
            @Override public KeyValues getLowCardinalityKeyValues(GatewayContext context) {
                String route = routeTag(context.getServerWebExchange()); var response = context.getResponse();
                String status = response == null || response.getStatusCode() == null ? "UNKNOWN" : Integer.toString(response.getStatusCode().value());
                return KeyValues.of(KeyValue.of("http.method", methodTag(context.getRequest().getMethod().name())),
                        KeyValue.of("spring.cloud.gateway.route.id", route), KeyValue.of("spring.cloud.gateway.route.uri", route), KeyValue.of("http.status_code", status));
            }
            /** {@inheritDoc} */
            @Override public KeyValues getHighCardinalityKeyValues(GatewayContext context) { return KeyValues.empty(); }
            /** {@inheritDoc} */
            @Override public String getContextualName(GatewayContext context) {
                return "HTTP " + methodTag(context.getRequest().getMethod().name()) + " " + routeTag(context.getServerWebExchange());
            }
        };
    }

    /**
     * 对固定方法/路由/状态的计时器记录一次样本，既覆盖成功，也覆盖限流和来源/体防护拒绝。
     * @param exchange 请求交换
     * @param started 单调起始时间
     * @param status 最终提交状态或取消499
     * @param observed 当前请求一次性标记
     */
    private void record(ServerWebExchange exchange, long started, int status, AtomicBoolean observed) {
        if (!observed.compareAndSet(false, true)) return;
        registry.timer("stratum.gateway.requests", "method", methodTag(exchange.getRequest().getMethod().name()),
                "route", routeTag(exchange), "status", Integer.toString(status))
                .record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
    }

    /**
     * 归并所有扩展方法为OTHER，在注册前保持方法集合固定。
     * @param method 原始方法名
     * @return 标准方法或OTHER
     */
    private static String methodTag(String method) {
        return switch (method) { case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT" -> method; default -> "OTHER"; };
    }

    /**
     * 只输出已登记路由别名，不使用原始URI、上游地址或动态配置的名称。
     * @param exchange 请求交换
     * @return 固定路由类别
     */
    private static String routeTag(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null) return switch (route.getId()) { case "erp-stable" -> "stable"; case "erp-gray" -> "gray"; default -> "other"; };
        return exchange.getRequest().getPath().value().startsWith("/actuator/") ? "local" : "unmatched";
    }
}
