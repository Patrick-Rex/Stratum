package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.infrastructure.persistence.auth.PermissionRecord;
import com.patrick.stratum.infrastructure.persistence.auth.RolePermissionRecord;
import com.patrick.stratum.infrastructure.persistence.auth.RoleRecord;
import com.patrick.stratum.infrastructure.persistence.auth.UserRoleRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 验证实际Easy-Query映射与代理，所有写入只在Application工作单元内作为测试夹具执行。 */
class AuthorizationMappingIntegrationTest extends MariaDbIntegrationTest {
    private final EasyEntityQuery database;
    private final UnitOfWork unitOfWork;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final JdbcTemplate jdbc;
    private final List<String> roleIds = new ArrayList<>();
    private final List<String> permissionIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    /**
     * 装配真实ORM和测试事务。
     * @param database ORM客户端
     * @param unitOfWork 写事务
     * @param accounts 账号夹具仓储
     * @param passwords 随机密码散列器
     * @param jdbc 独立断言及清理客户端
     */
    @Autowired
    AuthorizationMappingIntegrationTest(EasyEntityQuery database, UnitOfWork unitOfWork, LocalAccountRepository accounts,
            PasswordHasher passwords, JdbcTemplate jdbc) {
        this.database = database; this.unitOfWork = unitOfWork; this.accounts = accounts; this.passwords = passwords; this.jdbc = jdbc;
    }

    /** 按引用顺序清理自己的测试授权，不扫描或删除其他账号的数据。 */
    @AfterEach
    void clean() {
        for (String role : roleIds) {
            jdbc.update("DELETE FROM auth_user_role WHERE role_id=?", role);
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", role);
            jdbc.update("DELETE FROM auth_role WHERE id=?", role);
        }
        for (String permission : permissionIds) jdbc.update("DELETE FROM auth_permission WHERE id=?", permission);
        for (String user : userIds) jdbc.update("DELETE FROM auth_user WHERE id=?", user);
    }

    /** 四张表的列映射、API/FEAT同编码及两类复合主键均保持正确。 */
    @Test
    void roundTripsTypedPermissionsAndCompositeRelations() {
        String user = account(); var role = role(); var secondRole = role();
        var api = permission("API"); var feature = permission("FEAT"); feature.setCode(api.getCode());
        unitOfWork.execute(() -> {
            assertEquals(1, database.insertable(role).executeRows());
            assertEquals(1, database.insertable(secondRole).executeRows());
            assertEquals(1, database.insertable(api).executeRows());
            assertEquals(1, database.insertable(feature).executeRows());
            database.insertable(userRole(user, role.getId())).executeRows();
            database.insertable(userRole(user, secondRole.getId())).executeRows();
            database.insertable(rolePermission(role.getId(), api.getId())).executeRows();
            database.insertable(rolePermission(role.getId(), feature.getId())).executeRows();
            return null;
        });
        var loaded = database.queryable(RoleRecord.class).where(row -> row.id().eq(role.getId())).singleOrNull();
        assertNotNull(loaded); assertEquals(role.getCode(), loaded.getCode()); assertEquals("测试角色", loaded.getName()); assertEquals(0, loaded.getVersion());
        var permissions = database.queryable(PermissionRecord.class).where(row -> row.code().eq(api.getCode())).toList();
        assertEquals(2, permissions.size());
        assertEquals(java.util.Set.of("API", "FEAT"), permissions.stream().map(PermissionRecord::getType).collect(java.util.stream.Collectors.toSet()));
        var membership = database.queryable(UserRoleRecord.class).where(row -> { row.userId().eq(user); row.roleId().eq(role.getId()); }).singleOrNull();
        assertNotNull(membership); assertEquals(user, membership.getUserId());
        var grants = database.queryable(RolePermissionRecord.class).where(row -> row.roleId().eq(role.getId())).toList();
        assertEquals(2, grants.size());
        assertEquals(1, unitOfWork.execute(() -> database.deletable(membership).allowDeleteStatement(true).executeRows()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user_role WHERE user_id=?", Integer.class, user));
        assertEquals(secondRole.getId(), jdbc.queryForObject("SELECT role_id FROM auth_user_role WHERE user_id=?", String.class, user));
        assertEquals(1, unitOfWork.execute(() -> database.deletable(grants.getFirst()).allowDeleteStatement(true).executeRows()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_permission WHERE role_id=?", Integer.class, role.getId()));
    }

    /** ORM写入角色和关系后发生失败时整体回滚，不留下部分授权。 */
    @Test
    void rollsBackOrmRoleAndMembershipTogether() {
        String user = account(); var role = role();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            database.insertable(role).executeRows(); database.insertable(userRole(user, role.getId())).executeRows();
            throw new IllegalStateException("测试回滚");
        }));
        assertNull(database.queryable(RoleRecord.class).where(row -> row.id().eq(role.getId())).singleOrNull());
        assertTrue(database.queryable(UserRoleRecord.class).where(row -> row.userId().eq(user)).toList().isEmpty());
    }

    /** @return 尚未持久化的角色测试映射 */
    private RoleRecord role() {
        var row = new RoleRecord(); row.setId(UUID.randomUUID().toString()); roleIds.add(row.getId());
        row.setCode("role." + row.getId().replace("-", "")); row.setName("测试角色"); row.setVersion(0L); return row;
    }

    /**
     * 构造权限映射，不作为生产权限注册入口。
     * @param type 权限类型
     * @return 尚未持久化的权限
     */
    private PermissionRecord permission(String type) {
        var row = new PermissionRecord(); row.setId(UUID.randomUUID().toString()); permissionIds.add(row.getId());
        row.setCode("permission." + row.getId().replace("-", "")); row.setType(type); row.setName("测试权限"); return row;
    }

    /**
     * 构造账号角色关系。
     * @param user 账号UUID
     * @param role 角色UUID
     * @return 关系映射
     */
    private static UserRoleRecord userRole(String user, String role) {
        var row = new UserRoleRecord(); row.setUserId(user); row.setRoleId(role); return row;
    }

    /**
     * 构造角色权限关系。
     * @param role 角色UUID
     * @param permission 权限UUID
     * @return 关系映射
     */
    private static RolePermissionRecord rolePermission(String role, String permission) {
        var row = new RolePermissionRecord(); row.setRoleId(role); row.setPermissionId(permission); return row;
    }

    /** @return 已保存的普通账号UUID */
    private String account() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("mapping-" + UUID.randomUUID(), passwords.hash(raw), false);
            userIds.add(account.getId()); unitOfWork.execute(() -> { accounts.insert(account); return null; }); return account.getId();
        } finally { java.util.Arrays.fill(raw, '\0'); }
    }
}
