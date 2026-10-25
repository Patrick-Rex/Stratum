package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.trade.Fulfillment;
import com.patrick.stratum.domain.trade.FulfillmentRepository;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.trade.FulfillmentLineView;
import com.patrick.stratum.query.trade.FulfillmentQuery;
import com.patrick.stratum.query.trade.FulfillmentQueryHandler;
import com.patrick.stratum.query.trade.FulfillmentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 履约历史由真实订单仓库限定，验证头分页、批量明细和所有目标过滤均不能越权。 */
class FulfillmentScopeIntegrationTest extends TradeOrderScopeIntegrationTest {
    @Autowired private FulfillmentRepository postings;
    @Autowired private EasyEntityQuery database;
    private final Map<TradeOrder.Kind, List<Fulfillment>> batches = new EnumMap<>(TradeOrder.Kind.class);

    /** 为两类订单的第一仓库准备两批、第二仓库准备一批，使用真实订单版本和明细。 */
    @BeforeEach
    void prepareHistory() {
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getFirst(); var hidden = fixtures.get(kind).getLast();
            batches.put(kind, List.of(fulfill(order), fulfill(order), fulfill(hidden)));
        }
    }

    /**
     * 默认无权且HTTP404；指定范围只返回原仓库两批完整明细，批次和订单条件不能扩大范围。
     * @throws Exception HTTP失败
     */
    @Test
    void restrictsCountsPagesAndLinesToAuthorizedOrderWarehouse() throws Exception {
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getFirst();
            assertEquals(0, queries.execute(query(order, 1, null), context).getTotal());
            assertEquals(404, request("GET", path(kind) + "/" + order.getId() + "/fulfillments", token, null).statusCode());
        }
        configure(role, WarehouseScope.custom(Set.of(first)));
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getFirst(); var hidden = fixtures.get(kind).getLast();
            var history = batches.get(kind);
            for (int page = 1; page <= 2; page++) {
                var result = queries.execute(query(order, page, null), context);
                assertEquals(2, result.getTotal()); assertEquals(1, result.getItems().size()); var row = result.getItems().getFirst();
                assertEquals(history.get(page - 1).getId(), row.id()); assertEquals(page, row.orderVersion()); assertEquals(1, row.lines().size());
                assertEquals(order.getLines().getFirst().getId(), row.lines().getFirst().orderLineId());
                assertEquals(0, new BigDecimal("0.25").compareTo(row.lines().getFirst().quantity()));
            }
            assertTrue(queries.execute(query(order, 3, null), context).getItems().isEmpty());
            assertEquals(0, queries.execute(query(hidden, 1, null), context).getTotal());
            assertEquals(0, queries.execute(query(hidden, 1, history.getLast().getId()), context).getTotal());
            assertEquals(0, queries.execute(query(order, 1, history.getLast().getId()), context).getTotal());
            assertEquals(0, queries.execute(query(hidden, 1, history.getFirst().getId()), context).getTotal());
            assertEquals(0, queries.execute(query(fixtures.get(kind).get(1), 1, null), context).getTotal());
            String url = path(kind) + "/" + hidden.getId() + "/fulfillments?postingId=" + history.getLast().getId();
            assertEquals(404, request("GET", url, token, null).statusCode());
            assertEquals(1, data(request("GET", url, accessToken(), null), 200).path("total").asLong());
        }
        configure(role, WarehouseScope.all());
        for (var kind : TradeOrder.Kind.values()) {
            var hidden = fixtures.get(kind).getLast();
            assertEquals(1, data(request("GET", path(kind) + "/" + hidden.getId() + "/fulfillments", token, null), 200).path("total").asLong());
        }
        configure(role, WarehouseScope.custom(Set.of()));
        for (var kind : TradeOrder.Kind.values()) assertEquals(404, request("GET", path(kind) + "/" + fixtures.get(kind).getLast().getId() + "/fulfillments", token, null).statusCode());
    }

    /**
     * 跨角色并集不复制履约头，移除成员或停用立即丢失原仓库历史；错误类别不能读。
     * @throws Exception HTTP失败
     */
    @Test
    void observesCurrentUnionMembershipAndAccountStatus() throws Exception {
        UUID other = createRole("history-union-" + UUID.randomUUID(), "历史并集");
        configure(role, WarehouseScope.custom(Set.of(first))); configure(other, WarehouseScope.custom(Set.of(first, second)));
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role, other)); return null; });
        for (var kind : TradeOrder.Kind.values()) {
            assertEquals(2, queries.execute(query(fixtures.get(kind).getFirst(), 1, null), context).getTotal());
            assertEquals(1, queries.execute(query(fixtures.get(kind).getLast(), 1, null), context).getTotal());
        }
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        var hidden = fixtures.get(TradeOrder.Kind.PURCHASE).getLast();
        assertEquals(0, queries.execute(query(hidden, 1, null), context).getTotal());
        var allowed = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst();
        assertEquals(0, queries.execute(FulfillmentQuery.create(TradeOrder.Kind.SALE, allowed.getId(), 1, 20, List.of(), List.of()), context).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        assertEquals(0, queries.execute(query(allowed, 1, null), context).getTotal());
    }

    /** 主体和事务校验以及头/行两阶段ORM故障均失败关闭，不返回部分历史结果。 */
    @Test
    void failsClosedForContextTransactionAndEitherDatabaseStage() {
        var order = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst(); var query = query(order, 1, null);
        error(CommonErrorCode.UNAUTHORIZED, () -> queries.execute(query, QueryContext.anonymous("history-anonymous")));
        error(CommonErrorCode.FORBIDDEN, () -> queries.execute(query, new QueryContext.Snapshot("history-invalid", Optional.of("administrator"), Set.of("all"))));
        assertEquals(0, queries.execute(query, new QueryContext.Snapshot("history-missing", Optional.of(UUID.randomUUID().toString()), Set.of("all"))).getTotal());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> queries.execute(query, context)));
        var broken = mock(EasyEntityQuery.class);
        when(broken.queryable(FulfillmentView.class)).thenThrow(new EasyQueryException("sensitive-history-head"));
        error(CommonErrorCode.INTERNAL_ERROR, () -> new FulfillmentQueryHandler(broken).handle(query, context));
        configure(role, WarehouseScope.custom(Set.of(first)));
        var brokenLines = mock(EasyEntityQuery.class);
        when(brokenLines.queryable(FulfillmentView.class)).thenAnswer(ignored -> database.queryable(FulfillmentView.class));
        when(brokenLines.queryable(FulfillmentLineView.class)).thenThrow(new EasyQueryException("sensitive-history-line"));
        error(CommonErrorCode.INTERNAL_ERROR, () -> new FulfillmentQueryHandler(brokenLines).handle(query, context));
    }

    /**
     * 原子更新订单并追加四分之一数量的履约凭据，不执行库存过账。
     * @param order 原订单
     * @return 本次凭据
     */
    private Fulfillment fulfill(TradeOrder order) {
        var quantities = Map.of(order.getLines().getFirst().getId(), new Quantity(new BigDecimal("0.25")));
        return work.execute(() -> { order.fulfill(quantities); orders.save(order); var posting = Fulfillment.create(order, quantities); postings.append(posting); return posting; });
    }

    /**
     * 构造原订单固定查询，按版本升序每页一批。
     * @param order 原订单
     * @param page 页码
     * @param posting 可选批次
     * @return 历史请求
     */
    private FulfillmentQuery query(TradeOrder order, int page, UUID posting) {
        return FulfillmentQuery.create(order.getKind(), order.getId(), page, 1, List.of("orderVersion,asc"), posting == null ? List.of() : List.of(eq("id", posting)));
    }
}
