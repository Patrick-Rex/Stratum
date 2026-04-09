package com.patrick.stratum.common.error;

/**
 * 统一错误码契约，负责定义错误码、消息键与默认文案的标准访问方式。
 * 适用场景：application/interface/query 层统一传递错误语义。
 */
public interface ErrorCode {

    /**
     * 获取整型错误码。
     *
     * @param 无。
     * @return 错误码数值，必须符合规范码段。
     * @throws 无。
     * @apiNote 使用示例：errorCode.getCode()
     */
    int getCode();

    /**
     * 获取 i18n 消息键。
     *
     * @param 无。
     * @return 国际化消息键，例如 error.common.badRequest。
     * @throws 无。
     * @apiNote 使用示例：messageSource.getMessage(errorCode.getMessageKey(), null, locale)
     */
    String getMessageKey();

    /**
     * 获取默认错误文案。
     *
     * @param 无。
     * @return 默认中文或英文错误消息，用于无 i18n 资源时兜底。
     * @throws 无。
     * @apiNote 使用示例：errorCode.getDefaultMessage()
     */
    String getDefaultMessage();
}
