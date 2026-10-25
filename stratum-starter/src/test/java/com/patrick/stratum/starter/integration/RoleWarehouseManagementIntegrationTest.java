package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.RoleWarehouseManagementApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 真实库/Redis/HTTP夹具验证管理员整组范围配置、独立版本、即时生效及并发和回滚。 */
class RoleWarehouseManagementIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired private AccountWarehouseAccess warehouses;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    @Autowired private RoleWarehouseManagementApplicationService configured;
    private final List<UUID> ownedRoles = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /** 移除本测试范围关联再删除独占主数据，父夹具处理账号及角色。 */
    @AfterEach
    void cleanWarehouses() {
        for (UUID role : ownedRoles) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role.toString());
        for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
    }

    /**
     * 范围变化实时影响现有成员，但不撤销已有登录会话，也不改变角色API权限版本。
     * @throws Exception HTTP失败
     */
    @Test
    void reconfiguresLiveScopesWithoutRevokingSessionsOrChangingApiRoleVersion() throws Exception {
        UUID role = role(), allowed = master(MasterData.Kind.WAREHOUSE), excluded = master(MasterData.Kind.WAREHOUSE);
        String username = "scope-member-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password);
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        var session = login(username, password); var service = service(UUID.fromString(subjectId()), work);
        assertFalse(warehouses.permits(user, allowed, "warehouse-management"));
        assertEquals(1, service.configure(role, 0, DataScopeType.CUSTOM, Set.of(allowed)));
        assertTrue(warehouses.permits(user, allowed, "warehouse-management")); assertFalse(warehouses.permits(user, excluded, "warehouse-management"));
        data(request("GET", "/auth/me", session.accessToken(), null), 200);
        assertEquals(2, service.configure(role, 1, DataScopeType.ALL, Set.of()));
        assertTrue(warehouses.permits(user, excluded, "warehouse-management"));
        assertEquals(3, service.configure(role, 2, DataScopeType.CUSTOM, Set.of()));
        assertFalse(warehouses.permits(user, allowed, "warehouse-management")); data(request("GET", "/auth/me", session.accessToken(), null), 200);
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, role.toString()));
    }

    /**
     * 相同配置同版本不保存，旧版本即使提交相同配置也冲突；角色缺失404。
     * @throws Exception HTTP失败
     */
    @Test
    void handlesNoOpStaleVersionAndMissingRolePrecisely() throws Exception {
        UUID role = role(); var service = service(UUID.fromString(subjectId()), work);
        assertEquals(0, service.configure(role, 0, DataScopeType.CUSTOM, Set.of()));
        assertEquals(1, service.configure(role, 0, DataScopeType.ALL, Set.of()));
        assertEquals(1, service.configure(role, 1, DataScopeType.ALL, Set.of()));
        error(CommonErrorCode.CONFLICT, () -> service.configure(role, 0, DataScopeType.ALL, Set.of()));
        error(CommonErrorCode.NOT_FOUND, () -> service.configure(UUID.randomUUID(), 0, DataScopeType.CUSTOM, Set.of()));
        assertEquals(1L, load(role).getVersion());
    }

    /**
     * 只有当前启用管理员能配置，管理员自身ALL固定，真实Spring入口拒绝外层事务。
     * @throws Exception HTTP失败
     */
    @Test
    void protectsAdministratorScopeAndRequiresCurrentEnabledAdministrator() throws Exception {
        UUID role = role(); UUID ordinary = createAccount("scope-operator-" + UUID.randomUUID(), UUID.randomUUID().toString());
        error(CommonErrorCode.FORBIDDEN, () -> service(ordinary, work).configure(role, 0, DataScopeType.ALL, Set.of()));
        var anonymous = new RoleWarehouseManagementApplicationService(() -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); }, administration, scopes, work);
        error(CommonErrorCode.UNAUTHORIZED, () -> anonymous.configure(role, 0, DataScopeType.ALL, Set.of()));
        assertThrows(IllegalTransactionStateException.class, () -> work.execute(() -> configured.configure(role, 0, DataScopeType.ALL, Set.of())));
        var service = service(UUID.fromString(subjectId()), work);
        UUID administrator = UUID.fromString(jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class));
        long version = load(administrator).getVersion();
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(administrator, version, DataScopeType.ALL, Set.of()));
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(administrator, version, DataScopeType.CUSTOM, Set.of()));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", subjectId());
        try { error(CommonErrorCode.FORBIDDEN, () -> service.configure(role, 0, DataScopeType.ALL, Set.of())); }
        finally { jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", subjectId()); }
        assertEquals(WarehouseScope.all(), load(administrator).getScope()); assertEquals(0L, load(role).getVersion());
    }

    /**
     * 输入越界、类型矛盾及无效仓库引用全部拒绝，已保存的范围与版本保持完整。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInvalidConfigurationAndRollsBackReferenceConflicts() throws Exception {
        UUID role = role(), warehouse = master(MasterData.Kind.WAREHOUSE), material = master(MasterData.Kind.MATERIAL);
        var service = service(UUID.fromString(subjectId()), work);
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, -1, DataScopeType.CUSTOM, Set.of()));
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, 0, DataScopeType.ORG, Set.of()));
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, 0, DataScopeType.ALL, Set.of(warehouse)));
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, 0, null, Set.of()));
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, 0, DataScopeType.CUSTOM, null));
        var invalid = new HashSet<UUID>(); invalid.add(null);
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, 0, DataScopeType.CUSTOM, invalid));
        var oversized = IntStream.range(0, WarehouseScope.MAX_WAREHOUSES + 1).mapToObj(index -> UUID.randomUUID()).collect(Collectors.toSet());
        error(CommonErrorCode.BAD_REQUEST, () -> service.configure(role, 0, DataScopeType.CUSTOM, oversized));
        assertEquals(1, service.configure(role, 0, DataScopeType.CUSTOM, Set.of(warehouse)));
        for (UUID bad : Set.of(UUID.randomUUID(), material)) {
            error(CommonErrorCode.CONFLICT, () -> service.configure(role, 1, DataScopeType.CUSTOM, Set.of(bad)));
            assertEquals(1L, load(role).getVersion()); assertEquals(Set.of(warehouse), load(role).getScope().warehouseIds());
        }
    }

    /**
     * 提交前回调故障回滚范围和独立版本，不返回成功或留下部分授权。
     * @throws Exception HTTP失败
     */
    @Test
    void rollsBackConfigurationWhenCommitFails() throws Exception {
        UUID role = role(), warehouse = master(MasterData.Kind.WAREHOUSE);
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
        var service = service(UUID.fromString(subjectId()), failing);
        assertThrows(IllegalStateException.class, () -> service.configure(role, 0, DataScopeType.CUSTOM, Set.of(warehouse)));
        assertEquals(0L, load(role).getVersion()); assertTrue(load(role).getScope().warehouseIds().isEmpty());
    }

    /**
     * 两个当前管理员请求使用同一观察版本时，仅一份完整配置能够提交。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void serializesConcurrentAdministratorReconfiguration() throws Exception {
        UUID role = role(), first = master(MasterData.Kind.WAREHOUSE), second = master(MasterData.Kind.WAREHOUSE);
        var service = service(UUID.fromString(subjectId()), work);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(executor.submit(() -> outcome(service, role, first)), executor.submit(() -> outcome(service, role, second)));
            var results = new ArrayList<Integer>(); for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(status -> status == 200).count());
            assertEquals(1, results.stream().filter(status -> status == 409).count());
        }
        var current = load(role); assertEquals(1L, current.getVersion());
        assertTrue(Set.of(Set.of(first), Set.of(second)).contains(current.getScope().warehouseIds()));
    }

    /**
     * 新建本测试普通角色及初始空范围。
     * @return 角色UUID
     * @throws Exception HTTP失败
     */
    private UUID role() throws Exception {
        UUID role = createRole("scope-management-" + UUID.randomUUID(), "范围管理"); ownedRoles.add(role); return role;
    }

    /**
     * 保存测试仓库或错误类别物料。
     * @param kind 主数据类别
     * @return 主数据UUID
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "范围管理", kind == MasterData.Kind.MATERIAL ? "件" : null); masterIds.add(value.getId());
        work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 以显式可信测试主体构造应用，生产仍从安全身份适配器取得操作者。
     * @param actor 当前操作者
     * @param unitOfWork 被测工作单元
     * @return 管理应用
     */
    private RoleWarehouseManagementApplicationService service(UUID actor, UnitOfWork unitOfWork) {
        CurrentIdentity identity = () -> new CurrentIdentity.Snapshot(actor, UUID.randomUUID(), 0);
        return new RoleWarehouseManagementApplicationService(identity, administration, scopes, unitOfWork);
    }

    /**
     * 从新工作单元读取已提交配置。
     * @param role 角色UUID
     * @return 当前配置
     */
    private RoleWarehouseScope load(UUID role) { return work.execute(() -> { administration.lock(); return scopes.findByRoleId(role.toString()).orElseThrow(); }); }

    /**
     * 获取并发请求状态，非冲突异常不得吞掉。
     * @param service 管理应用
     * @param role 目标角色
     * @param warehouse 新配置仓库
     * @return 200或409
     */
    private int outcome(RoleWarehouseManagementApplicationService service, UUID role, UUID warehouse) {
        try { service.configure(role, 0, DataScopeType.CUSTOM, Set.of(warehouse)); return 200; }
        catch (StratumException failure) { assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 409; }
    }

    /**
     * 核对脱敏错误码，避免输出底层连接信息。
     * @param expected 预期错误
     * @param action 被测操作
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
