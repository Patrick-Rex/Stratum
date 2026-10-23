package com.patrick.stratum.infrastructure.persistence.masterdata;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 主数据仓储适配：依赖Application事务，通过唯一键、外键和版本保护写入。 */
@Repository
@RequiredArgsConstructor
public class MasterDataRepositoryImpl implements MasterDataRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public Optional<MasterData> findById(String id) {
        return Optional.ofNullable(query.queryable(MasterDataRecord.class).where(row -> row.id().eq(id)).singleOrNull())
                .map(row -> MasterData.restore(row.getId(), MasterData.Kind.valueOf(row.getKind()), row.getCode(),
                        row.getName(), row.getBaseUnit(), row.getVersion()));
    }

    /** {@inheritDoc} */
    @Override
    public void save(MasterData aggregate) {
        requireWriteTransaction();
        var row = map(aggregate);
        long next = aggregate.getVersion() == null ? 0 : Math.incrementExact(aggregate.getVersion());
        try {
            if (aggregate.getVersion() == null) {
                row.setVersion(0L);
                requireOneRow(query.insertable(row).executeRows());
            } else {
                requireOneRow(query.updatable(row).executeRows());
            }
            aggregate.markPersisted(next);
        } catch (EasyQueryException failure) {
            throw translate(failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(MasterData aggregate) {
        requireWriteTransaction();
        if (aggregate.getVersion() == null) throw new StratumException(CommonErrorCode.CONFLICT);
        try {
            requireOneRow(query.deletable(map(aggregate)).allowDeleteStatement(true).executeRows());
        } catch (EasyQueryException failure) {
            throw translate(failure);
        }
    }

    /**
     * 显式映射领域状态，ORM类型不进入Domain。
     * @param aggregate 主数据聚合
     * @return 独立持久化对象
     */
    private MasterDataRecord map(MasterData aggregate) {
        var row = new MasterDataRecord();
        row.setId(aggregate.getId());
        row.setKind(aggregate.getKind().name());
        row.setCode(aggregate.getCode());
        row.setName(aggregate.getName());
        row.setBaseUnit(aggregate.getBaseUnit());
        row.setVersion(aggregate.getVersion());
        return row;
    }

    /**
     * 不允许在工作单元外或只读事务中写入。
     * @throws IllegalStateException 缺少写事务
     */
    private void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("主数据写入必须在Application工作单元中执行");
        }
    }

    /**
     * 零行写入或删除视为版本冲突，不能返回假成功。
     * @param rows 影响行数
     * @throws StratumException 不是一行时抛出冲突
     */
    private void requireOneRow(long rows) {
        if (rows != 1) throw new StratumException(CommonErrorCode.CONFLICT);
    }

    /**
     * 将约束与并发错误映射为安全的业务冲突，不暴露SQL或数据库内容。
     * @param failure ORM异常
     * @return 业务冲突或原始异常
     */
    private RuntimeException translate(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) {
            return new StratumException(CommonErrorCode.CONFLICT, List.of("主数据版本已变更"), failure);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == 1062) {
                    return new StratumException(CommonErrorCode.CONFLICT, List.of("同类主数据编码已存在"), failure);
                }
                if (sql.getErrorCode() == 1451) {
                    return new StratumException(CommonErrorCode.CONFLICT, List.of("主数据已被业务引用，不能删除"), failure);
                }
                if (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState())) {
                    return new StratumException(CommonErrorCode.CONFLICT, List.of("主数据发生并发冲突"), failure);
                }
            }
        }
        return failure;
    }
}
