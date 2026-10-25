package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import com.patrick.stratum.query.trade.TradeOrderQuery;
import com.patrick.stratum.query.trade.TradeOrderQueryHandler;
import com.patrick.stratum.query.trade.TradeOrderView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 采购销售摘要在数据库计数和分页前执行仓库范围，与客户端所有过滤相交。 */
class TradeOrderSummaryScopeIntegrationTest extends TradeOrderScopeIntegrationTest {
    /**
     * 双类型订单默认空，指定范围限定总数和分页；目标ID、单号、主体和仓库不能扩大范围。
     * @throws Exception HTTP失败
     */
    @Test
    void filtersBothKindsBeforeCountsPagesAndClientTargets() throws Exception {
        for (var kind : TradeOrder.Kind.values()) assertEquals(0, read(kind, 1, 1, List.of()).getTotal());
        configure(role, WarehouseScope.custom(Set.of(first)));
        for (var kind : TradeOrder.Kind.values()) {
            var hidden = fixtures.get(kind).getLast();
            var firstPage = read(kind, 1, 1, List.of()); var secondPage = read(kind, 2, 1, List.of());
            assertEquals(2, firstPage.getTotal()); assertEquals(2, secondPage.getTotal());
            assertEquals(first, firstPage.getItems().getFirst().warehouseId()); assertEquals(first, secondPage.getItems().getFirst().warehouseId());
            assertNotEquals(firstPage.getItems().getFirst().id(), secondPage.getItems().getFirst().id());
            assertTrue(read(kind, 3, 1, List.of()).getItems().isEmpty());
            for (var filter : List.of(eq("id", hidden.getId()), eq("orderNumber", hidden.getOrderNumber()), eq("warehouseId", second))) {
                assertEquals(0, read(kind, 1, 20, List.of(filter)).getTotal());
            }
            String url = path(kind) + "?partyId=" + hidden.getPartyId();
            assertEquals(2, data(request("GET", url, token, null), 200).path("total").asLong());
        }
        configure(role, WarehouseScope.all());
        for (var kind : TradeOrder.Kind.values()) assertEquals(3, read(kind, 1, 20, List.of()).getTotal());
        configure(role, WarehouseScope.custom(Set.of()));
        for (var kind : TradeOrder.Kind.values()) {
            String url = path(kind) + "?partyId=" + fixtures.get(kind).getFirst().getPartyId();
            assertEquals(0, data(request("GET", url, token, null), 200).path("total").asLong());
            assertEquals(3, data(request("GET", url, accessToken(), null), 200).path("total").asLong());
        }
    }

    /**
     * 多角色重叠不重复订单，成员撤回、停用和缺失范围立即改变结果。
     * @throws Exception HTTP失败
     */
    @Test
    void appliesLiveUnionAndEnabledMembership() throws Exception {
        UUID other = createRole("order-union-" + UUID.randomUUID(), "订单并集");
        configure(role, WarehouseScope.custom(Set.of(first))); configure(other, WarehouseScope.custom(Set.of(first, second)));
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role, other)); return null; });
        for (var kind : TradeOrder.Kind.values()) assertEquals(3, read(kind, 1, 20, List.of()).getTotal());
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        for (var kind : TradeOrder.Kind.values()) assertEquals(2, read(kind, 1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        for (var kind : TradeOrder.Kind.values()) assertEquals(0, read(kind, 1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString());
        jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role.toString());
        for (var kind : TradeOrder.Kind.values()) assertEquals(0, read(kind, 1, 20, List.of()).getTotal());
    }

    /** 匿名、畸形和未知主体无法读取；事务及ORM故障保持失败关闭。 */
    @Test
    void rejectsInvalidContextsTransactionsAndDatabaseFailures() {
        var query = TradeOrderQuery.create(TradeOrder.Kind.PURCHASE, 1, 20, List.of(), List.of());
        error(CommonErrorCode.UNAUTHORIZED, () -> queries.execute(query, QueryContext.anonymous("order-anonymous")));
        error(CommonErrorCode.FORBIDDEN, () -> queries.execute(query, new QueryContext.Snapshot("order-invalid", Optional.of("1-1-1-1-1"), Set.of("all"))));
        assertEquals(0, queries.execute(query, new QueryContext.Snapshot("order-missing", Optional.of(UUID.randomUUID().toString()), Set.of("all"))).getTotal());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> queries.execute(query, context)));
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(TradeOrderView.class)).thenThrow(new EasyQueryException("sensitive-order-scope"));
        error(CommonErrorCode.INTERNAL_ERROR, () -> new TradeOrderQueryHandler(database).handle(query, context));
    }

    /**
     * 按本用例独立交易方隔离数据，再与附加条件和强制仓库范围相交。
     * @param kind 订单类别
     * @param page 页码
     * @param size 页大小
     * @param extra 额外过滤
     * @return 摘要页
     */
    private QueryResult<TradeOrderQuery.Row> read(TradeOrder.Kind kind, int page, int size, List<QuerySpec.FilterInput> extra) {
        var filters = new ArrayList<>(extra); filters.add(eq("partyId", fixtures.get(kind).getFirst().getPartyId()));
        return queries.execute(TradeOrderQuery.create(kind, page, size, List.of("totalAmount,asc"), filters), context);
    }
}
