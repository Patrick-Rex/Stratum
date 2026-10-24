package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实MariaDB验证角色聚合、精确权限关系和当前读；管理应用仍由后续节点接入。 */
class AuthorizationRoleRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final AuthorizationRoleRepository roles;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> ids = new ArrayList<>();

    /**
     * 注入写仓储、工作单元与独立断言客户端。
     * @param roles 角色仓储
     * @param unitOfWork Application事务
     * @param jdbc 独立验证客户端
     */
    @Autowired
    AuthorizationRoleRepositoryIntegrationTest(AuthorizationRoleRepository roles, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.roles = roles; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 持有协调行后登记共享目录，不清除其他测试或既有业务授权。 */
    @BeforeEach
    void prepareCatalog() {
        unitOfWork.execute(() -> {
            jdbc.queryForObject("SELECT initialized FROM auth_administration_guard WHERE singleton_id=1 FOR UPDATE", Boolean.class);
            roles.registerCatalog(); return null;
        });
    }

    /** 仅清理本用例新增角色和其权限关联，保留登记目录。 */
    @AfterEach
    void clean() {
        for (String id : ids) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", id);
            jdbc.update("DELETE FROM auth_role WHERE id=?", id);
        }
    }

    /** 目录重复登记保持UUID，API和FEAT映射不混淆。 */
    @Test
    void catalogRegistrationRetainsExistingIdentities() {
        var before = jdbc.queryForList("SELECT id,code,type FROM auth_permission ORDER BY id");
        prepareCatalog();
        assertEquals(before, jdbc.queryForList("SELECT id,code,type FROM auth_permission ORDER BY id"));
        for (var entry : PermissionCatalog.values()) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission WHERE code=? AND type=?", Integer.class,
                    entry.permission().code(), entry.permission().type().name()));
        }
    }

    /** 保存名称与全部权限，清空关系不遗留旧授权，代码和身份固定。 */
    @Test
    void roundTripsAndReplacesRolePermissionsAtomically() {
        var role = create();
        role.replacePermissions(Set.of(PermissionCatalog.PURCHASE_READ.permission(), PermissionCatalog.PURCHASE_FEATURE.permission()));
        save(role); assertEquals(0, role.getVersion());
        var loaded = load(role.getId()); assertEquals(role.getPermissions(), loaded.getPermissions());
        assertEquals(role.getCode(), loaded.getCode());
        loaded.rename("采购主管"); loaded.replacePermissions(Set.of(PermissionCatalog.SALES_READ.permission())); save(loaded);
        assertEquals(1, loaded.getVersion()); assertEquals("采购主管", load(role.getId()).getName());
        assertEquals(loaded.getPermissions(), load(role.getId()).getPermissions());
        loaded.replacePermissions(Set.of()); save(loaded); assertTrue(load(role.getId()).getPermissions().isEmpty());
        assertEquals(role.getId(), unitOfWork.execute(() -> roles.findByCode(role.getCode()).orElseThrow().getId()));
    }

    /** 过期版本和重复代码返回脱敏409，不能部分覆盖名称或权限。 */
    @Test
    void rejectsConflictsWithoutChangingExistingGrants() {
        var role = create(); save(role); var stale = load(role.getId());
        role.replacePermissions(Set.of(PermissionCatalog.MATERIAL_READ.permission())); save(role);
        stale.rename("过期"); stale.replacePermissions(Set.of(PermissionCatalog.ROLE_MANAGE.permission()));
        var failure = assertThrows(StratumException.class, () -> save(stale));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); assertNull(failure.getCause());
        assertEquals(role.getPermissions(), load(role.getId()).getPermissions());
        var duplicate = AuthorizationRole.create(role.getCode(), "重复"); ids.add(duplicate.getId());
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> save(duplicate)).getErrorCode());
    }

    /** 外层失败必须回滚角色及权限关系；所有写仓储入口拒绝无事务调用。 */
    @Test
    void rollsBackEntireAggregateAndRequiresWriteTransaction() {
        var role = create(); role.replacePermissions(Set.of(PermissionCatalog.ACCOUNT_MANAGE.permission()));
        assertThrows(IllegalStateException.class, () -> roles.save(role));
        assertThrows(IllegalStateException.class, roles::registerCatalog);
        assertThrows(IllegalStateException.class, () -> roles.findById(role.getId()));
        assertThrows(IllegalStateException.class, () -> roles.findByCode(role.getCode()));
        assertThrows(IllegalArgumentException.class, () -> unitOfWork.execute(() -> {
            roles.save(role); throw new IllegalArgumentException("强制回滚");
        }));
        assertTrue(unitOfWork.execute(() -> roles.findById(role.getId()).isEmpty()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_permission WHERE role_id=?", Integer.class, role.getId()));
    }

    /** MariaDB快照隔离冲突必须拒绝旧授权；新工作单元重试后读取最新角色及权限。 */
    @Test
    void rejectsObsoleteSnapshotAndReadsLatestAfterWholeTransactionRetry() {
        assertEquals(1, jdbc.queryForObject("SELECT @@innodb_snapshot_isolation", Integer.class));
        var role = create(); save(role);
        var failure = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            assertEquals("测试角色", jdbc.queryForObject("SELECT name FROM auth_role WHERE id=?", String.class, role.getId()));
            CompletableFuture.runAsync(() -> {
                var changed = load(role.getId()); changed.rename("最新名称");
                changed.replacePermissions(Set.of(PermissionCatalog.INVENTORY_READ.permission())); save(changed);
            }).join();
            return roles.findById(role.getId()).orElseThrow();
        }));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); assertNull(failure.getCause());
        var current = load(role.getId());
        assertEquals("最新名称", current.getName());
        assertEquals(Set.of(PermissionCatalog.INVENTORY_READ.permission()), current.getPermissions());
    }

    /**
     * 创建用例独占角色。
     * @return 未持久化角色
     */
    private AuthorizationRole create() {
        var role = AuthorizationRole.create("role." + UUID.randomUUID().toString().replace("-", ""), "测试角色");
        ids.add(role.getId()); return role;
    }

    /**
     * 在Application事务内保存。
     * @param role 角色
     */
    private void save(AuthorizationRole role) { unitOfWork.execute(() -> { roles.save(role); return null; }); }

    /**
     * 在Application事务内重建写聚合。
     * @param id 角色UUID
     * @return 聚合
     */
    private AuthorizationRole load(String id) { return unitOfWork.execute(() -> roles.findById(id).orElseThrow()); }
}
