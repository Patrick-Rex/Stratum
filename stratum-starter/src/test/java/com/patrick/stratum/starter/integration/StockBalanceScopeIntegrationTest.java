package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.domain.auth.RoleWarehouseScopeRepository;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.inventory.StockBalanceQuery;
import com.patrick.stratum.query.inventory.StockBalanceQueryHandler;
import com.patrick.stratum.query.inventory.StockBalanceView;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实多仓库余额验证数据库范围过滤先于计数分页，配置即时生效且其他管理员不能扩大范围。 */
class StockBalanceScopeIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private QueryGateway queries;
    @Autowired private MasterDataRepository masters;
    @Autowired private StockBalanceRepository balances;
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private UnitOfWork work;
    private final List<String> masterIds = new ArrayList<>();
    private final List<UUID> balanceIds = new ArrayList<>();
    private final Set<UUID> configuredRoles = new HashSet<>();
    private UUID user;
    private UUID role;
    private UUID material;
    private UUID first;
    private UUID second;
    private QueryContext context;
    private String token;

    /**
     * 准备两个同物料仓库余额和默认空范围普通角色；父夹具另有真实管理员。
     * @throws Exception HTTP失败
     */
    @BeforeEach
    void prepare() throws Exception {
        material = master(MasterData.Kind.MATERIAL); first = master(MasterData.Kind.WAREHOUSE); second = master(MasterData.Kind.WAREHOUSE);
        balance(material, first); balance(material, second);
        String username = "balance-scope-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        user = createAccount(username, password); role = createRole("balance-scope-" + UUID.randomUUID(), "余额范围");
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 0, "name", "余额范围",
                "permissions", Set.of(PermissionCatalog.INVENTORY_READ.permission()))), 200);
        token = login(username, password).accessToken();
        context = new QueryContext.Snapshot("balance-scope", Optional.of(user.toString()), Set.of("erp.inventory.read"));
    }

    /** 先解除本用例角色仓库引用，再清理余额和主数据；父夹具随后清理自身账号角色。 */
    @AfterEach
    void cleanBusiness() {
        if (role != null) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role.toString());
        for (UUID id : configuredRoles) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", id.toString());
        for (UUID id : balanceIds) jdbc.update("DELETE FROM erp_stock_balance WHERE id=?", id.toString());
        for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
    }

    /**
     * 空范围不被另一个管理员放大，指定范围与客户端条件相交，ALL和撤回对同一会话立即可见。
     * @throws Exception HTTP失败
     */
    @Test
    void filtersCountsPagesAndTargetsBeforeReturningRows() throws Exception {
        assertEquals(0, read(1, 1, List.of()).getTotal());
        configure(role, WarehouseScope.custom(Set.of(first)));
        var allowed = read(1, 1, List.of()); assertEquals(1, allowed.getTotal()); assertEquals(first, allowed.getItems().getFirst().warehouseId());
        assertTrue(read(2, 1, List.of()).getItems().isEmpty());
        assertEquals(0, read(1, 20, List.of(eq("warehouseId", second))).getTotal());
        assertEquals(0, read(1, 20, List.of(eq("id", balanceIds.get(1)))).getTotal());
        String path = "/api/erp/stock-balances?materialId=" + material;
        assertEquals(1, data(request("GET", path, token, null), 200).path("total").asLong());
        configure(role, WarehouseScope.all());
        assertEquals(2, read(1, 1, List.of()).getTotal()); assertEquals(1, read(2, 1, List.of()).getItems().size());
        assertNotEquals(read(1, 1, List.of()).getItems().getFirst().id(), read(2, 1, List.of()).getItems().getFirst().id());
        assertEquals(2, data(request("GET", path, token, null), 200).path("total").asLong());
        configure(role, WarehouseScope.custom(Set.of()));
        var denied = data(request("GET", path, token, null), 200);
        assertEquals(0, denied.path("total").asLong()); assertTrue(denied.path("items").isEmpty());
        assertEquals(2, data(request("GET", path, accessToken(), null), 200).path("total").asLong());
    }

    /**
     * 多角色取并集，重叠授权不重复计数，移除成员和停用账号不能沿用范围。
     * @throws Exception HTTP失败
     */
    @Test
    void unionsRolesWithoutDuplicatingBalancesAndObservesAccountChanges() throws Exception {
        UUID other = createRole("balance-union-" + UUID.randomUUID(), "余额并集");
        configure(role, WarehouseScope.custom(Set.of(first))); configure(other, WarehouseScope.custom(Set.of(first, second)));
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role, other)); return null; });
        assertEquals(2, read(1, 20, List.of()).getTotal());
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        assertEquals(1, read(1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString()); assertEquals(0, read(1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString()); assertEquals(1, read(1, 20, List.of()).getTotal());
        jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role.toString()); assertEquals(0, read(1, 20, List.of()).getTotal());
    }

    /** 匿名和非规范主体直接拒绝，自报权限不能授权，未知账号空结果，写事务内查询继续拒绝。 */
    @Test
    void rejectsUntrustedContextAndTransactionalReads() {
        var query = StockBalanceQuery.create(1, 20, List.of(), List.of(eq("materialId", material)));
        error(CommonErrorCode.UNAUTHORIZED, () -> queries.execute(query, QueryContext.anonymous("balance-anonymous")));
        for (String actor : List.of("administrator", "' OR 1=1", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {
            error(CommonErrorCode.FORBIDDEN, () -> queries.execute(query, new QueryContext.Snapshot("balance-invalid", Optional.of(actor), Set.of("all"))));
        }
        assertEquals(0, queries.execute(query, new QueryContext.Snapshot("balance-missing", Optional.of(UUID.randomUUID().toString()), Set.of("all"))).getTotal());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> queries.execute(query, context)));
    }

    /** ORM故障保留失败并脱敏，不能回退至无范围查询。 */
    @Test
    void failsClosedOnDatabaseErrors() {
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(StockBalanceView.class)).thenThrow(new EasyQueryException("sensitive-balance-scope"));
        var handler = new StockBalanceQueryHandler(database);
        error(CommonErrorCode.INTERNAL_ERROR, () -> handler.handle(StockBalanceQuery.create(1, 20, List.of(), List.of()), context));
    }

    /**
     * 保存精确范围配置，记录独立版本及待清理角色。
     * @param target 角色
     * @param scope 完整范围
     */
    private void configure(UUID target, WarehouseScope scope) {
        work.execute(() -> {
            administration.lock(); var current = scopes.findByRoleId(target.toString()).orElseThrow();
            current.replace(scope); scopes.save(current); configuredRoles.add(target); return null;
        });
    }

    /**
     * 按同一物料限制本用例数据，附加条件仍须与强制范围相交。
     * @param page 页码
     * @param size 页大小
     * @param extra 额外过滤
     * @return 有界余额页
     */
    private QueryResult<StockBalanceQuery.Row> read(int page, int size, List<QuerySpec.FilterInput> extra) {
        var filters = new ArrayList<>(extra); filters.add(eq("materialId", material));
        return queries.execute(StockBalanceQuery.create(page, size, List.of("quantity,asc"), filters), context);
    }

    /**
     * 创建本用例主数据并登记清理范围。
     * @param kind 主数据类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "余额范围", kind == MasterData.Kind.MATERIAL ? "件" : null);
        masterIds.add(value.getId()); work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 创建独立仓库余额。
     * @param item 物料
     * @param warehouse 仓库
     */
    private void balance(UUID item, UUID warehouse) {
        var value = StockBalance.open(item, warehouse); value.receive(new Quantity(BigDecimal.ONE)); balanceIds.add(value.getId());
        work.execute(() -> { balances.save(value); return null; });
    }

    /**
     * 构造白名单UUID精确条件。
     * @param field 字段
     * @param value 标识
     * @return 条件
     */
    private QuerySpec.FilterInput eq(String field, UUID value) { return new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null); }

    /**
     * 验证脱敏业务错误。
     * @param expected 预期错误
     * @param action 触发操作
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
