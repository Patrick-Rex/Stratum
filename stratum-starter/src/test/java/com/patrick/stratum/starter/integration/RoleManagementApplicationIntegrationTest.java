package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.RoleManagementApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证角色管理权限、整组授权替换、版本并发与回滚。 */
class RoleManagementApplicationIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private AccountPermissions permissions;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoleManagementApplicationService configuredService;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> roleCodes = new ArrayList<>();
    private String createdAdminRole;
    private UUID administratorRole;
    private UUID administrator;

    /** 为本测试管理员准备真实角色关系，保留已有目录和管理员角色。 */
    @BeforeEach
    void prepare() {
        administratorRole = work.execute(() -> {
            administration.lock(); roles.registerCatalog();
            var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                var created = AuthorizationRole.administrator(); createdAdminRole = created.getId(); roles.save(created); return created;
            });
            return UUID.fromString(role.getId());
        });
        administrator = account();
        work.execute(() -> { administration.replaceRoleIds(administrator, Set.of(administratorRole)); return null; });
    }

    /** 清理本测试账号与角色，不移除已有系统目录。 */
    @AfterEach
    void clean() {
        for (UUID user : users) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", user.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", user.toString());
        }
        for (String code : roleCodes) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id IN (SELECT id FROM auth_role WHERE code=?)", code);
            jdbc.update("DELETE FROM auth_role WHERE code=?", code);
        }
        if (createdAdminRole != null) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", createdAdminRole);
            jdbc.update("DELETE FROM auth_role WHERE id=?", createdAdminRole);
        }
    }

    /** 新建普通角色无默认权限，固定编码唯一且保留编码不能由管理创建入口占用。 */
    @Test
    void createsEmptyRoleAndRejectsDuplicateOrReservedCode() {
        String code = code(); var service = service(administrator, work);
        UUID id = service.create(code, "采购员"); var role = load(id);
        assertEquals(code, role.getCode()); assertEquals("采购员", role.getName());
        assertEquals(0L, role.getVersion()); assertTrue(role.getPermissions().isEmpty());
        error(CommonErrorCode.CONFLICT, () -> service.create(code, "重复角色"));
        assertThrows(IllegalArgumentException.class, () -> service.create(AuthorizationRole.ADMINISTRATOR_CODE, "覆盖管理员"));
    }

    /** 角色授权通过真实查询即时可见，精确区分API与FEAT，清空后旧权限消失。 */
    @Test
    void replacesPermissionsImmediatelyAndPreservesCode() {
        var service = service(administrator, work); String code = code();
        UUID role = service.create(code, "采购员"); UUID user = account();
        work.execute(() -> { administration.replaceRoleIds(user, Set.of(role)); return null; });
        assertTrue(permissions.findByUserId(user, "role-management-test").orElseThrow().permissions().isEmpty());
        var grants = Set.of(PermissionCatalog.PURCHASE_READ.permission(), PermissionCatalog.PURCHASE_FEATURE.permission());
        assertEquals(1, service.configure(role, 0, "采购主管", grants));
        assertEquals(grants, permissions.findByUserId(user, "role-management-test").orElseThrow().permissions());
        assertEquals(code, load(role).getCode()); assertEquals("采购主管", load(role).getName());
        error(CommonErrorCode.CONFLICT, () -> service.configure(role, 0, "旧版本", Set.of()));
        assertEquals(2, service.configure(role, 1, "只读名称", Set.of()));
        assertTrue(permissions.findByUserId(user, "role-management-test").orElseThrow().permissions().isEmpty());
    }

    /** 未登记授权、非法名称及保留管理员角色配置均整体拒绝，原版本与权限保持不变。 */
    @Test
    void rejectsInvalidConfigurationWithoutChangingState() {
        var service = service(administrator, work); UUID role = service.create(code(), "原名称");
        assertThrows(IllegalArgumentException.class, () -> service.configure(role, 0, "非法授权",
                Set.of(new Permission("unregistered.permission", Permission.Type.API))));
        assertThrows(IllegalArgumentException.class, () -> service.configure(role, 0, " ", Set.of()));
        var adminBefore = load(administratorRole);
        assertThrows(IllegalArgumentException.class, () -> service.configure(administratorRole, adminBefore.getVersion(), "改写管理员", Set.of()));
        assertEquals(PermissionCatalog.permissions(), load(administratorRole).getPermissions());
        assertEquals(adminBefore.getVersion(), load(administratorRole).getVersion());
        assertEquals("原名称", load(role).getName()); assertEquals(0L, load(role).getVersion());
        error(CommonErrorCode.NOT_FOUND, () -> service.configure(UUID.randomUUID(), 0, "不存在", Set.of()));
    }

    /** 当前普通或停用账号不能创建/配置角色，真实Spring入口拒绝已有事务。 */
    @Test
    void requiresCurrentEnabledAdministratorAndIndependentTransaction() {
        UUID role = service(administrator, work).create(code(), "角色"); UUID ordinary = account();
        String rejectedCode = code();
        error(CommonErrorCode.FORBIDDEN, () -> service(ordinary, work).create(rejectedCode, "越权"));
        error(CommonErrorCode.FORBIDDEN, () -> service(ordinary, work).configure(role, 0, "越权", Set.of()));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", administrator.toString());
        error(CommonErrorCode.FORBIDDEN, () -> service(administrator, work).configure(role, 0, "停用操作", Set.of()));
        assertThrows(IllegalTransactionStateException.class, () -> work.execute(() -> configuredService.create(rejectedCode, "外层事务")));
        assertEquals("角色", load(role).getName());
    }

    /** 在写入角色和权限关系后注入提交前故障，二者必须同时恢复原状态。 */
    @Test
    void rollsBackNameVersionAndPermissionReplacement() {
        UUID role = service(administrator, work).create(code(), "原名称");
        UnitOfWork failing = new UnitOfWork() {
            /** {@inheritDoc} */
            @Override public <T> T execute(java.util.function.Supplier<T> action) {
                return work.execute(() -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        /** {@inheritDoc} */
                        @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("测试提交失败"); }
                    });
                    return action.get();
                });
            }
        };
        assertThrows(IllegalStateException.class, () -> service(administrator, failing).configure(role, 0, "失败修改",
                Set.of(PermissionCatalog.INVENTORY_READ.permission())));
        var unchanged = load(role);
        assertEquals("原名称", unchanged.getName()); assertEquals(0L, unchanged.getVersion()); assertTrue(unchanged.getPermissions().isEmpty());
    }

    /**
     * 并发配置同一预期版本只能提交一份，不混合两个请求的名称和权限。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentConfigurationsHaveOneWinner() throws Exception {
        var service = service(administrator, work); UUID role = service.create(code(), "原名称");
        var choices = List.of(PermissionCatalog.PURCHASE_READ.permission(), PermissionCatalog.SALES_READ.permission());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = choices.stream().map(permission -> executor.submit(() -> {
                try { service.configure(role, 0, permission.code(), Set.of(permission)); return 1; }
                catch (StratumException failure) { assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 0; }
            })).toList();
            assertEquals(1, jobs.getFirst().get(15, TimeUnit.SECONDS) + jobs.getLast().get(15, TimeUnit.SECONDS));
        }
        var winner = load(role);
        assertEquals(1L, winner.getVersion()); assertEquals(1, winner.getPermissions().size());
        assertEquals(winner.getName(), winner.getPermissions().iterator().next().code());
    }

    /** @return 本用例普通账号UUID */
    private UUID account() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("role-management-" + UUID.randomUUID(), passwords.hash(raw), false);
            UUID id = UUID.fromString(account.getId()); users.add(id);
            work.execute(() -> { accounts.insert(account); return null; }); return id;
        } finally { Arrays.fill(raw, '\0'); }
    }

    /** @return 已登记清理的随机角色编码 */
    private String code() { String code = "role-" + UUID.randomUUID(); roleCodes.add(code); return code; }

    /**
     * 组装可信身份下的角色管理用例。
     * @param actor 操作者UUID
     * @param unit 工作单元，可注入失败
     * @return 应用用例
     */
    private RoleManagementApplicationService service(UUID actor, UnitOfWork unit) {
        return new RoleManagementApplicationService(() -> new CurrentIdentity.Snapshot(actor, UUID.randomUUID(), 0), administration, roles, unit);
    }

    /**
     * 在新工作单元读取角色快照。
     * @param id 角色UUID
     * @return 角色聚合
     */
    private AuthorizationRole load(UUID id) { return work.execute(() -> roles.findById(id.toString()).orElseThrow()); }

    /**
     * 断言无敏感原因的统一错误。
     * @param code 预期错误
     * @param action 被拒绝操作
     */
    private static void error(CommonErrorCode code, Runnable action) {
        var failure = assertThrows(StratumException.class, action::run);
        assertEquals(code, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
