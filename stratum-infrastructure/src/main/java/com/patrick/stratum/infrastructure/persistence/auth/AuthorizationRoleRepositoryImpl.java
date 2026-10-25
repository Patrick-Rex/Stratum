package com.patrick.stratum.infrastructure.persistence.auth;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.domain.auth.RoleWarehouseScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 角色聚合写仓储，通过当前读、乐观版本及同事务关系替换保存精确授权。 */
@Repository
@RequiredArgsConstructor
public class AuthorizationRoleRepositoryImpl implements AuthorizationRoleRepository {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override public void registerCatalog() {
        requireWriteTransaction();
        try {
            var existing = permissionRows(PermissionCatalog.permissions());
            var additions = new ArrayList<PermissionRecord>();
            for (var entry : PermissionCatalog.values()) {
                if (!existing.containsKey(entry.permission())) {
                    var row = new PermissionRecord();
                    row.setId(entry.registrationId().toString()); row.setCode(entry.permission().code());
                    row.setType(entry.permission().type().name()); row.setName(entry.displayName()); additions.add(row);
                }
            }
            if (!additions.isEmpty()) database.insertable(additions).configure(options -> options.setPrintSQL(false)).executeRows();
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override public void save(AuthorizationRole role) {
        requireWriteTransaction();
        try {
            var rows = permissionRows(role.getPermissions());
            if (!rows.keySet().containsAll(role.getPermissions())) throw new StratumException(CommonErrorCode.CONFLICT);
            var row = new RoleRecord(); row.setId(role.getId()); row.setCode(role.getCode()); row.setName(role.getName());
            long next = role.getVersion() == null ? 0 : Math.incrementExact(role.getVersion());
            if (role.getVersion() == null) {
                row.setVersion(0L);
                requireOne(database.insertable(row).configure(options -> options.setPrintSQL(false)).executeRows());
                var initial = RoleWarehouseScope.initial(role.getId(), role.getCode());
                var scope = new RoleWarehouseScopeRecord(); scope.setRoleId(role.getId());
                scope.setScopeType(initial.getScope().type().name()); scope.setVersion(initial.getVersion());
                requireOne(database.insertable(scope).configure(options -> options.setPrintSQL(false)).executeRows());
            } else {
                row.setVersion(role.getVersion());
                requireOne(database.updatable(row).configure(options -> options.setPrintSQL(false)).executeRows());
            }
            database.deletable(RolePermissionRecord.class).where(relation -> relation.roleId().eq(role.getId()))
                    .allowDeleteStatement(true).configure(options -> options.setPrintSQL(false)).executeRows();
            var relations = role.getPermissions().stream().map(permission -> {
                var relation = new RolePermissionRecord(); relation.setRoleId(role.getId());
                relation.setPermissionId(rows.get(permission).getId()); return relation;
            }).toList();
            if (!relations.isEmpty()) database.insertable(relations).configure(options -> options.setPrintSQL(false)).executeRows();
            role.markPersisted(next);
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
        catch (ArithmeticException failure) { throw new StratumException(CommonErrorCode.CONFLICT); }
    }

    /** {@inheritDoc} */
    @Override public Optional<AuthorizationRole> findById(String id) {
        requireWriteTransaction();
        try {
            return restore(database.queryable(RoleRecord.class).where(role -> role.id().eq(id))
                    .forUpdate().configure(options -> options.setPrintSQL(false)).singleOrNull());
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override public Optional<AuthorizationRole> findByCode(String code) {
        requireWriteTransaction();
        try {
            return restore(database.queryable(RoleRecord.class).where(role -> role.code().eq(code))
                    .forUpdate().configure(options -> options.setPrintSQL(false)).singleOrNull());
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /**
     * 有界读取角色关系及权限，全部使用当前读避免调用方较早的事务快照。
     * @param role 持久化角色或空
     * @return 领域聚合
     */
    private Optional<AuthorizationRole> restore(RoleRecord role) {
        if (role == null) return Optional.empty();
        var relations = database.queryable(RolePermissionRecord.class).where(row -> row.roleId().eq(role.getId()))
                .limit(AccountPermissions.Snapshot.MAX_PERMISSIONS + 1).forUpdate().configure(options -> options.setPrintSQL(false)).toList();
        if (relations.size() > AccountPermissions.Snapshot.MAX_PERMISSIONS) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        Set<Permission> grants = Set.of();
        if (!relations.isEmpty()) {
            var ids = relations.stream().map(RolePermissionRecord::getPermissionId).toList();
            grants = database.queryable(PermissionRecord.class).where(row -> row.id().in(ids)).forUpdate()
                    .configure(options -> options.setPrintSQL(false)).toList().stream()
                    .map(row -> new Permission(row.getCode(), Permission.Type.valueOf(row.getType()))).collect(Collectors.toUnmodifiableSet());
        }
        return Optional.of(AuthorizationRole.restore(role.getId(), role.getCode(), role.getName(), grants, role.getVersion()));
    }

    /**
     * 单次批量解析权限标识，不逐条查询；数据库唯一约束限制同编码最多两类。
     * @param permissions 请求权限
     * @return 精确类型和编码到持久化行的映射
     */
    private Map<Permission, PermissionRecord> permissionRows(Collection<Permission> permissions) {
        if (permissions.isEmpty()) return Map.of();
        List<String> codes = permissions.stream().map(Permission::code).distinct().toList();
        return database.queryable(PermissionRecord.class).where(row -> row.code().in(codes)).forUpdate()
                .configure(options -> options.setPrintSQL(false)).toList().stream()
                .collect(Collectors.toMap(row -> new Permission(row.getCode(), Permission.Type.valueOf(row.getType())), Function.identity()));
    }

    /** 只允许Application的写工作单元调用，仓储不创建事务。 */
    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("角色仓储必须在Application写工作单元中使用");
        }
    }

    /**
     * 验证单行保存，零行视为并发冲突。
     * @param affected 影响行数
     */
    private static void requireOne(long affected) {
        if (affected != 1) throw new StratumException(CommonErrorCode.CONFLICT);
    }

    /**
     * 脱敏SQL故障，并将唯一约束、版本及死锁映射为可重试冲突。
     * @param failure ORM错误
     * @return 无底层原因链的业务错误
     */
    private static StratumException safeFailure(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) return new StratumException(CommonErrorCode.CONFLICT);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                    || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) return new StratumException(CommonErrorCode.CONFLICT);
        }
        return new StratumException(CommonErrorCode.INTERNAL_ERROR);
    }
}
