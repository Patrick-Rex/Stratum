package com.patrick.stratum.domain.model;

import java.util.Arrays;

/**
 * 值对象基类。
 *
 * <p>值对象没有独立标识符，由其所包含的所有属性值共同定义相等性。
 * 两个值对象若所有属性值相等，则视为同一值对象。值对象应当不可变：
 * 所有字段必须为 {@code final}，不提供修改方法。</p>
 *
 * <p>子类必须实现 {@link #getEqualityFields()} 方法，返回参与相等性
 * 比较的字段数组。{@link #equals(Object)} 和 {@link #hashCode()} 将
 * 基于这些字段自动计算。</p>
 */
public abstract class ValueObject {

    /**
     * 返回参与相等性比较的字段数组。
     *
     * <p>子类通常返回 {@code new Object[]{field1, field2, ...}}，
     * 确保涵盖所有定义该值对象身份的属性。</p>
     *
     * @return 参与比较的字段，不可为 null
     */
    protected abstract Object[] getEqualityFields();

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ValueObject that = (ValueObject) o;
        return Arrays.deepEquals(getEqualityFields(), that.getEqualityFields());
    }

    @Override
    public final int hashCode() {
        return Arrays.deepHashCode(getEqualityFields());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + Arrays.toString(getEqualityFields()) + '}';
    }
}
