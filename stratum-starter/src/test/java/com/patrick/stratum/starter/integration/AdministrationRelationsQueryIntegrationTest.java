package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AccountManagementView;
import com.patrick.stratum.query.auth.AccountRolesQuery;
import com.patrick.stratum.query.auth.AccountRolesQueryHandler;
import com.patrick.stratum.query.auth.AdministrationQueryAccess;
import com.patrick.stratum.query.auth.RolePermissionsQuery;
import com.patrick.stratum.query.auth.RolePermissionsQueryHandler;
import com.patrick.stratum.query.auth.RoleView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实库验证管理关系详情的完整快照、数量边界、缺失目标与可信管理员检查。 */
class AdministrationRelationsQueryIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private UnitOfWork work;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private PasswordHasher passwords;
    @Autowired private AdministrationQueryAccess access;
    @Autowired private JdbcTemplate jdbc;
    private final List<UUID> ownedRoles = new ArrayList<>();
    private final List<UUID> ownedPermissions = new ArrayList<>();
    private UUID target;

    /** 创建独立普通账号，父夹具负责实际登录的管理员。 */
    @BeforeEach
    void prepare() {
        char[] secret = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("relations-" + UUID.randomUUID(), passwords.hash(secret), false);
            target = UUID.fromString(account.getId()); work.execute(() -> { accounts.insert(account); return null; });
        } finally { Arrays.fill(secret, '\0'); }
    }

    /** 先删除自身关系再删除目标和越界夹具，不影响目录及管理员。 */
    @AfterEach
    void clean() {
        if (target != null) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", target.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", target.toString());
        }
        for (UUID role : ownedRoles) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", role.toString());
            jdbc.update("DELETE FROM auth_role WHERE id=?", role.toString());
        }
        jdbc.batchUpdate("DELETE FROM auth_permission WHERE id=?", ownedPermissions.stream().map(id -> new Object[]{id.toString()}).toList());
    }

    /** 空关系不等于目标不存在，同事务关系与版本在单语句结果中同步可见，快照不可变。 */
    @Test
    void readsCompleteImmutableRelationsAndVersions() {
        UUID role = role();
        var emptyAccount = gateway.execute(AccountRolesQuery.create(target), context()).getItems().getFirst();
        var emptyRole = gateway.execute(RolePermissionsQuery.create(role), context()).getItems().getFirst();
        assertTrue(emptyAccount.roleIds().isEmpty()); assertTrue(emptyRole.permissions().isEmpty());
        assertEquals(0, gateway.execute(AccountRolesQuery.create(UUID.randomUUID()), context()).getTotal());
        assertEquals(0, gateway.execute(RolePermissionsQuery.create(UUID.randomUUID()), context()).getTotal());
        var grants = Set.of(PermissionCatalog.MATERIAL_READ.permission(), PermissionCatalog.MATERIAL_FEATURE.permission());
        work.execute(() -> {
            administration.lock(); administration.replaceRoleIds(target, Set.of(role));
            var account = accounts.findById(target.toString()).orElseThrow(); accounts.updateStatus(account);
            var configured = roles.findById(role.toString()).orElseThrow(); configured.replacePermissions(grants); roles.save(configured); return null;
        });
        var member = gateway.execute(AccountRolesQuery.create(target), context()).getItems().getFirst();
        var granted = gateway.execute(RolePermissionsQuery.create(role), context()).getItems().getFirst();
        assertEquals(1, member.version()); assertEquals(Set.of(role), member.roleIds());
        assertEquals(1, granted.version()); assertEquals(grants, granted.permissions());
        assertThrows(UnsupportedOperationException.class, () -> member.roleIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> granted.permissions().clear());
        work.execute(() -> {
            administration.replaceRoleIds(target, Set.of());
            var configured = roles.findById(role.toString()).orElseThrow(); configured.replacePermissions(Set.of()); roles.save(configured); return null;
        });
        assertTrue(gateway.execute(AccountRolesQuery.create(target), context()).getItems().getFirst().roleIds().isEmpty());
        assertTrue(gateway.execute(RolePermissionsQuery.create(role), context()).getItems().getFirst().permissions().isEmpty());
        assertEquals(grants, granted.permissions());
    }

    /** 64个角色完整返回，第65个关系使读取失败而非静默丢失授权。 */
    @Test
    void rejectsRoleMembershipOverflow() {
        for (int index = 0; index < 64; index++) {
            UUID role = role(); jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", target.toString(), role.toString());
        }
        assertEquals(64, gateway.execute(AccountRolesQuery.create(target), context()).getItems().getFirst().roleIds().size());
        UUID overflow = role(); jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", target.toString(), overflow.toString());
        assertEquals(CommonErrorCode.INTERNAL_ERROR, assertThrows(StratumException.class,
                () -> gateway.execute(AccountRolesQuery.create(target), context())).getErrorCode());
    }

    /** 1024项精确权限完整返回，第1025项使读取失败；测试夹具不受正常目录写入口限制。 */
    @Test
    void rejectsPermissionOverflow() {
        UUID role = role(); String prefix = "relations." + UUID.randomUUID().toString().replace("-", "");
        var permissionRows = new ArrayList<Object[]>(); var relationRows = new ArrayList<Object[]>();
        for (int index = 0; index < 1025; index++) {
            UUID id = UUID.randomUUID(); ownedPermissions.add(id);
            permissionRows.add(new Object[]{id.toString(), prefix + "." + index, "API", "边界权限"});
            relationRows.add(new Object[]{role.toString(), id.toString()});
        }
        work.execute(() -> {
            jdbc.batchUpdate("INSERT INTO auth_permission(id,code,type,name) VALUES(?,?,?,?)", permissionRows);
            jdbc.batchUpdate("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", relationRows.subList(0, 1024)); return null;
        });
        assertEquals(1024, gateway.execute(RolePermissionsQuery.create(role), context()).getItems().getFirst().permissions().size());
        jdbc.update("INSERT INTO auth_role_permission(role_id,permission_id) VALUES(?,?)", relationRows.getLast());
        assertEquals(CommonErrorCode.INTERNAL_ERROR, assertThrows(StratumException.class,
                () -> gateway.execute(RolePermissionsQuery.create(role), context())).getErrorCode());
    }

    /** 两种关系查询均先检查管理员资格，不泄漏匿名或普通账号可探测的目标关系。 */
    @Test
    void enforcesAdministratorAndTransactionBoundary() {
        UUID role = role(); var accountQuery = AccountRolesQuery.create(target); var roleQuery = RolePermissionsQuery.create(role);
        var ordinary = new QueryContext.Snapshot("relations-query", Optional.of(target.toString()), Set.of("auth.account.manage", "auth.role.manage"));
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(accountQuery, ordinary)).getErrorCode());
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, () -> gateway.execute(roleQuery, ordinary)).getErrorCode());
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, () -> gateway.execute(accountQuery, QueryContext.anonymous("relations-query"))).getErrorCode());
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, () -> gateway.execute(roleQuery, QueryContext.anonymous("relations-query"))).getErrorCode());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(accountQuery, context())));
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(roleQuery, context())));
    }

    /** 两种目标查询都将ORM故障转换为无原因链的内部错误。 */
    @Test
    void sanitizesRelationshipStorageFailures() {
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(AccountManagementView.class)).thenThrow(new EasyQueryException("sensitive-relations"));
        when(database.queryable(RoleView.class)).thenThrow(new EasyQueryException("sensitive-relations"));
        var accountHandler = new AccountRolesQueryHandler(database, access); var roleHandler = new RolePermissionsQueryHandler(database, access);
        var first = assertThrows(StratumException.class, () -> accountHandler.handle(AccountRolesQuery.create(target), context()));
        var second = assertThrows(StratumException.class, () -> roleHandler.handle(RolePermissionsQuery.create(UUID.randomUUID()), context()));
        for (var failure : List.of(first, second)) {
            assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
            assertFalse(failure.toString().contains("sensitive-relations"));
        }
    }

    /**
     * 创建本测试普通空权限角色。
     * @return 领域生成的角色UUID
     */
    private UUID role() {
        var role = AuthorizationRole.create("relations-" + UUID.randomUUID(), "关系测试");
        UUID id = UUID.fromString(role.getId()); ownedRoles.add(id); work.execute(() -> { roles.save(role); return null; }); return id;
    }

    /**
     * 使用真实登录管理员构造服务器上下文。
     * @return 不采信客户端权限声明的上下文
     */
    private QueryContext context() { return new QueryContext.Snapshot("relations-query", Optional.of(subjectId()), Set.of()); }
}
