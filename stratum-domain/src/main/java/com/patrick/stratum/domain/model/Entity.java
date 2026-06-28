package com.patrick.stratum.domain.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * 实体基类。
 *
 * <p>实体由唯一标识符定义，标识符相等的两个实体视为同一实体，
 * 无论其属性值是否相同。所有领域实体均需继承本类。</p>
 *
 * @param <ID> 实体标识符类型，必须可序列化
 */
public abstract class Entity<ID extends Serializable> {

    /**
     * 实体唯一标识符。
     */
    protected final ID id;

    /**
     * 构造实体并绑定标识符。
     *
     * @param id 实体唯一标识符，不可为 null
     */
    protected Entity(ID id) {
        this.id = Objects.requireNonNull(id, "实体标识符不可为 null");
    }

    /**
     * 返回实体唯一标识符。
     *
     * @return 标识符，不为 null
     */
    public ID getId() {
        return id;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Entity<?> other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + "id=" + id + '}';
    }
}
