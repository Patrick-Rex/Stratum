package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 余额与流水范围验收共用真实账号、双仓库余额及独立配置夹具。 */
abstract class InventoryScopeIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired protected QueryGateway queries;
    @Autowired private MasterDataRepository masters;
    @Autowired private StockBalanceRepository balances;
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired protected AuthorizationAdministrationRepository administration;
    @Autowired protected UnitOfWork work;
    private final List<String> masterIds = new ArrayList<>();
    protected final List<UUID> balanceIds = new ArrayList<>();
    private final Set<UUID> configuredRoles = new HashSet<>();
    protected UUID user;
    protected UUID role;
    protected UUID material;
    protected UUID first;
    protected UUID second;
    protected QueryContext context;
    protected String token;

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
     * 保存精确范围配置并登记待清理角色。
     * @param target 角色
     * @param scope 完整范围
     */
    protected void configure(UUID target, WarehouseScope scope) {
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
    protected QueryResult<StockBalanceQuery.Row> readBalances(int page, int size, List<QuerySpec.FilterInput> extra) {
        var filters = new ArrayList<>(extra); filters.add(eq("materialId", material));
        return queries.execute(StockBalanceQuery.create(page, size, List.of("quantity,asc"), filters), context);
    }

    /**
     * 创建本用例主数据并登记清理范围。
     * @param kind 主数据类别
     * @return 标识
     */
    protected UUID master(MasterData.Kind kind) {
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
    protected QuerySpec.FilterInput eq(String field, UUID value) { return new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null); }

    /**
     * 验证脱敏业务错误。
     * @param expected 预期错误
     * @param action 触发操作
     */
    protected void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
