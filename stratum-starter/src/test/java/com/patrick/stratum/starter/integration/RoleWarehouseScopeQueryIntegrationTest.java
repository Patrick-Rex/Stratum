package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AdministrationQueryAccess;
import com.patrick.stratum.query.auth.RoleView;
import com.patrick.stratum.query.auth.RoleWarehouseScopeQuery;
import com.patrick.stratum.query.auth.RoleWarehouseScopeQueryHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 管理范围详情通过真实角色与数据库验证独立版本、完整集合、实时资格和失败关闭。 */
class RoleWarehouseScopeQueryIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private AdministrationQueryAccess access;
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    private final List<UUID> ownedRoles = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /** 清理自身范围引用再删除测试仓库，父夹具删除自己的角色和账号。 */
    @AfterEach
    void cleanWarehouses() {
        for (UUID role : ownedRoles) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role.toString());
        if (!masterIds.isEmpty()) jdbc.batchUpdate("DELETE FROM erp_master_data WHERE id=?", masterIds.stream().map(id -> new Object[]{id}).toList());
    }

    /**
     * 详情区分空配置与未知角色，独立版本不随API权限版本变化，快照集合不可变。
     * @throws Exception HTTP失败
     */
    @Test
    void readsCompleteIndependentConfigurationAndDistinguishesMissingRole() throws Exception {
        UUID role = role(), warehouse = warehouse(); var initial = read(role);
        assertEquals(0L, initial.version()); assertEquals(DataScopeType.CUSTOM, initial.scopeType()); assertTrue(initial.warehouseIds().isEmpty());
        assertTrue(gateway.execute(RoleWarehouseScopeQuery.create(UUID.randomUUID()), context()).getItems().isEmpty());
        configure(role, WarehouseScope.all()); var all = read(role); assertEquals(1L, all.version()); assertEquals(DataScopeType.ALL, all.scopeType());
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 0, "name", "只改名称与权限", "permissions", Set.of())), 200);
        assertEquals(1L, read(role).version());
        configure(role, WarehouseScope.custom(Set.of(warehouse))); var selected = read(role);
        assertEquals(2L, selected.version()); assertEquals(Set.of(warehouse), selected.warehouseIds());
        configure(role, WarehouseScope.custom(Set.of())); assertTrue(read(role).warehouseIds().isEmpty());
        assertEquals(Set.of(warehouse), selected.warehouseIds());
        assertThrows(UnsupportedOperationException.class, () -> selected.warehouseIds().clear());
        var source = new HashSet<>(Set.of(warehouse)); var copy = new RoleWarehouseScopeQuery.Row(role, 0, DataScopeType.CUSTOM, source);
        source.clear(); assertEquals(Set.of(warehouse), copy.warehouseIds());
        UUID administrator = UUID.fromString(jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class));
        assertEquals(DataScopeType.ALL, read(administrator).scopeType()); assertTrue(read(administrator).warehouseIds().isEmpty());
    }

    /**
     * 真实启用管理员角色是必要条件，匿名、普通、自报权限、已移除和停用管理员不能读取。
     * @throws Exception HTTP失败
     */
    @Test
    void requiresCurrentEnabledAdministratorWithoutTrustingPermissionStrings() throws Exception {
        UUID role = role(); var query = RoleWarehouseScopeQuery.create(role);
        error(CommonErrorCode.UNAUTHORIZED, () -> gateway.execute(query, QueryContext.anonymous("role-warehouse-query")));
        UUID ordinary = createAccount("scope-reader-" + UUID.randomUUID(), UUID.randomUUID().toString());
        var forged = new QueryContext.Snapshot("role-warehouse-query", Optional.of(ordinary.toString()), Set.of("auth.role.manage", "auth.administration"));
        error(CommonErrorCode.FORBIDDEN, () -> gateway.execute(query, forged));
        String administrator = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=? AND role_id=?", subjectId(), administrator);
        try { error(CommonErrorCode.FORBIDDEN, () -> gateway.execute(query, context())); }
        finally { jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", subjectId(), administrator); }
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", subjectId());
        try { error(CommonErrorCode.FORBIDDEN, () -> gateway.execute(query, context())); }
        finally { jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", subjectId()); }
        assertEquals(role, read(role).id());
    }

    /**
     * 单配置1024集合完整返回，历史超界数据必须失败而不能静默返回前1024项。
     * @throws Exception HTTP失败
     */
    @Test
    void returnsExactMaximumAndRejectsOversizedStoredConfiguration() throws Exception {
        UUID role = role(); var ids = IntStream.range(0, WarehouseScope.MAX_WAREHOUSES + 1).mapToObj(index -> UUID.randomUUID()).toList();
        masterIds.addAll(ids.stream().map(UUID::toString).toList());
        work.execute(() -> {
            jdbc.batchUpdate("INSERT INTO erp_master_data(id,kind,code,name) VALUES(?,'WAREHOUSE',?,'范围详情上界')",
                    ids.stream().map(id -> new Object[]{id.toString(), id.toString()}).toList()); return null;
        });
        var maximum = ids.subList(0, WarehouseScope.MAX_WAREHOUSES).stream().collect(Collectors.toSet());
        configure(role, WarehouseScope.custom(maximum)); assertEquals(maximum, read(role).warehouseIds());
        jdbc.update("INSERT INTO auth_role_warehouse(role_id,warehouse_id) VALUES(?,?)", role.toString(), ids.getLast().toString());
        error(CommonErrorCode.INTERNAL_ERROR, () -> read(role));
    }

    /**
     * 事务内读取、缺失配置和存储故障不返回成功详情，异常不泄露SQL原因。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsTransactionsAndFailsClosedOnMissingConfigurationOrStorageFault() throws Exception {
        UUID role = role(); var query = RoleWarehouseScopeQuery.create(role);
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(query, context())));
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(RoleView.class)).thenThrow(new EasyQueryException("sensitive-role-scope"));
        var handler = new RoleWarehouseScopeQueryHandler(database, access);
        error(CommonErrorCode.INTERNAL_ERROR, () -> handler.handle(query, context()));
        jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role.toString());
        error(CommonErrorCode.INTERNAL_ERROR, () -> read(role));
        assertThrows(NullPointerException.class, () -> RoleWarehouseScopeQuery.create(null));
        assertThrows(IllegalArgumentException.class, () -> new RoleWarehouseScopeQuery.Row(role, -1, DataScopeType.CUSTOM, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new RoleWarehouseScopeQuery.Row(role, 0, DataScopeType.ORG, Set.of()));
    }

    /**
     * 创建本测试空配置角色。
     * @return 角色UUID
     * @throws Exception HTTP失败
     */
    private UUID role() throws Exception {
        UUID role = createRole("scope-query-" + UUID.randomUUID(), "范围详情"); ownedRoles.add(role); return role;
    }

    /**
     * 保存本测试独占仓库。
     * @return 仓库UUID
     */
    private UUID warehouse() {
        var value = MasterData.create(MasterData.Kind.WAREHOUSE, UUID.randomUUID().toString(), "范围详情", null); masterIds.add(value.getId());
        work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 在管理协调锁下整组替换测试角色范围。
     * @param role 角色UUID
     * @param scope 新配置
     */
    private void configure(UUID role, WarehouseScope scope) {
        work.execute(() -> { administration.lock(); var configuration = scopes.findByRoleId(role.toString()).orElseThrow();
            configuration.replace(scope); scopes.save(configuration); return null; });
    }

    /**
     * 通过正式网关返回一条完整角色配置。
     * @param role 角色UUID
     * @return 有界不可变配置
     */
    private RoleWarehouseScopeQuery.Row read(UUID role) {
        var result = gateway.execute(RoleWarehouseScopeQuery.create(role), context()); assertEquals(1, result.getTotal()); return result.getItems().getFirst();
    }

    /**
     * 当前初始化管理员的可信查询上下文，不携带自报权限。
     * @return 服务端测试上下文
     */
    private QueryContext context() { return new QueryContext.Snapshot("role-warehouse-query", Optional.of(subjectId()), Set.of()); }

    /**
     * 核对安全错误码与无底层原因。
     * @param expected 预期错误
     * @param action 被测操作
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
