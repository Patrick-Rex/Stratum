package com.patrick.stratum.interfaceadapter.response;

import java.util.List;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.extension.StringExtensions;

/**
 * Interface 层统一响应工厂，负责演示并承接对 common ApiResponse 的复用入口。
 * 适用场景：Controller 返回成功响应时的统一构建。
 */
public final class ApiResponseFactory {

    private ApiResponseFactory() {
    }

    /**
     * 构建成功响应对象。
     *
     * @param data 响应业务数据，可为 null。
     * @return 基于 common 模块构建的标准成功响应。
     * @throws IllegalStateException 当当前线程缺少 traceId 上下文时抛出异常。
     * @apiNote 使用示例：return ApiResponseFactory.ok(dto)
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.success(data);
    }

    /**
     * 构建参数错误响应对象，演示 interface 层对 common 字符串扩展与统一错误码的复用。
     *
     * @param detail 参数错误明细，允许为空白；为空白时回退默认明细。
     * @return 基于 common 模块构建的标准失败响应。
     * @throws IllegalStateException 当当前线程缺少 traceId 上下文时抛出异常。
     * @apiNote 使用示例：return ApiResponseFactory.badRequest("name 不能为空")
     */
    public static <T> ApiResponse<T> badRequest(String detail) {
        String normalizedDetail = StringExtensions.hasText(detail) ? detail.trim() : "request validation failed";
        return ApiResponse.failure(CommonErrorCode.BAD_REQUEST, List.of(normalizedDetail));
    }
}
