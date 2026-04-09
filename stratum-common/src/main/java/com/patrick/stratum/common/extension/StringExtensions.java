package com.patrick.stratum.common.extension;

/**
 * 字符串扩展工具类，负责提供跨模块复用的最小字符串处理能力。
 * 适用场景：application/interface/query 层统一处理空白、标准化与文本判断。
 */
public final class StringExtensions {

    private StringExtensions() {
    }

    /**
     * 将字符串标准化为去首尾空白后的文本；若入参为空则返回空字符串。
     *
     * @param value 原始字符串，允许为 null。
     * @return 去首尾空白后的字符串，永不为 null。
     * @throws 无。
     */
    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 判断字符串是否包含可见文本（非 null 且去空白后非空）。
     *
     * @param value 待判断字符串，允许为 null。
     * @return true 表示包含可见文本；false 表示为空白或 null。
     * @throws 无。
     */
    public static boolean hasText(String value) {
        return !trimToEmpty(value).isEmpty();
    }

    /**
     * 比较两个字符串的忽略大小写等值关系。
     *
     * @param left 左值字符串，允许为 null。
     * @param right 右值字符串，允许为 null。
     * @return true 表示两者在忽略大小写后相等。
     * @throws 无。
     */
    public static boolean equalsIgnoreCase(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }
}
