package com.patrick.stratum.gateway.filter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import io.netty.channel.ConnectTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import tools.jackson.databind.json.JsonMapper;

/** 将网关自身错误转换为统一安全外壳，不二次包装正常上游响应或已经提交的数据流。 */
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private final JsonMapper json;

    /** {@inheritDoc} */
    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        var response = exchange.getResponse(); if (response.isCommitted()) return Mono.error(failure);
        int status = status(failure); CommonErrorCode code = code(status);
        String traceId = exchange.getAttribute(GatewayCorrelationFilter.TRACE_ATTRIBUTE);
        if (!CorrelationIdPolicy.isValid(traceId)) traceId = CorrelationIdPolicy.resolve(null);
        exchange.getAttributes().put(GatewayCorrelationFilter.TRACE_ATTRIBUTE, traceId);
        String message = switch (status) { case 502 -> "上游服务暂不可用"; case 504 -> "上游响应超时"; case 503 -> "入口服务暂不可用"; default -> code.getDefaultMessage(); };
        byte[] body = json.writeValueAsBytes(ApiResponse.failure(code, message, List.of(), traceId));
        response.setStatusCode(HttpStatusCode.valueOf(status)); var headers = response.getHeaders();
        for (String name : List.of(HttpHeaders.CONTENT_LENGTH, HttpHeaders.TRANSFER_ENCODING, HttpHeaders.CONTENT_ENCODING,
                HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.LOCATION, HttpHeaders.SET_COOKIE)) headers.remove(name);
        headers.setContentType(MediaType.APPLICATION_JSON); headers.setCacheControl("no-store"); headers.setPragma("no-cache");
        headers.set(CorrelationIdPolicy.TRACE_HEADER, traceId);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * 有界检查异常链，连接/传输故障502、超时504；不读取异常文案或上游地址。
     * @param failure 原失败
     * @return 安全HTTP错误状态
     */
    private int status(Throwable failure) {
        int status = 500; Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof ResponseStatusException declared) {
                int value = declared.getStatusCode().value(); return value >= 400 && value <= 599 ? value : 500;
            }
            if (current instanceof TimeoutException || current instanceof io.netty.handler.timeout.TimeoutException || current instanceof ConnectTimeoutException) return 504;
            if (current instanceof IOException || current instanceof AbortedException) status = 502;
        }
        return status;
    }

    /**
     * 沿用既有错误码段，代理5xx故障使用50000，不将内部传输细节写入明细。
     * @param status HTTP状态
     * @return 标准错误码
     */
    private CommonErrorCode code(int status) {
        return switch (status) {
            case 401 -> CommonErrorCode.UNAUTHORIZED; case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND; case 409 -> CommonErrorCode.CONFLICT;
            case 429 -> CommonErrorCode.RATE_LIMITED;
            default -> status < 500 ? CommonErrorCode.BAD_REQUEST : CommonErrorCode.INTERNAL_ERROR;
        };
    }
}
