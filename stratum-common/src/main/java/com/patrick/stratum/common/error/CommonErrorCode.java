package com.patrick.stratum.common.error;

/**
 * 公共错误码枚举，负责提供跨模块可复用的基础错误码定义。
 * 码段遵循 API 规范：400xx/401xx/403xx/404xx/409xx/500xx。
 */
public enum CommonErrorCode implements ErrorCode {

    BAD_REQUEST(40000, "error.common.badRequest", "请求参数或协议不合法"),
    UNAUTHORIZED(40100, "error.common.unauthorized", "未认证或认证已失效"),
    FORBIDDEN(40300, "error.common.forbidden", "无权限访问当前资源"),
    NOT_FOUND(40400, "error.common.notFound", "请求资源不存在"),
    CONFLICT(40900, "error.common.conflict", "请求状态冲突"),
    INTERNAL_ERROR(50000, "error.common.internalError", "服务内部错误");

    private final int code;
    private final String messageKey;
    private final String defaultMessage;

    CommonErrorCode(int code, String messageKey, String defaultMessage) {
        this.code = code;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 获取整型错误码。
     *
     * @param 无。
     * @return 标准化错误码。
     * @throws 无。
     * @apiNote 使用示例：CommonErrorCode.BAD_REQUEST.getCode()
     */
    @Override
    public int getCode() {
        return code;
    }

    /**
     * 获取 i18n 消息键。
     *
     * @param 无。
     * @return 国际化消息键。
     * @throws 无。
     * @apiNote 使用示例：CommonErrorCode.BAD_REQUEST.getMessageKey()
     */
    @Override
    public String getMessageKey() {
        return messageKey;
    }

    /**
     * 获取默认错误文案。
     *
     * @param 无。
     * @return 无 i18n 时可直接返回的兜底文案。
     * @throws 无。
     * @apiNote 使用示例：CommonErrorCode.BAD_REQUEST.getDefaultMessage()
     */
    @Override
    public String getDefaultMessage() {
        return defaultMessage;
    }
}
