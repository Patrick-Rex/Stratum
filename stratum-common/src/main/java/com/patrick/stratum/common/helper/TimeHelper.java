package com.patrick.stratum.common.helper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.patrick.stratum.common.extension.StringExtensions;

/**
 * 时间帮助类，负责提供统一 UTC 时间获取与 ISO-8601 文本转换能力。
 * 适用场景：审计信息、日志标签、任务时间戳等跨模块基础场景。
 */
public final class TimeHelper {

    private TimeHelper() {
    }

    /**
     * 获取当前 UTC 时间戳。
     *
     * @param 无。
     * @return 当前 Instant 时间对象。
     * @throws 无。
     */
    public static Instant nowUtc() {
        return Instant.now();
    }

    /**
     * 将 Instant 格式化为 ISO-8601 字符串。
     *
     * @param instant 时间对象，不允许为 null。
     * @return ISO-8601 格式字符串。
     * @throws IllegalArgumentException 当 instant 为 null 时抛出异常。
     */
    public static String formatIso(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant cannot be null");
        }
        return instant.toString();
    }

    /**
     * 将 ISO-8601 字符串解析为 Instant。
     *
     * @param value ISO-8601 时间文本，不允许为空白。
     * @return 解析后的 Instant。
     * @throws IllegalArgumentException 当 value 为空白或格式不合法时抛出异常。
     */
    public static Instant parseIso(String value) {
        if (!StringExtensions.hasText(value)) {
            throw new IllegalArgumentException("time text cannot be blank");
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("invalid ISO-8601 time: " + value, ex);
        }
    }
}
