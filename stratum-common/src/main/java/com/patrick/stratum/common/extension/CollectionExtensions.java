package com.patrick.stratum.common.extension;

import java.util.Collection;
import java.util.List;

/**
 * 集合扩展工具类，负责提供跨模块复用的最小集合判空与不可变拷贝能力。
 * 适用场景：统一处理集合空值与防御性复制，避免业务层重复实现。
 */
public final class CollectionExtensions {

    private CollectionExtensions() {
    }

    /**
     * 判断集合是否为空（null 或 size=0 均视为 empty）。
     *
     * @param values 待判断集合，允许为 null。
     * @return true 表示集合为空；false 表示集合存在元素。
     * @throws 无。
     */
    public static boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    /**
     * 判断集合是否非空。
     *
     * @param values 待判断集合，允许为 null。
     * @return true 表示集合至少包含一个元素。
     * @throws 无。
     */
    public static boolean isNotEmpty(Collection<?> values) {
        return !isEmpty(values);
    }

    /**
     * 生成集合的不可变副本；当入参为 null 时返回空不可变列表。
     *
     * @param values 原始集合，允许为 null。
     * @param <T> 集合元素类型。
     * @return 不可变列表副本，永不为 null。
     * @throws 无。
     */
    public static <T> List<T> immutableCopy(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
