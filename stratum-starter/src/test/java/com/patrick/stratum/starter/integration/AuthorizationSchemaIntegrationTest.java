package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实MariaDB验证RBAC关系迁移，不授予生产账号权限或创建管理入口。 */
class AuthorizationSchemaIntegrationTest extends MariaDbIntegrationTest {
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final List<String> roleIds = new ArrayList<>();
    private final List<String> permissionIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();

    /**
     * 装配测试迁移断言和账号夹具。
     * @param jdbc 数据库断言客户端
     * @param unitOfWork 测试写事务
     * @param accounts 账号仓储
     * @param passwords 随机凭据散列器
     */
    @Autowired
    AuthorizationSchemaIntegrationTest(JdbcTemplate jdbc, UnitOfWork unitOfWork, LocalAccountRepository accounts, PasswordHasher passwords) {
        this.jdbc = jdbc; this.unitOfWork = unitOfWork; this.accounts = accounts; this.passwords = passwords;
    }

    /** 按外键顺序仅清理本用例角色、权限和账号。 */
    @AfterEach
    void clean() {
        for (String id : roleIds) {
            jdbc.update("DELETE FROM auth_user_role WHERE role_id = ?", id);
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id = ?", id);
            jdbc.update("DELETE FROM auth_role WHERE id = ?", id);
        }
        for (String id : permissionIds) jdbc.update("DELETE FROM auth_permission WHERE id = ?", id);
        for (String id : userIds) jdbc.update("DELETE FROM auth_user WHERE id = ?", id);
    }

    /** 角色编码唯一，权限按类型与编码唯一，API/FEAT同编码仍为两个独立权限。 */
    @Test
    void enforcesRoleAndTypedPermissionUniqueness() {
        String code = "erp." + UUID.randomUUID().toString().replace("-", "");
        role(code); permission(code, "API"); permission(code, "FEAT");
        assertThrows(DataIntegrityViolationException.class, () -> role(code));
        assertThrows(DataIntegrityViolationException.class, () -> permission(code, "API"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM auth_permission WHERE code = ?", Integer.class, code));
    }

    /** 非法类型、编码、UUID及负版本在数据库层拒绝。 */
    @Test
    void rejectsInvalidPersistedIdentifiersAndTypes() {
        for (String type : List.of("api", "feat", "DATA", "", "API ")) {
            assertThrows(DataIntegrityViolationException.class, () -> permission("erp.read", type));
        }
        for (String code : List.of("ERP.read", "erp.*", "erp..read", "", "a\n", "erp.read ", "a".repeat(97))) {
            assertThrows(DataIntegrityViolationException.class, () -> permission(code, "API"));
            assertThrows(DataIntegrityViolationException.class, () -> role(code));
        }
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_role(id,code,name) VALUES(?,?,?)", "invalid", "erp.invalid", "非法"));
        String role = role("erp." + UUID.randomUUID().toString().replace("-", ""));
        assertThrows(DataIntegrityViolationException.class, () -> write("UPDATE auth_role SET version=-1 WHERE id=?", role));
        assertThrows(DataIntegrityViolationException.class, () -> write("UPDATE auth_role SET name=' ' WHERE id=?", role));
    }

    /** 两类关系拒绝重复和不存在的引用，已引用的源数据不能直接删除。 */
    @Test
    void enforcesRelationshipIntegrityWithoutCascade() {
        String user = account(); String role = role("erp." + UUID.randomUUID().toString().replace("-", ""));
        String permission = permission("erp." + UUID.randomUUID().toString().replace("-", ""), "API");
        write("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", user, role);
        write("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", role, permission);
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", user, role));
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", role, permission));
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", UUID.randomUUID().toString(), role));
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", user, UUID.randomUUID().toString()));
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", role, UUID.randomUUID().toString()));
        assertThrows(DataIntegrityViolationException.class, () -> write("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", UUID.randomUUID().toString(), permission));
        assertThrows(DataIntegrityViolationException.class, () -> write("DELETE FROM auth_user WHERE id=?", user));
        assertThrows(DataIntegrityViolationException.class, () -> write("DELETE FROM auth_role WHERE id=?", role));
        assertThrows(DataIntegrityViolationException.class, () -> write("DELETE FROM auth_permission WHERE id=?", permission));
    }

    /** 角色与授权关系可以同一工作单元回滚，不能留下部分授权。 */
    @Test
    void rollsBackRoleAndAssignmentTogether() {
        String user = account(); String role = UUID.randomUUID().toString(); roleIds.add(role);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            jdbc.update("INSERT INTO auth_role(id,code,name) VALUES(?,?,?)", role, "rollback." + role.replace("-", ""), "回滚角色");
            jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", user, role);
            throw new IllegalStateException("回滚测试");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role WHERE id=?", Integer.class, role));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_user_role WHERE role_id=?", Integer.class, role));
    }

    /**
     * 创建角色测试记录，不表示已提供角色管理业务。
     * @param code 角色编码
     * @return UUID
     */
    private String role(String code) {
        String id = UUID.randomUUID().toString(); roleIds.add(id);
        write("INSERT INTO auth_role(id,code,name) VALUES(?,?,?)", id, code, "测试角色"); return id;
    }

    /**
     * 创建权限测试记录。
     * @param code 权限编码
     * @param type 权限类型
     * @return UUID
     */
    private String permission(String code, String type) {
        String id = UUID.randomUUID().toString(); permissionIds.add(id);
        write("INSERT INTO auth_permission(id,code,type,name) VALUES(?,?,?,?)", id, code, type, "测试权限"); return id;
    }

    /** @return 已在写工作单元保存的普通测试账号UUID */
    private String account() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("schema-" + UUID.randomUUID(), passwords.hash(raw), false);
            userIds.add(account.getId()); unitOfWork.execute(() -> { accounts.insert(account); return null; }); return account.getId();
        } finally { java.util.Arrays.fill(raw, '\0'); }
    }

    /**
     * 通过Application工作单元执行测试夹具写入。
     * @param sql 固定测试SQL
     * @param values 绑定参数
     */
    private void write(String sql, Object... values) { unitOfWork.execute(() -> jdbc.update(sql, values)); }
}
