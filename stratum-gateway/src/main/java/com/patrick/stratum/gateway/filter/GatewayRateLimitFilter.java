package com.patrick.stratum.gateway.filter;

import lombok.RequiredArgsConstructor;
import com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 对已匹配ERP路由消耗直连IP共享额度，拒绝与存储故障都在上游调用前结束。 */
@Component
@RequiredArgsConstructor
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {
    private final RedisGatewayRateLimiter limiter;

    /** {@inheritDoc} */
    @Override public int getOrder() { return -100; }

    /** {@inheritDoc} */
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var peer = exchange.getRequest().getRemoteAddress();
        if (peer == null || peer.isUnresolved()) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        return limiter.consume(peer.getAddress())
                .onErrorMap(failure -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .flatMap(decision -> {
                    if (decision.allowed()) return chain.filter(exchange);
                    exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, Math.ceilDiv(decision.retryAfterMillis(), 1000))));
                    return Mono.error(new ResponseStatusException(decision.capacityExhausted() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS));
                });
    }
}
