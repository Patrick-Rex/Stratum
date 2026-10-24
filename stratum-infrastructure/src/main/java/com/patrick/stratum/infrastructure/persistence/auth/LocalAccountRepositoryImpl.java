package com.patrick.stratum.infrastructure.persistence.auth;

import java.sql.SQLException;
import java.util.Optional;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 账号写模型仓储；仅在Application写事务中工作，数据库异常不携带可能含凭据的SQL原因。 */
@Repository
@RequiredArgsConstructor
public class LocalAccountRepositoryImpl implements LocalAccountRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public void insert(LocalAccount account) {
        requireWriteTransaction();
        if (account.getVersion() != null) { throw new StratumException(CommonErrorCode.CONFLICT); }
        var row = new LocalAccountRecord();
        row.setId(account.getId());
        row.setUsername(account.getUsername());
        row.setPasswordHash(account.getPasswordHash());
        row.setEnabled(account.isEnabled());
        row.setBootstrapSlot(account.isBootstrapAdministrator() ? 1 : null);
        row.setVersion(0L);
        try {
            if (query.insertable(row).configure(options -> options.setPrintSQL(false)).executeRows() != 1) {
                throw new StratumException(CommonErrorCode.CONFLICT);
            }
            account.markPersisted(0);
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(LocalAccount account) {
        requireWriteTransaction();
        if (account.getVersion() == null) throw new StratumException(CommonErrorCode.CONFLICT);
        try {
            long next = Math.incrementExact(account.getVersion());
            var row = new LocalAccountRecord();
            row.setId(account.getId());
            row.setEnabled(account.isEnabled());
            row.setVersion(account.getVersion());
            if (query.updatable(row).configure(options -> options.setPrintSQL(false)).executeRows() != 1) {
                throw new StratumException(CommonErrorCode.CONFLICT);
            }
            account.markPersisted(next);
        } catch (ArithmeticException failure) { throw new StratumException(CommonErrorCode.CONFLICT); }
        catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LocalAccount> findById(String id) {
        requireWriteTransaction();
        try {
            return Optional.ofNullable(query.queryable(LocalAccountRecord.class).configure(options -> options.setPrintSQL(false))
                    .where(row -> row.id().eq(id)).forUpdate().singleOrNull())
                    .map(LocalAccountRepositoryImpl::restore);
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LocalAccount> findBootstrapAdministrator() {
        requireWriteTransaction();
        try {
            return Optional.ofNullable(query.queryable(LocalAccountRecord.class)
                    .configure(options -> options.setPrintSQL(false))
                    .where(row -> row.bootstrapSlot().eq(1)).singleOrNull()).map(LocalAccountRepositoryImpl::restore);
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /**
     * 还原内部聚合，数据库约束保证启用标记和初始化槽合法。
     * @param row 内部持久化记录
     * @return 账号聚合
     */
    private static LocalAccount restore(LocalAccountRecord row) {
        return LocalAccount.restore(row.getId(), row.getUsername(), row.getPasswordHash(), row.getEnabled(),
                row.getBootstrapSlot() != null, row.getVersion());
    }

    /** 写模型读写均必须由Application工作单元驱动，登录读取使用独立QueryGateway。 */
    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("账号仓储必须在Application写工作单元中使用");
        }
    }

    /**
     * 分类唯一、版本及事务快照冲突并脱敏全部底层异常，不保留密码散列或SQL。
     * @param failure ORM失败
     * @return 无敏感原因链的业务异常
     */
    private static StratumException safeFailure(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) return new StratumException(CommonErrorCode.CONFLICT);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062
                    || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT);
            }
        }
        return new StratumException(CommonErrorCode.INTERNAL_ERROR);
    }
}
