package com.patrick.stratum.interfaceadapter.response;

import java.nio.charset.StandardCharsets;

import com.patrick.stratum.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

/** 统一包装业务 JSON 响应，限定 Interface/Query 控制器以保留第三方协议。 */
@RestControllerAdvice(basePackages = {"com.patrick.stratum.interfaceadapter", "com.patrick.stratum.query"})
@RequiredArgsConstructor
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    private final JsonMapper jsonMapper;

    /** {@inheritDoc} */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType)
                || StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    /** {@inheritDoc} */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?>) return body;
        if (response instanceof ServletServerHttpResponse servletResponse) {
            int status = servletResponse.getServletResponse().getStatus();
            if (status == 204 || status == 304) return body;
            if (status >= 400) {
                throw new IllegalStateException("业务错误响应必须使用 ApiResponse 或抛出业务异常");
            }
        }
        boolean stringConverter = StringHttpMessageConverter.class.isAssignableFrom(converterType);
        if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                && !contentType.getSubtype().endsWith("+json")
                && !(stringConverter && MediaType.TEXT_PLAIN.isCompatibleWith(contentType))) {
            return body;
        }
        ApiResponse<Object> wrapped = ApiResponse.success(body);
        response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        if (stringConverter) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String serialized = jsonMapper.writeValueAsString(wrapped);
            response.getHeaders().setContentLength(serialized.getBytes(StandardCharsets.UTF_8).length);
            return serialized;
        }
        return wrapped;
    }
}
