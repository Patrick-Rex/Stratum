package com.patrick.stratum.starter.integration;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.inventory.StockBalanceQuery;
import com.patrick.stratum.query.inventory.StockBalanceQueryHandler;
import com.patrick.stratum.query.inventory.StockBalanceView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实多仓库余额验证数据库范围过滤先于计数分页，配置即时生效且其他管理员不能扩大范围。 */
class StockBalanceScopeIntegrationTest extends InventoryScopeIntegrationTest {
    /**
     * 空范围不被另一个管理员放大，指定范围与客户端条件相交，ALL和撤回对同一会话立即可见。
     * @throws Exception HTTP失败
     */
    @Test
    void filtersCountsPagesAndTargetsBeforeReturningRows() throws Exception {
        assertEquals(0, readBalances(1, 1, List.of()).getTotal());
        configure(role, WarehouseScope.custom(Set.of(first)));
        var allowed = readBalances(1, 1, List.of()); assertEquals(1, allowed.getTotal()); assertEquals(first, allowed.getItems().getFirst().warehouseId());
        assertTrue(readBalances(2, 1, List.of()).getItems().isEmpty());
        assertEquals(0, readBalances(1, 20, List.of(eq("warehouseId", second))).getTotal());
        assertEquals(0, readBalances(1, 20, List.of(eq("id", balanceIds.get(1)))).getTotal());
        String path = "/api/erp/stock-balances?materialId=" + material;
        assertEquals(1, data(request("GET", path, token, null), 200).path("total").asLong());
        configure(role, WarehouseScope.all());
        assertEquals(2, readBalances(1, 1, List.of()).getTotal()); assertEquals(1, readBalances(2, 1, List.of()).getItems().size());
        assertNotEquals(readBalances(1, 1, List.of()).getItems().getFirst().id(), readBalances(2, 1, List.of()).getItems().getFirst().id());
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
        assertEquals(2, readBalances(1, 20, List.of()).getTotal());
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        assertEquals(1, readBalances(1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString()); assertEquals(0, readBalances(1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString()); assertEquals(1, readBalances(1, 20, List.of()).getTotal());
        jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role.toString()); assertEquals(0, readBalances(1, 20, List.of()).getTotal());
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

}
