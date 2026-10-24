package com.patrick.stratum.interfaceadapter.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** MVC外安全错误的标准JSON输出器，不序列化原异常、请求凭据或令牌。 */
@RequiredArgsConstructor
public class SecurityErrorWriter {
    private final JsonMapper json;
    private final MessageSource messages;

    /**
     * 输出带traceId的标准401/403/500响应，禁止缓存敏感请求的结果。
     * @param request 当前请求，仅用于选择文案语言
     * @param response 响应
     * @param code 安全错误码，只接受认证、授权或内部错误
     * @throws IOException 响应输出失败
     * @apiNote 必须在RequestTraceFilter内调用，例如{@code errors.write(request, response, CommonErrorCode.UNAUTHORIZED)}。
     */
    public void write(HttpServletRequest request, HttpServletResponse response, CommonErrorCode code) throws IOException {
        int status = switch (code) {
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case INTERNAL_ERROR -> 500;
            default -> throw new IllegalArgumentException("安全错误码不支持");
        };
        String message = messages.getMessage(code.getMessageKey(), null, code.getDefaultMessage(), request.getLocale());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        if (code == CommonErrorCode.UNAUTHORIZED) { response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); }
        json.writeValue(response.getOutputStream(), ApiResponse.failure(code, message, List.of()));
    }
}
