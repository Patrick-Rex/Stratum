package com.patrick.stratum.starter.web;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.ErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 全局异常处理器，负责异常到统一错误码的映射、i18n 文案解析与统一响应输出。
 * 适用场景：interface/query 层抛出异常后的统一兜底处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private final MessageSource messageSource;

    /**
     * 构造全局异常处理器。
     *
     * @param messageSource Spring 国际化消息解析器。
     * @return 无返回值。
     * @throws IllegalArgumentException 当 messageSource 为空时抛出异常。
     * @apiNote 使用示例：由 Spring 容器自动注入。
     */
    public GlobalExceptionHandler(MessageSource messageSource) {
        if (messageSource == null) {
            throw new IllegalArgumentException("messageSource cannot be null");
        }
        this.messageSource = messageSource;
    }

    /**
     * 统一处理所有未被其他处理器捕获的异常并输出标准响应。
     *
     * @param exception 当前请求抛出的异常对象。
     * @param request HTTP 请求对象，用于解析 locale 头。
     * @return 包装为统一模型的失败响应，包含本地化 message 与 traceId。
     * @throws IllegalStateException 当请求上下文不可用时由底层组件抛出。
     * @apiNote 使用示例：由 Spring MVC 在异常链末端自动回调。
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleThrowable(Throwable exception, HttpServletRequest request) {
        MappedError mappedError = mapException(exception);
        Locale locale = resolveLocale(request);
        String localizedMessage = messageSource.getMessage(
                mappedError.errorCode().getMessageKey(),
                null,
                mappedError.errorCode().getDefaultMessage(),
                locale
        );

        if (mappedError.errorCode().getCode() >= CommonErrorCode.INTERNAL_ERROR.getCode()) {
            LOGGER.error("Unhandled exception captured, traceId={}", TraceIdContext.getRequiredTraceId(), exception);
        }

        ApiResponse<Void> response = ApiResponse.failure(mappedError.errorCode(), localizedMessage, mappedError.details());
        return ResponseEntity.status(toHttpStatus(mappedError.errorCode())).body(response);
    }

    /**
     * 将异常映射为统一错误码与明细列表。
     *
     * @param exception 原始异常对象。
     * @return 映射后的错误码与明细信息。
     * @throws 无。
     * @apiNote 使用示例：MappedError mapped = mapException(ex)
     */
    public MappedError mapException(Throwable exception) {
        if (exception instanceof StratumException stratumException) {
            return new MappedError(stratumException.getErrorCode(), stratumException.getDetails());
        }
        if (exception instanceof IllegalArgumentException illegalArgumentException) {
            String detail = illegalArgumentException.getMessage();
            List<String> details = (detail == null || detail.isBlank()) ? List.of() : List.of(detail);
            return new MappedError(CommonErrorCode.BAD_REQUEST, details);
        }
        return new MappedError(CommonErrorCode.INTERNAL_ERROR, List.of());
    }

    /**
     * 解析请求 locale，优先读取 Accept-Language 头，缺失或非法时回退默认中文。
     *
     * @param request HTTP 请求对象。
     * @return 解析后的 Locale 对象。
     * @throws 无。
     * @apiNote 使用示例：Locale locale = resolveLocale(request)
     */
    public Locale resolveLocale(HttpServletRequest request) {
        if (request == null) {
            return DEFAULT_LOCALE;
        }
        String headerValue = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (headerValue == null || headerValue.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String primaryTag = headerValue.split(",")[0].trim();
        Locale parsed = Locale.forLanguageTag(primaryTag);
        if (parsed.getLanguage() == null || parsed.getLanguage().isBlank()) {
            return DEFAULT_LOCALE;
        }
        if (Locale.US.getLanguage().equalsIgnoreCase(parsed.getLanguage())) {
            return Locale.US;
        }
        if (Locale.SIMPLIFIED_CHINESE.getLanguage().equalsIgnoreCase(parsed.getLanguage())) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return DEFAULT_LOCALE;
    }

    /**
     * 将业务错误码映射为 HTTP 状态码。
     *
     * @param errorCode 统一错误码对象。
     * @return 对应的 HTTP 状态码。
     * @throws 无。
     * @apiNote 使用示例：HttpStatus status = toHttpStatus(CommonErrorCode.BAD_REQUEST)
     */
    public HttpStatus toHttpStatus(ErrorCode errorCode) {
        int code = errorCode.getCode();
        if (code >= 40000 && code < 40100) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code >= 40100 && code < 40200) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code >= 40300 && code < 40400) {
            return HttpStatus.FORBIDDEN;
        }
        if (code >= 40400 && code < 40500) {
            return HttpStatus.NOT_FOUND;
        }
        if (code >= 40900 && code < 41000) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * 异常映射结果载体，负责承载统一错误码与错误明细。
     *
     * @param errorCode 统一错误码。
     * @param details 错误明细。
     */
    public record MappedError(ErrorCode errorCode, List<String> details) {
    }
}
