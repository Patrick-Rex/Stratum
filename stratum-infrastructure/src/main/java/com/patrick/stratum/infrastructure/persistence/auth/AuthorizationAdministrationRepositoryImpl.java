package com.patrick.stratum.infrastructure.persistence.auth;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 账号角色写存储及管理协调锁；每个入口均取同一行锁，防止遗漏协调或跨事务复用内存标记。 */
@Repository
@RequiredArgsConstructor
public class AuthorizationAdministrationRepositoryImpl implements AuthorizationAdministrationRepository {
    private final EasyEntityQuery database;

    /** {@inheritDoc} */
    @Override public void lock() { guard(); }

    /** {@inheritDoc} */
    @Override public boolean isEnabledAdministrator(UUID userId) {
        Objects.requireNonNull(userId, "账号标识不能为空");
        guard(); return administratorCount(userId) > 0;
    }

    /** {@inheritDoc} */
    @Override public long countEnabledAdministrators() { guard(); return administratorCount(null); }

    /** {@inheritDoc} */
    @Override public Set<UUID> findRoleIds(UUID userId) {
        Objects.requireNonNull(userId, "账号标识不能为空"); guard();
        try {
            var rows = database.queryable(UserRoleRecord.class).where(row -> row.userId().eq(userId.toString()))
                    .limit(MAX_ROLES_PER_ACCOUNT + 1).forUpdate().configure(options -> options.setPrintSQL(false)).toList();
            if (rows.size() > MAX_ROLES_PER_ACCOUNT) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            return rows.stream().map(row -> UUID.fromString(row.getRoleId())).collect(Collectors.toUnmodifiableSet());
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override public void replaceRoleIds(UUID userId, Set<UUID> roleIds) {
        Objects.requireNonNull(userId, "账号标识不能为空");
        Objects.requireNonNull(roleIds, "角色集合不能为空");
        if (roleIds.size() > MAX_ROLES_PER_ACCOUNT) throw new IllegalArgumentException("账号角色数超出上限");
        var replacement = Set.copyOf(roleIds); guard();
        try {
            database.deletable(UserRoleRecord.class).where(row -> row.userId().eq(userId.toString()))
                    .allowDeleteStatement(true).configure(options -> options.setPrintSQL(false)).executeRows();
            var rows = replacement.stream().map(roleId -> {
                var row = new UserRoleRecord(); row.setUserId(userId.toString()); row.setRoleId(roleId.toString()); return row;
            }).toList();
            if (!rows.isEmpty()) database.insertable(rows).configure(options -> options.setPrintSQL(false)).executeRows();
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /** {@inheritDoc} */
    @Override public boolean isInitialized() { return guard().getInitialized(); }

    /** {@inheritDoc} */
    @Override public void markInitialized() {
        var row = guard();
        if (row.getInitialized()) return;
        row.setInitialized(true);
        try {
            if (database.updatable(row).configure(options -> options.setPrintSQL(false)).executeRows() != 1) {
                throw new StratumException(CommonErrorCode.CONFLICT);
            }
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /**
     * 锁定唯一协调行，任何缺失或读取失败均拒绝管理操作。
     * @return 当前协调状态
     */
    private AdministrationGuardRecord guard() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("管理仓储必须在Application写工作单元中使用");
        }
        try {
            var row = database.queryable(AdministrationGuardRecord.class).where(record -> record.singletonId().eq(1))
                    .forUpdate().configure(options -> options.setPrintSQL(false)).singleOrNull();
            if (row == null) throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
            return row;
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /**
     * 当前读统计管理员关系，SQL只投影成员标识，不加载账号凭据。
     * @param userId 指定账号或空表示全体
     * @return 启用管理员数
     */
    private long administratorCount(UUID userId) {
        try {
            return database.queryable(UserRoleRecord.class)
                    .innerJoin(RoleRecord.class, (member, role) -> member.roleId().eq(role.id()))
                    .innerJoin(LocalAccountRecord.class, (member, role, user) -> member.userId().eq(user.id()))
                    .where((member, role, user) -> {
                        role.code().eq(AuthorizationRole.ADMINISTRATOR_CODE); user.enabled().eq(true);
                        if (userId != null) member.userId().eq(userId.toString());
                    })
                    .select((member, role, user) -> member)
                    .forUpdate().configure(options -> options.setPrintSQL(false)).count();
        } catch (EasyQueryException failure) { throw safeFailure(failure); }
    }

    /**
     * 将约束及锁冲突转换为409，其余为500，均去除底层SQL原因链。
     * @param failure ORM异常
     * @return 脱敏错误
     */
    private static StratumException safeFailure(EasyQueryException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                    || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT);
            }
        }
        return new StratumException(CommonErrorCode.INTERNAL_ERROR);
    }
}
