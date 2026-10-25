package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.trade.TradeOrderDetailQuery;
import com.patrick.stratum.query.trade.TradeOrderDetailQueryHandler;
import com.patrick.stratum.query.trade.TradeOrderView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 详情查询在同一头行快照内限定真实仓库，未经授权不返回任何订单或明细。 */
class TradeOrderDetailScopeIntegrationTest extends TradeOrderScopeIntegrationTest {
    /**
     * 两种订单未授权均404，指定范围完整返回原快照；ALL和撤回对同一Access立即生效。
     * @throws Exception HTTP失败
     */
    @Test
    void protectsBothKindsAndPreservesAuthorizedSnapshot() throws Exception {
        for (var kind : TradeOrder.Kind.values()) for (var order : fixtures.get(kind)) {
            var query = TradeOrderDetailQuery.create(kind, order.getId());
            assertEquals(0, queries.execute(query, context).getTotal());
            assertTrue(json.readTree(request("GET", path(kind) + "/" + order.getId(), token, null).body()).path("data").isNull());
            assertEquals(404, request("GET", path(kind) + "/" + order.getId(), token, null).statusCode());
        }
        configure(role, WarehouseScope.custom(Set.of(first)));
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getFirst();
            work.execute(() -> { order.fulfill(Map.of(order.getLines().getFirst().getId(), new Quantity(new BigDecimal("0.125")))); orders.save(order); return null; });
            var actual = queries.execute(TradeOrderDetailQuery.create(kind, order.getId()), context).getItems().getFirst();
            assertEquals(order.getId(), actual.order().id()); assertEquals(first, actual.order().warehouseId());
            assertEquals(1, actual.order().version()); assertEquals(TradeOrder.Status.PARTIAL, actual.order().status());
            assertEquals(order.getLines().getFirst().getId(), actual.lines().getFirst().id());
            assertEquals(0, new BigDecimal("0.125").compareTo(actual.lines().getFirst().fulfilled()));
            var http = data(request("GET", path(kind) + "/" + order.getId(), token, null), 200);
            assertEquals(1, http.path("order").path("version").asLong()); assertEquals(1, http.path("lines").size());
            var hidden = fixtures.get(kind).getLast();
            assertTrue(queries.execute(TradeOrderDetailQuery.create(kind, hidden.getId()), context).getItems().isEmpty());
            assertEquals(404, request("GET", path(kind) + "/" + hidden.getId(), token, null).statusCode());
        }
        configure(role, WarehouseScope.all());
        for (var kind : TradeOrder.Kind.values()) assertEquals(200, request("GET", path(kind) + "/" + fixtures.get(kind).getLast().getId(), token, null).statusCode());
        configure(role, WarehouseScope.custom(Set.of()));
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getFirst();
            assertEquals(404, request("GET", path(kind) + "/" + order.getId(), token, null).statusCode());
            assertEquals(200, request("GET", path(kind) + "/" + order.getId(), accessToken(), null).statusCode());
        }
    }

    /**
     * 多角色范围并集、成员撤回及停用实时可见，错误类别和未知目标仍为空。
     * @throws Exception HTTP失败
     */
    @Test
    void observesLiveUnionWithoutCrossKindOrUnknownRows() throws Exception {
        UUID other = createRole("detail-union-" + UUID.randomUUID(), "详情并集");
        configure(role, WarehouseScope.custom(Set.of(first))); configure(other, WarehouseScope.custom(Set.of(second)));
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role, other)); return null; });
        for (var kind : TradeOrder.Kind.values()) for (var order : fixtures.get(kind)) assertEquals(1, queries.execute(TradeOrderDetailQuery.create(kind, order.getId()), context).getTotal());
        var hidden = fixtures.get(TradeOrder.Kind.PURCHASE).getLast();
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        assertEquals(0, queries.execute(TradeOrderDetailQuery.create(hidden.getKind(), hidden.getId()), context).getTotal());
        assertEquals(0, queries.execute(TradeOrderDetailQuery.create(TradeOrder.Kind.SALE, hidden.getId()), context).getTotal());
        assertEquals(0, queries.execute(TradeOrderDetailQuery.create(TradeOrder.Kind.PURCHASE, UUID.randomUUID()), context).getTotal());
        var allowed = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst();
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        assertEquals(0, queries.execute(TradeOrderDetailQuery.create(allowed.getKind(), allowed.getId()), context).getTotal());
    }

    /** 匿名和畸形主体不能读详情；事务和ORM故障不能回退到未过滤头行关联。 */
    @Test
    void rejectsUntrustedContextAndFailsClosedOnStorage() {
        var query = TradeOrderDetailQuery.create(TradeOrder.Kind.PURCHASE, fixtures.get(TradeOrder.Kind.PURCHASE).getFirst().getId());
        error(CommonErrorCode.UNAUTHORIZED, () -> queries.execute(query, QueryContext.anonymous("detail-anonymous")));
        error(CommonErrorCode.FORBIDDEN, () -> queries.execute(query, new QueryContext.Snapshot("detail-invalid", Optional.of("administrator"), Set.of("all"))));
        assertEquals(0, queries.execute(query, new QueryContext.Snapshot("detail-missing", Optional.of(UUID.randomUUID().toString()), Set.of("all"))).getTotal());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> queries.execute(query, context)));
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(TradeOrderView.class)).thenThrow(new EasyQueryException("sensitive-order-detail"));
        error(CommonErrorCode.INTERNAL_ERROR, () -> new TradeOrderDetailQueryHandler(database).handle(query, context));
    }
}
