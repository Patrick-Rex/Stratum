package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.auth.CurrentAuthorizationApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.CurrentWarehouseAuthorizationApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.*;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 当前仓库检查通过真实账号/角色配置、生产查询适配和显式可信身份夹具验证，HTTP绑定待后续节点。 */
class CurrentWarehouseAuthorizationIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private AccountPermissions permissions;
    @Autowired private AccountWarehouseAccess warehouses;
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    @Autowired private CurrentWarehouseAuthorizationApplicationService configured;
    private final List<String> masterIds = new ArrayList<>();
    private UUID role;

    /** 先移除本测试范围引用再删除自身仓库，父夹具继续清理测试账号和角色。 */
    @AfterEach
    void cleanWarehouses() {
        if (role != null) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role.toString());
        for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
    }

    /**
     * 当前账号默认无范围，配置指定/全部/撤回后同一可信身份立即观察变化。
     * @throws Exception HTTP失败
     */
    @Test
    void usesCurrentIdentityAndObservesLiveWarehouseChanges() throws Exception {
        UUID user = prepareAccount(), allowed = warehouse(), excluded = warehouse(); var service = service(user);
        error(CommonErrorCode.FORBIDDEN, () -> service.require(allowed));
        configure(WarehouseScope.custom(Set.of(allowed))); assertDoesNotThrow(() -> service.require(allowed));
        error(CommonErrorCode.FORBIDDEN, () -> service.require(excluded));
        configure(WarehouseScope.all()); assertDoesNotThrow(() -> service.require(excluded));
        configure(WarehouseScope.custom(Set.of())); error(CommonErrorCode.FORBIDDEN, () -> service.require(allowed));
        configure(WarehouseScope.all());
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 1, "roleIds", Set.of())), 200);
        error(CommonErrorCode.FORBIDDEN, () -> service.require(allowed));
    }

    /**
     * 身份失效与范围拒绝分别返回401/403，查询适配与当前应用均拒绝加入写事务。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInactiveIdentityAndTransactionalQueries() throws Exception {
        UUID user = prepareAccount(), target = warehouse(); configure(WarehouseScope.all()); var service = service(user);
        assertTrue(warehouses.permits(user, target, "current-warehouse"));
        assertThrows(IllegalStateException.class, () -> work.execute(() -> warehouses.permits(user, target, "current-warehouse")));
        assertThrows(IllegalStateException.class, () -> work.execute(() -> { service.require(target); return null; }));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        assertFalse(warehouses.permits(user, target, "current-warehouse")); error(CommonErrorCode.UNAUTHORIZED, () -> service.require(target));
        error(CommonErrorCode.UNAUTHORIZED, () -> service(UUID.randomUUID()).require(target));
        error(CommonErrorCode.UNAUTHORIZED, () -> configured.require(target));
        assertThrows(NullPointerException.class, () -> service.require(null));
    }

    /** 匿名和状态读取故障在范围读取前拒绝，范围故障保持500，可信主体与trace原样传递。 */
    @Test
    void failsClosedAndPassesOnlyTrustedSubjectAndTrace() {
        UUID user = UUID.randomUUID(), target = UUID.randomUUID(); var calls = new AtomicInteger();
        var current = new CurrentAuthorizationApplicationService(identity(user),
                (id, trace) -> Optional.of(new AccountPermissions.Snapshot(user, true, Set.of())));
        AccountWarehouseAccess probe = (id, warehouse, trace) -> {
            assertEquals(user, id); assertEquals(target, warehouse); assertEquals("current-warehouse", trace); calls.incrementAndGet(); return false;
        };
        var previous = TraceIdContext.getTraceId(); TraceIdContext.setTraceId("current-warehouse");
        try {
            var denied = new CurrentWarehouseAuthorizationApplicationService(current, probe);
            error(CommonErrorCode.FORBIDDEN, () -> denied.require(target));
            error(CommonErrorCode.FORBIDDEN, () -> denied.require(target)); assertEquals(2, calls.get());
            var anonymous = new CurrentAuthorizationApplicationService(() -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); }, permissions);
            error(CommonErrorCode.UNAUTHORIZED, () -> new CurrentWarehouseAuthorizationApplicationService(anonymous, probe).require(target));
            var stateFailure = new CurrentAuthorizationApplicationService(identity(user),
                    (id, trace) -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); });
            error(CommonErrorCode.INTERNAL_ERROR, () -> new CurrentWarehouseAuthorizationApplicationService(stateFailure, probe).require(target));
            assertEquals(2, calls.get());
            var unavailable = new CurrentWarehouseAuthorizationApplicationService(current,
                    (id, warehouse, trace) -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); });
            error(CommonErrorCode.INTERNAL_ERROR, () -> unavailable.require(target));
            assertEquals("current-warehouse", TraceIdContext.getTraceId().orElseThrow());
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow()); else TraceIdContext.clear();
        }
    }

    /**
     * 通过管理HTTP创建普通账号并配置空角色，沿用父夹具清理。
     * @return 当前账号UUID
     * @throws Exception HTTP失败
     */
    private UUID prepareAccount() throws Exception {
        UUID user = createAccount("warehouse-app-" + UUID.randomUUID(), UUID.randomUUID().toString());
        role = createRole("warehouse-app-" + UUID.randomUUID(), "仓库应用检查");
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        return user;
    }

    /**
     * 保存本用例独占仓库。
     * @return 仓库UUID
     */
    private UUID warehouse() {
        var value = MasterData.create(MasterData.Kind.WAREHOUSE, UUID.randomUUID().toString(), "仓库应用", null); masterIds.add(value.getId());
        work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 使用生产写仓储更改本测试角色范围。
     * @param scope 新范围
     */
    private void configure(WarehouseScope scope) {
        work.execute(() -> { administration.lock(); var configuration = scopes.findByRoleId(role.toString()).orElseThrow();
            configuration.replace(scope); scopes.save(configuration); return null; });
    }

    /**
     * 使用可信测试身份和生产查询端口构造当前范围应用。
     * @param user 测试当前账号UUID
     * @return 当前仓库检查入口
     */
    private CurrentWarehouseAuthorizationApplicationService service(UUID user) {
        return new CurrentWarehouseAuthorizationApplicationService(new CurrentAuthorizationApplicationService(identity(user), permissions), warehouses);
    }

    /**
     * 构造服务端身份端口的明确测试替身。
     * @param user 当前账号UUID
     * @return 不读取HTTP自报字段的身份端口
     */
    private CurrentIdentity identity(UUID user) { return () -> new CurrentIdentity.Snapshot(user, UUID.randomUUID(), 0); }

    /**
     * 检查安全错误且不泄露底层原因。
     * @param expected 预期错误码
     * @param action 被测调用
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
