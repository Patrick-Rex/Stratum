package com.patrick.stratum.infrastructure.persistence.auth;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.DataScopeType;
import com.patrick.stratum.domain.auth.RoleWarehouseScope;
import com.patrick.stratum.domain.auth.RoleWarehouseScopeRepository;
import com.patrick.stratum.domain.auth.WarehouseScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 角色仓库配置写仓储，使用当前读、独立乐观版本及同事务整组替换，不创建事务。 */
@Repository
@RequiredArgsConstructor
public class RoleWarehouseScopeRepositoryImpl implements RoleWarehouseScopeRepository {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override public Optional<RoleWarehouseScope> findByRoleId(String roleId) {
        requireWriteTransaction();
        try {
            var role = lockRole(roleId);
            if (role == null) return Optional.empty();
            var configuration = lockConfiguration(roleId);
            var relations = database.queryable(RoleWarehouseRecord.class).where(row -> row.roleId().eq(roleId))
                    .limit(WarehouseScope.MAX_WAREHOUSES + 1).forUpdate().configure(options -> options.setPrintSQL(false)).toList();
            if (relations.size() > WarehouseScope.MAX_WAREHOUSES) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            var warehouses = relations.stream().map(row -> UUID.fromString(row.getWarehouseId())).collect(Collectors.toUnmodifiableSet());
            var scope = new WarehouseScope(DataScopeType.valueOf(configuration.getScopeType()), warehouses);
            return Optional.of(RoleWarehouseScope.restore(roleId, role.getCode(), scope, configuration.getVersion()));
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
        catch (IllegalArgumentException failure) { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); }
    }

    /** {@inheritDoc} */
    @Override public void save(RoleWarehouseScope configuration) {
        requireWriteTransaction();
        try {
            var role = lockRole(configuration.getId());
            if (role == null) throw new StratumException(CommonErrorCode.NOT_FOUND);
            if (!role.getCode().equals(configuration.getRoleCode())) throw new StratumException(CommonErrorCode.CONFLICT);
            var current = lockConfiguration(configuration.getId());
            if (!current.getVersion().equals(configuration.getVersion())) throw new StratumException(CommonErrorCode.CONFLICT);
            long next = Math.incrementExact(configuration.getVersion());
            // 先移除从属集合，再切换类型，保持CUSTOM复合外键约束；失败由工作单元整体回滚。
            database.deletable(RoleWarehouseRecord.class).where(row -> row.roleId().eq(configuration.getId()))
                    .allowDeleteStatement(true).configure(options -> options.setPrintSQL(false)).executeRows();
            var replacement = new RoleWarehouseScopeRecord(); replacement.setRoleId(configuration.getId());
            replacement.setScopeType(configuration.getScope().type().name()); replacement.setVersion(configuration.getVersion());
            if (database.updatable(replacement).configure(options -> options.setPrintSQL(false)).executeRows() != 1) {
                throw new StratumException(CommonErrorCode.CONFLICT);
            }
            var relations = configuration.getScope().warehouseIds().stream().map(id -> {
                var row = new RoleWarehouseRecord(); row.setRoleId(configuration.getId()); row.setWarehouseId(id.toString()); return row;
            }).toList();
            if (!relations.isEmpty()) database.insertable(relations).configure(options -> options.setPrintSQL(false)).executeRows();
            configuration.markPersisted(next);
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
        catch (ArithmeticException failure) { throw new StratumException(CommonErrorCode.CONFLICT); }
    }

    /**
     * 锁定固定角色身份，防止使用伪造编码扩大配置权限。
     * @param roleId 角色UUID
     * @return 当前角色行或空
     */
    private RoleRecord lockRole(String roleId) {
        return database.queryable(RoleRecord.class).where(row -> row.id().eq(roleId)).forUpdate()
                .configure(options -> options.setPrintSQL(false)).singleOrNull();
    }

    /**
     * 读取并锁定最新配置，已存在角色缺失配置时失败关闭，不补默认授权。
     * @param roleId 角色UUID
     * @return 当前配置行
     */
    private RoleWarehouseScopeRecord lockConfiguration(String roleId) {
        var row = database.queryable(RoleWarehouseScopeRecord.class).where(scope -> scope.roleId().eq(roleId))
                .forUpdate().configure(options -> options.setPrintSQL(false)).singleOrNull();
        if (row == null) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        return row;
    }

    /** 仅允许Application写工作单元调用，仓储不控制事务或管理资格。 */
    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("角色仓库配置必须在Application写工作单元中访问");
        }
    }

    /**
     * 脱敏底层SQL故障，将乐观冲突、引用错误、死锁及快照冲突映射为409。
     * @param failure ORM错误
     * @return 不附带底层原因的业务错误
     */
    private static StratumException safeFailure(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) return new StratumException(CommonErrorCode.CONFLICT);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                    || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT);
            }
        }
        return new StratumException(CommonErrorCode.INTERNAL_ERROR);
    }
}
