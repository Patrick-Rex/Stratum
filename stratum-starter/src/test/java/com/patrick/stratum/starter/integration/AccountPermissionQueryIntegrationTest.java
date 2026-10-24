package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AccountPermissionQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实QueryGateway与MariaDB验证账号权限快照及有界读取，不通过HTTP暴露权限读取器。 */
class AccountPermissionQueryIntegrationTest extends MariaDbIntegrationTest {
    private final QueryGateway gateway;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> roleIds = new ArrayList<>();
    private final List<String> permissionIds = new ArrayList<>();

    /**
     * 注入只读入口与独立夹具依赖。
     * @param gateway 查询网关
     * @param unitOfWork 夹具事务
     * @param jdbc 独立写夹具和清理客户端
     * @param accounts 账号仓储
     * @param passwords 随机凭据散列器
     */
    @Autowired
    AccountPermissionQueryIntegrationTest(QueryGateway gateway, UnitOfWork unitOfWork, JdbcTemplate jdbc,
            LocalAccountRepository accounts, PasswordHasher passwords) {
        this.gateway = gateway; this.unitOfWork = unitOfWork; this.jdbc = jdbc; this.accounts = accounts; this.passwords = passwords;
    }

    /** 仅按本测试随机标识清理，先删除引用关系。 */
    @AfterEach
    void clean() {
        for (String role : roleIds) {
            jdbc.update("DELETE FROM auth_user_role WHERE role_id=?", role);
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", role);
            jdbc.update("DELETE FROM auth_role WHERE id=?", role);
        }
        jdbc.batchUpdate("DELETE FROM auth_permission WHERE id=?", permissionIds.stream().map(id -> new Object[]{id}).toList());
        for (String user : userIds) jdbc.update("DELETE FROM auth_user WHERE id=?", user);
    }

