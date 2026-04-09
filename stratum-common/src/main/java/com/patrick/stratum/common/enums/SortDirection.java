package com.patrick.stratum.common.enums;

import com.patrick.stratum.common.extension.StringExtensions;

/**
 * 排序方向基础枚举，负责统一 asc/desc 的解析与标准输出。
 * 适用场景：分页查询参数解析与排序白名单校验。
 */
public enum SortDirection {

    ASC,
    DESC;

    /**
     * 将文本解析为排序方向；空白或非法值回退为 ASC。
     *
     * @param token 排序方向文本，允许为 null。
     * @return 解析后的排序方向枚举。
     * @throws 无。
     */
    public static SortDirection fromToken(String token) {
        String normalized = StringExtensions.trimToEmpty(token);
        if ("desc".equalsIgnoreCase(normalized)) {
            return DESC;
        }
        return ASC;
    }

    /**
     * 获取规范化的小写文本表示。
     *
     * @param 无。
     * @return asc 或 desc。
     * @throws 无。
     */
    public String toToken() {
        return name().toLowerCase();
    }
}
