package com.patrick.stratum.common.helper;

import com.patrick.stratum.common.extension.StringExtensions;

/**
 * 校验帮助类，负责提供跨模块可复用的最小参数校验能力。
 * 适用场景：命令参数、查询参数、接口输入的前置校验。
 */
public final class ValidationHelper {

    private ValidationHelper() {
    }

    /**
     * 校验对象不能为空。
     *
     * @param value 待校验对象。
     * @param fieldName 字段名，用于异常提示。
     * @param <T> 对象类型。
     * @return 原对象本身，便于链式调用。
     * @throws IllegalArgumentException 当 value 为 null 时抛出异常。
     */
    public static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        return value;
    }

    /**
     * 校验字符串必须包含可见文本。
     *
     * @param value 待校验字符串。
     * @param fieldName 字段名，用于异常提示。
     * @return 去首尾空白后的文本。
     * @throws IllegalArgumentException 当 value 为空白时抛出异常。
     */
    public static String requireNonBlank(String value, String fieldName) {
        if (!StringExtensions.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value.trim();
    }

    /**
     * 校验数值必须为正数。
     *
     * @param value 待校验数值。
     * @param fieldName 字段名，用于异常提示。
     * @return 原数值。
     * @throws IllegalArgumentException 当 value 小于等于 0 时抛出异常。
     */
    public static long requirePositive(long value, String fieldName) {
        if (value <= 0L) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }
}
