package com.patrick.stratum.application.transaction;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通过 Spring 代理建立 Application 写事务边界，参与同一数据源的持久化共用事务。
 * 必须注入 UnitOfWork 使用，不能自行构造实例或依赖同类自调用开启事务。
 */
@Component
public class TransactionalUnitOfWork implements UnitOfWork {

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(Supplier<T> action) {
        return Objects.requireNonNull(action, "action").get();
    }
}
