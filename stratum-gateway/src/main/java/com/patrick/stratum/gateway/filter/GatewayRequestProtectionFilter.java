package com.patrick.stratum.gateway.filter;

import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 在转发前完整有界读取请求体，校验头容量并移除客户端自报代理来源。 */
@Component
@EnableConfigurationProperties(GatewayProtectionProperties.class)
public class GatewayRequestProtectionFilter implements WebFilter, Ordered {
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private final GatewayProtectionProperties properties;
    private final Semaphore buffers;

    /**
     * 创建本进程缓冲资源配额，不参与业务分布式并发控制。
     * @param properties 已校验的资源边界
     * @apiNote 示例：由Spring绑定stratum.gateway.protection参数后装配。
     */
    public GatewayRequestProtectionFilter(GatewayProtectionProperties properties) {
        this.properties = properties; this.buffers = new Semaphore(properties.maximumBufferedRequests());
    }

    /** {@inheritDoc} */
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }

    /** {@inheritDoc} */
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            validateHeaders(exchange.getRequest().getHeaders());
            if (exchange.getRequest().getHeaders().getContentLength() > properties.maximumBodyBytes()) {
                return Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE));
            }
            if (!buffers.tryAcquire()) return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
            return DataBufferUtils.join(exchange.getRequest().getBody(), properties.maximumBodyBytes())
                    .map(this::copyAndRelease).defaultIfEmpty(new byte[0])
                    .onErrorMap(DataBufferLimitException.class, failure -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE))
                    .timeout(properties.bodyTimeout(), Mono.error(new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT)))
                    .flatMap(body -> forward(exchange, chain, body))
                    .doFinally(signal -> buffers.release());
        });
    }

    /**
     * 在任何缓冲前校验头名称、控制字符及容量，不回显原值。
     * @param headers 原请求头
     * @throws ResponseStatusException 非法头400或容量超限431
     */
    private void validateHeaders(HttpHeaders headers) {
        long bytes = 0; int count = 0;
        for (var entry : headers.headerSet()) {
            String name = entry.getKey();
            if (!HEADER_NAME.matcher(name).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            for (String value : entry.getValue()) {
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    if ((character < 32 && character != '\t') || character == 127 || character > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
                bytes += name.length() + value.length() + 4; count++;
                if (bytes > properties.maximumHeaderBytes() || count > properties.maximumHeaderCount()) {
                    throw new ResponseStatusException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
                }
            }
        }
    }

    /**
     * 复制有界内容并立即释放Netty聚合缓冲，后续代理只持有普通字节数组。
     * @param buffer 有界聚合缓冲
     * @return 原始请求体
     */
    private byte[] copyAndRelease(DataBuffer buffer) {
        try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
        finally { DataBufferUtils.release(buffer); }
    }

    /**
     * 使用完整原体转发并规范长度，移除所有自报Forwarded/X-Forwarded及X-Real-IP。
     * @param exchange 请求交换
     * @param chain 后续过滤链
     * @param body 完整有界原体
     * @return 后续处理完成信号
     */
    private Mono<Void> forward(ServerWebExchange exchange, WebFilterChain chain, byte[] body) {
        var headers = new HttpHeaders(); headers.putAll(exchange.getRequest().getHeaders());
        headers.headerNames().stream().filter(name -> name.equalsIgnoreCase("Forwarded") || name.equalsIgnoreCase("X-Real-IP")
                || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-")).toList().forEach(headers::remove);
        headers.remove(HttpHeaders.TRANSFER_ENCODING); headers.setContentLength(body.length);
        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            /** {@inheritDoc} */
            @Override public HttpHeaders getHeaders() { return HttpHeaders.readOnlyHttpHeaders(headers); }
            /** {@inheritDoc} */
            @Override public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> body.length == 0 ? Flux.empty() : Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
        return chain.filter(exchange.mutate().request(request).build());
    }
}
