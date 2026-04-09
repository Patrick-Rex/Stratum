package com.patrick.stratum.common.api;

import java.util.List;

import com.patrick.stratum.common.error.ErrorCode;
import com.patrick.stratum.common.trace.TraceIdContext;

/**
 * 统一 API 响应对象，负责承载成功与失败场景下的标准化响应结构。
 * 适用场景：interface/query 层对外返回 HTTP 响应体时的统一封装。
 */
public final class ApiResponse<T> {

    private static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "OK";

    private final int code;
    private final String message;
    private final T data;
    private final List<String> details;
    private final String traceId;

    private ApiResponse(int code, String message, T data, List<String> details, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.details = details == null ? List.of() : List.copyOf(details);
        this.traceId = traceId;
    }

    /**
     * 构建成功响应，message 固定为 OK。
     *
     * @param data 成功返回的数据体，可为 null。
     * @return 成功响应对象，code 固定为 0。
     * @throws IllegalStateException 当当前线程缺少 traceId 上下文时抛出异常。
     * @apiNote 使用示例：ApiResponse.success(userDto)
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, List.of(), TraceIdContext.getRequiredTraceId());
    }

    /**
     * 按错误码构建失败响应，message 使用错误码默认文案。
     *
     * @param errorCode 统一错误码对象，不能为空。
     * @param details 错误明细列表；无明细时请传空列表。
     * @return 失败响应对象，code 为非 0。
     * @throws IllegalArgumentException 当 errorCode 为空时抛出异常。
     * @apiNote 使用示例：ApiResponse.failure(CommonErrorCode.BAD_REQUEST, List.of("name 不能为空"))
     */
    public static <T> ApiResponse<T> failure(ErrorCode errorCode, List<String> details) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode cannot be null");
        }
        return new ApiResponse<>(
                errorCode.getCode(),
                errorCode.getDefaultMessage(),
                null,
                details == null ? List.of() : details,
                TraceIdContext.getRequiredTraceId()
        );
    }

    /**
     * 按错误码与指定文案构建失败响应，message 可由 i18n 解析后的本地化文本覆盖。
     *
     * @param errorCode 统一错误码对象，不能为空。
     * @param message 本地化错误文案，允许为空；为空时回退到错误码默认文案。
     * @param details 错误明细列表；无明细时请传空列表。
     * @return 失败响应对象，code 为非 0。
     * @throws IllegalArgumentException 当 errorCode 为空时抛出异常。
     * @apiNote 使用示例：ApiResponse.failure(CommonErrorCode.BAD_REQUEST, "Bad request", List.of())
     */
    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, List<String> details) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode cannot be null");
        }
        String resolvedMessage = (message == null || message.isBlank()) ? errorCode.getDefaultMessage() : message;
        return new ApiResponse<>(
                errorCode.getCode(),
                resolvedMessage,
                null,
                details == null ? List.of() : details,
                TraceIdContext.getRequiredTraceId()
        );
    }

    /**
     * 获取业务状态码。
     *
     * @param 无。
     * @return 业务状态码，成功为 0，失败为非 0。
     * @throws 无。
     * @apiNote 使用示例：response.getCode()
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取响应消息。
     *
     * @param 无。
     * @return 响应消息文本。
     * @throws 无。
     * @apiNote 使用示例：response.getMessage()
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取响应数据体。
     *
     * @param 无。
     * @return 数据体对象，失败场景通常为 null。
     * @throws 无。
     * @apiNote 使用示例：response.getData()
     */
    public T getData() {
        return data;
    }

    /**
     * 获取错误明细列表。
     *
     * @param 无。
     * @return 不为 null 的不可变错误明细列表。
     * @throws 无。
     * @apiNote 使用示例：response.getDetails().isEmpty()
     */
    public List<String> getDetails() {
        return details;
    }

    /**
     * 获取链路追踪标识。
     *
     * @param 无。
     * @return 当前响应绑定的 traceId。
     * @throws 无。
     * @apiNote 使用示例：response.getTraceId()
     */
    public String getTraceId() {
        return traceId;
    }
}
