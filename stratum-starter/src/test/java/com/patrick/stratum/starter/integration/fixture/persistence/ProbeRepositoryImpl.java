package com.patrick.stratum.starter.integration.fixture.persistence;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.repository.Repository;
import com.patrick.stratum.starter.integration.fixture.domain.PersistenceProbe;

/** 可运行的具体仓储模板：映射独立于领域，不开启事务，不隐藏 SQL DSL。 */
public final class ProbeRepositoryImpl implements Repository<PersistenceProbe, String> {
    private final EasyEntityQuery query;

    /**
     * 注入与 Spring 数据源共用事务连接的 ORM 客户端。
     * @param query ORM 客户端
     * @apiNote 示例：new ProbeRepositoryImpl(easyEntityQuery)。
     */
    public ProbeRepositoryImpl(EasyEntityQuery query) { this.query = query; }

    /** {@inheritDoc} */
    @Override
    public Optional<PersistenceProbe> findById(String id) {
        return Optional.ofNullable(query.queryable(ProbeRecord.class).where(row -> row.id().eq(id)).singleOrNull())
                .map(row -> new PersistenceProbe(row.getId(), row.getPayload(), row.getVersion()));
    }

    /** {@inheritDoc} */
    @Override
    public void save(PersistenceProbe aggregate) {
        requireTransaction();
        ProbeRecord row = map(aggregate);
        if (aggregate.getVersion() == null) {
            row.setVersion(0L);
            query.insertable(row).executeRows();
            aggregate.markPersisted(0L);
            return;
        }
        try {
            requireOneRow(query.updatable(row).executeRows());
            aggregate.markPersisted(aggregate.getVersion() + 1);
        } catch (EasyQueryException failure) {
            throw translateWriteFailure(failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(PersistenceProbe aggregate) {
        requireTransaction();
        if (aggregate.getVersion() == null) { throw new StratumException(CommonErrorCode.CONFLICT); }
        try {
            requireOneRow(query.deletable(map(aggregate)).allowDeleteStatement(true).executeRows());
        } catch (EasyQueryException failure) {
            throw translateWriteFailure(failure);
        }
    }

    /**
     * 显式转换聚合与持久化对象，ORM 注解不进入 Domain。
     * @param aggregate 源聚合
     * @return 独立持久化对象
     */
    private static ProbeRecord map(PersistenceProbe aggregate) {
        var row = new ProbeRecord();
        row.setId(aggregate.getId());
        row.setPayload(aggregate.payload());
        row.setVersion(aggregate.getVersion());
        return row;
    }

    /**
     * 将不存在或版本不匹配统一映射为冲突，防止静默丢失更新。
     * @param rows 受影响行数
     * @throws StratumException 不是预期单行时抛出 40900
     */
    private static void requireOneRow(long rows) {
        if (rows != 1) { throw new StratumException(CommonErrorCode.CONFLICT); }
    }

    /**
     * 识别 ORM 乐观锁冲突和 MariaDB 快照写冲突，其他 SQL 错误保留原异常。
     * @param failure ORM 异常
     * @return 统一冲突异常或原异常
     */
    private static RuntimeException translateWriteFailure(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) {
            return new StratumException(CommonErrorCode.CONFLICT, List.of(), failure);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of(), failure);
            }
        }
        return failure;
    }

    /**
     * 要求 Application 已经开启事务；仓储本身不管理事务。
     * @throws IllegalStateException 事务外写入时抛出
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("仓储写入必须在 Application 工作单元内执行");
        }
    }
}