    /** 多角色重复授权只出现一次，同编码API与FEAT隔离，其他账号权限不混入。 */
    @Test
    void readsDistinctTypedPermissionsForOnlyTheTrustedAccount() {
        UUID user = account(); UUID other = account(); String first = role(user); String second = role(user); String foreign = role(other);
        String code = "erp." + UUID.randomUUID().toString().replace("-", "");
        String api = permission(code, "API"); String feature = permission(code, "FEAT");
        grant(first, api); grant(second, api); grant(second, feature);
        grant(foreign, permission("foreign." + UUID.randomUUID().toString().replace("-", ""), "API"));
        var snapshot = read(user).orElseThrow();
        assertEquals(user, snapshot.userId()); assertTrue(snapshot.enabled()); assertEquals(2, snapshot.permissions().size());
        assertEquals(Set.of(code), snapshot.codes(Permission.Type.API)); assertEquals(Set.of(code), snapshot.codes(Permission.Type.FEAT));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.permissions().clear());
    }

    /** 已存在无权限账号与未知账号区分，禁用状态阻止许可，后续读取反映关系删除。 */
    @Test
    void distinguishesMissingEmptyDisabledAndChangedAccounts() {
        UUID user = account(); assertTrue(read(user).orElseThrow().permissions().isEmpty());
        assertTrue(read(UUID.randomUUID()).isEmpty());
        String role = role(user); String code = "erp." + UUID.randomUUID().toString().replace("-", "");
        String permission = permission(code, "API"); grant(role, permission);
        var required = new Permission(code, Permission.Type.API); assertTrue(read(user).orElseThrow().permits(required));
        write("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        var disabled = read(user).orElseThrow(); assertFalse(disabled.enabled()); assertFalse(disabled.permits(required));
        assertTrue(disabled.codes(Permission.Type.API).isEmpty());
        write("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString());
        write("DELETE FROM auth_role_permission WHERE role_id=? AND permission_id=?", role, permission);
        assertTrue(read(user).orElseThrow().permissions().isEmpty());
    }

    /** 查询拒绝匿名、冒用其他上下文主体、空ID和写事务，不能用请求权限集合绕过绑定。 */
    @Test
    void rejectsUntrustedContextAndTransactions() {
        UUID user = account(); var query = AccountPermissionQuery.create(user);
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class,
                () -> gateway.execute(query, QueryContext.anonymous("permission-test"))).getErrorCode());
        var forged = new QueryContext.Snapshot("permission-test", Optional.of(UUID.randomUUID().toString()), Set.of("erp.all"));
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(query, forged)).getErrorCode());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> read(user)));
        assertThrows(NullPointerException.class, () -> AccountPermissionQuery.create(null));
    }

    /** 最大权限集合加一个无权限角色仍可读取，超过上限拒绝而非返回截断授权。 */
    @Test
    void boundsDistinctResultsWithoutCountingEmptyRolesAsGrants() {
        UUID user = account(); String role = role(user); role(user);
        var permissionRows = new ArrayList<Object[]>(); var relationRows = new ArrayList<Object[]>();
        String prefix = "limit." + UUID.randomUUID().toString().replace("-", "");
        for (int index = 0; index < AccountPermissions.Snapshot.MAX_PERMISSIONS; index++) {
            String id = UUID.randomUUID().toString(); permissionIds.add(id);
            permissionRows.add(new Object[]{id, prefix + "." + index, "API", "边界权限"}); relationRows.add(new Object[]{role, id});
        }
        unitOfWork.execute(() -> {
            jdbc.batchUpdate("INSERT INTO auth_permission(id,code,type,name) VALUES(?,?,?,?)", permissionRows);
            jdbc.batchUpdate("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", relationRows); return null;
        });
        assertEquals(1024, read(user).orElseThrow().permissions().size());
        grant(role, permission(prefix + ".extra", "API"));
        var failure = assertThrows(StratumException.class, () -> read(user));
        assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
    }

    /**
     * 经正式网关按可信身份读取。
     * @param user 账号UUID
     * @return 内部权限快照
     */
    private Optional<AccountPermissions.Snapshot> read(UUID user) {
        return gateway.execute(AccountPermissionQuery.create(user),
                new QueryContext.Snapshot("permission-test", Optional.of(user.toString()), Set.of())).getItems().stream().findFirst();
    }

    /** @return 已保存普通账号UUID */
    private UUID account() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("query-" + UUID.randomUUID(), passwords.hash(raw), false);
            userIds.add(account.getId()); unitOfWork.execute(() -> { accounts.insert(account); return null; }); return UUID.fromString(account.getId());
        } finally { java.util.Arrays.fill(raw, '\0'); }
    }

    /**
     * 创建并绑定测试角色。
     * @param user 账号UUID
     * @return 角色UUID
     */
    private String role(UUID user) {
        String id = UUID.randomUUID().toString(); roleIds.add(id);
        unitOfWork.execute(() -> {
            jdbc.update("INSERT INTO auth_role(id,code,name) VALUES(?,?,?)", id, "role." + id.replace("-", ""), "查询角色");
            jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", user.toString(), id); return null;
        }); return id;
    }

    /**
     * 新建精确权限测试记录。
     * @param code 权限编码
     * @param type 类型
     * @return 权限UUID
     */
    private String permission(String code, String type) {
        String id = UUID.randomUUID().toString(); permissionIds.add(id);
        write("INSERT INTO auth_permission(id,code,type,name) VALUES(?,?,?,?)", id, code, type, "查询权限"); return id;
    }

    /**
     * 绑定测试授权。
     * @param role 角色UUID
     * @param permission 权限UUID
     */
    private void grant(String role, String permission) { write("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", role, permission); }

    /**
     * 仅在测试工作单元内写入夹具。
     * @param sql 固定SQL
     * @param values 绑定参数
     */
    private void write(String sql, Object... values) { unitOfWork.execute(() -> jdbc.update(sql, values)); }
}
