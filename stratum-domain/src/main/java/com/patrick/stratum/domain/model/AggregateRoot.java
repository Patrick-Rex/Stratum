package com.patrick.stratum.domain.model;

import java.io.Serializable;

/**
 * 聚合根基类。
 *
 * <p>聚合根是领域模型中唯一可由外部直接引用的实体，负责维护其内部
 * 实体和值对象的整体不变性。所有写操作必须通过聚合根入口，以保证
 * 业务规则一致性。</p>
 *
 * <p>内置乐观锁版本号字段 {@code version}，由持久化层在写入时校验，
 * 并发冲突时抛出对应异常。</p>
 *
 * @param <ID> 聚合根标识符类型
 */
public abstract class AggregateRoot<ID extends Serializable> extends Entity<ID> {

    /**
     * 乐观锁版本号，由持久化层维护。
     */
    private Long version;

    protected AggregateRoot(ID id) {
        super(id);
    }

    /**
     * 返回当前乐观锁版本号。
     *
     * @return 版本号，初始可为 null
     */
    public Long getVersion() {
        return version;
    }

    /**
     * 设置乐观锁版本号（供持久化层调用）。
     *
     * @param version 新的版本号
     */
    protected void setVersion(Long version) {
        this.version = version;
    }
}
