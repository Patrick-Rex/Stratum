package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证角色仓库迁移、首次保存默认值、类别约束、引用保护及原子回滚。 */
class RoleWarehouseSchemaIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    private final List<String> roleIds = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /** 精确清理本测试角色和主数据，范围从属关系由数据库级联清理。 */
    @AfterEach
    void clean() {
        for (String id : roleIds) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", id);
            jdbc.update("DELETE FROM auth_role WHERE id=?", id);
        }
        for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
    }

    /** 角色生产仓储首次保存同事务登记独立配置，管理员ALL而普通角色为空CUSTOM。 */
    @Test
    void initializesNewRoleScopesWithoutChangingRoleVersions() {
        String ordinary = role();
        String administrator = work.execute(() -> {
            roles.registerCatalog();
            return roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                var created = AuthorizationRole.administrator(); roleIds.add(created.getId()); roles.save(created); return created;
            }).getId();
        });
        assertEquals("CUSTOM", type(ordinary)); assertEquals("ALL", type(administrator));
        assertEquals(0L, version(ordinary));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_warehouse WHERE role_id=?", Integer.class, ordinary));
        work.execute(() -> { var role = roles.findById(ordinary).orElseThrow(); role.rename("改名"); roles.save(role); return null; });
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, ordinary));
        assertEquals(0L, version(ordinary)); assertEquals("CUSTOM", type(ordinary));
    }

    /**
     * 使用实际迁移资源的回填语句验证既有普通角色默认值，仅限定本测试自建记录。
     * @throws Exception 迁移资源读取失败
     */
    @Test
    void backfillsLegacyRoleUsingTheActualMigrationStatement() throws Exception {
        String id = UUID.randomUUID().toString(); roleIds.add(id);
        jdbc.update("INSERT INTO auth_role(id,code,name,version) VALUES(?,?,?,7)", id, "legacy-" + id, "迁移前角色");
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V202610250001__create_role_warehouse_scope.sql")) {
            assertNotNull(input); String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String backfill = migration.substring(migration.indexOf("INSERT INTO auth_role_warehouse_scope"));
            backfill = backfill.strip().replaceFirst(";\\s*$", "") + " WHERE id=?";
            assertEquals(1, jdbc.update(backfill, id));
        }
        assertEquals("CUSTOM", type(id)); assertEquals(0L, version(id));
        assertEquals(7L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='202610250001' AND success=1", Integer.class));
    }

    /** 类型、独立版本、指定集合唯一性和仓库类别均由真实数据库约束，未知引用被拒绝。 */
    @Test
    void enforcesScopeTypesAndWarehouseCategoryReferences() {
        String role = role(); String warehouse = master(MasterData.Kind.WAREHOUSE), material = master(MasterData.Kind.MATERIAL);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth_role_warehouse_scope SET scope_type='ORG' WHERE role_id=?", role));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth_role_warehouse_scope SET version=-1 WHERE role_id=?", role));
        for (String invalid : List.of("ALL ", "all", "ALL\n")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth_role_warehouse_scope SET scope_type=? WHERE role_id=?", invalid, role));
        }
        assertThrows(DataIntegrityViolationException.class, () -> relation(role, UUID.randomUUID().toString()));
        assertThrows(DataIntegrityViolationException.class, () -> relation(role, material));
        assertThrows(DataIntegrityViolationException.class, () -> relation(UUID.randomUUID().toString(), warehouse));
        relation(role, warehouse);
        assertThrows(DataIntegrityViolationException.class, () -> relation(role, warehouse));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth_role_warehouse SET warehouse_kind='MATERIAL' WHERE role_id=?", role));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth_role_warehouse SET warehouse_kind='WAREHOUSE ' WHERE role_id=?", role));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE auth_role_warehouse_scope SET scope_type='ALL' WHERE role_id=?", role));
        jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role);
        jdbc.update("UPDATE auth_role_warehouse_scope SET scope_type='ALL' WHERE role_id=?", role);
        assertThrows(DataIntegrityViolationException.class, () -> relation(role, warehouse));
    }

    /** 指定仓库仍被配置引用时不能删除；删除本测试角色时仅其从属配置和集合一起清理。 */
    @Test
    void protectsReferencedWarehouseAndCleansOnlyDependentScopeRows() {
        String role = role(), other = role(), warehouse = master(MasterData.Kind.WAREHOUSE); relation(role, warehouse);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM erp_master_data WHERE id=?", warehouse));
        jdbc.update("DELETE FROM auth_role WHERE id=?", role);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_warehouse_scope WHERE role_id=?", Integer.class, role));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_warehouse WHERE role_id=?", Integer.class, role));
        assertEquals("CUSTOM", type(other));
        assertEquals(1, jdbc.update("DELETE FROM erp_master_data WHERE id=?", warehouse));
    }

    /** 角色、默认配置及指定关系随同一工作单元回滚，不遗留部分授权。 */
    @Test
    void rollsBackRoleAndInitialScopeWithAssignments() {
        var role = AuthorizationRole.create("rollback-" + UUID.randomUUID(), "回滚角色"); roleIds.add(role.getId());
        String warehouse = master(MasterData.Kind.WAREHOUSE);
        assertThrows(IllegalStateException.class, () -> work.execute(() -> {
            roles.save(role); relation(role.getId(), warehouse); throw new IllegalStateException("测试回滚");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role WHERE id=?", Integer.class, role.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_warehouse_scope WHERE role_id=?", Integer.class, role.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_warehouse WHERE role_id=?", Integer.class, role.getId()));
    }

    /**
     * 通过生产仓储创建普通测试角色。
     * @return 角色UUID
     */
    private String role() {
        var role = AuthorizationRole.create("scope-" + UUID.randomUUID(), "仓库配置测试"); roleIds.add(role.getId());
        work.execute(() -> { roles.save(role); return null; }); return role.getId();
    }

    /**
     * 保存测试主数据用于真实类别外键断言。
     * @param kind 仓库或物料
     * @return 主数据UUID
     */
    private String master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "范围引用", kind == MasterData.Kind.MATERIAL ? "件" : null);
        masterIds.add(value.getId()); work.execute(() -> { masters.save(value); return null; }); return value.getId();
    }

    /**
     * 插入本测试指定关系以验证数据库约束。
     * @param role 角色UUID
     * @param warehouse 目标UUID
     */
    private void relation(String role, String warehouse) { jdbc.update("INSERT INTO auth_role_warehouse(role_id,warehouse_id) VALUES(?,?)", role, warehouse); }

    /**
     * 读取独立配置类型，避免将角色API版本作为范围版本。
     * @param role 角色UUID
     * @return 类型编码
     */
    private String type(String role) { return jdbc.queryForObject("SELECT scope_type FROM auth_role_warehouse_scope WHERE role_id=?", String.class, role); }

    /**
     * 读取独立配置版本。
     * @param role 角色UUID
     * @return 范围版本
     */
    private long version(String role) { return jdbc.queryForObject("SELECT version FROM auth_role_warehouse_scope WHERE role_id=?", Long.class, role); }
}
