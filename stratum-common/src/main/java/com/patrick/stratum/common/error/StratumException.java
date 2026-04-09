package com.patrick.stratum.common.error;

import java.util.List;

/**
 * Stratum 全局业务异常基类，负责统一承载错误码、错误明细与异常因果链。
 * 适用场景：application/interface/query 层抛出可被统一映射的业务异常。
 */
public class StratumException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    /**
     * 使用错误码构建业务异常。
     *
     * @param errorCode 统一错误码对象，不能为空。
     * @return 无返回值。
     * @throws IllegalArgumentException 当 errorCode 为空时抛出异常。
     * @apiNote 使用示例：throw new StratumException(CommonErrorCode.BAD_REQUEST)
     */
    public StratumException(ErrorCode errorCode) {
        this(errorCode, List.of(), null);
    }

    /**
     * 使用错误码与单条明细构建业务异常。
     *
     * @param errorCode 统一错误码对象，不能为空。
     * @param detail 错误明细文本，可为空。
     * @return 无返回值。
     * @throws IllegalArgumentException 当 errorCode 为空时抛出异常。
     * @apiNote 使用示例：throw new StratumException(CommonErrorCode.BAD_REQUEST, "name 不能为空")
     */
    public StratumException(ErrorCode errorCode, String detail) {
        this(errorCode, detail == null || detail.isBlank() ? List.of() : List.of(detail), null);
    }

    /**
     * 使用错误码与明细列表构建业务异常。
     *
     * @param errorCode 统一错误码对象，不能为空。
     * @param details 错误明细列表；为空时自动转为空列表。
     * @return 无返回值。
     * @throws IllegalArgumentException 当 errorCode 为空时抛出异常。
     * @apiNote 使用示例：throw new StratumException(CommonErrorCode.BAD_REQUEST, List.of("字段非法"))
     */
    public StratumException(ErrorCode errorCode, List<String> details) {
        this(errorCode, details, null);
    }

    /**
     * 使用错误码、明细列表与根因异常构建业务异常。
     *
     * @param errorCode 统一错误码对象，不能为空。
     * @param details 错误明细列表；为空时自动转为空列表。
     * @param cause 根因异常，可为空。
     * @return 无返回值。
     * @throws IllegalArgumentException 当 errorCode 为空时抛出异常。
     * @apiNote 使用示例：throw new StratumException(CommonErrorCode.INTERNAL_ERROR, List.of(), ex)
     */
    public StratumException(ErrorCode errorCode, List<String> details, Throwable cause) {
        super(errorCode == null ? null : errorCode.getDefaultMessage(), cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode cannot be null");
        }
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * 获取异常绑定的统一错误码。
     *
     * @param 无。
     * @return 统一错误码对象。
     * @throws 无。
     * @apiNote 使用示例：exception.getErrorCode().getCode()
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取异常绑定的错误明细列表。
     *
     * @param 无。
     * @return 不为 null 的不可变明细列表。
     * @throws 无。
     * @apiNote 使用示例：exception.getDetails().isEmpty()
     */
    public List<String> getDetails() {
        return details;
    }
}
