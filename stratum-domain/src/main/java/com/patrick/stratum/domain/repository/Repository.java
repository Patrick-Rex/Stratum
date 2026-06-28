package com.patrick.stratum.domain.repository;

import java.io.Serializable;
import java.util.Optional;

import com.patrick.stratum.domain.model.AggregateRoot;

/**
 * 仓储接口基类。
 *
 * <p>仓储是聚合根的持久化抽象，封装对聚合根的加载、保存与删除操作。
 * 本接口仅定义领域层所需的最小契约，具体持久化策略由基础设施层实现。</p>
 *
 * <p>约束：</p>
 * <ul>
 *   <li>仓储只能操作聚合根，不得直接操作内部实体或值对象</li>
 *   <li>存储实现不得包含业务决策逻辑</li>
 *   <li>事务由 Application 层驱动，仓储实现不自行管理事务</li>
 * </ul>
 *
 * @param <T>  聚合根类型
 * @param <ID> 聚合根标识符类型
 */
public interface Repository<T extends AggregateRoot<ID>, ID extends Serializable> {

    /**
     * 按标识符查找聚合根。
     *
     * @param id 聚合根标识符，不可为 null
     * @return 聚合根 Optional，不存在时返回 {@link Optional#empty()}
     */
    Optional<T> findById(ID id);

    /**
     * 保存聚合根（新增或更新）。
     *
     * <p>实现需根据聚合根是否已存在决定执行 INSERT 或 UPDATE。
     * 更新时必须校验乐观锁版本号。</p>
     *
     * @param aggregate 待保存的聚合根，不可为 null
     */
    void save(T aggregate);

    /**
     * 删除聚合根。
     *
     * @param aggregate 待删除的聚合根，不可为 null
     */
    void delete(T aggregate);
}
