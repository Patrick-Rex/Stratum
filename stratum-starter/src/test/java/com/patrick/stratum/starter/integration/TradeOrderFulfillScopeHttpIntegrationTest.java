package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.trade.TradeOrder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/** 真实采购收货和销售出库必须同时具有履约API及原订单仓库范围，回执不能绕过实时授权。 */
class TradeOrderFulfillScopeHttpIntegrationTest extends TradeOrderWriteScopeIntegrationTest {
    /**
     * 不可变数据库效果快照，验证拒绝或重放没有订单、凭据、库存、流水或事件副作用。
     * @param quantity 仓库库存数量
     * @param balanceVersion 库存版本合计
     * @param orderVersion 原订单版本合计
     * @param postings 履约凭据数量
     * @param movements 库存流水数量
     * @param events 本链路事件数量
     */
    private record State(BigDecimal quantity, long balanceVersion, long orderVersion, int postings, int movements, int events) { }

    /**
     * 默认无范围不履约，指定仓库完成分批采购和销售；范围撤回后原键拒绝且数据库效果保持不变。
     * @throws Exception HTTP失败
     */
    @Test
    void authorizesOriginalWarehouseBeforePostingAndReceiptReplay() throws Exception {
        grant(Set.of(PermissionCatalog.PURCHASE_FULFILL.permission(), PermissionCatalog.SALES_FULFILL.permission()));
        var empty = state(first);
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getFirst(); String key = UUID.randomUUID().toString();
            assertEquals(404, write(batchPath(order), token, batch(order, 0, "0.25"), key).statusCode());
            assertEquals(0, receipts(key)); assertEquals(empty, state(first));
        }
        configure(role, WarehouseScope.custom(Set.of(first)));
        for (var kind : TradeOrder.Kind.values()) {
            var hidden = fixtures.get(kind).getLast(); String key = UUID.randomUUID().toString(); var before = state(second);
            assertEquals(404, write(batchPath(hidden) + "?warehouseId=" + first, token, batch(hidden, 0, "0.25"), key).statusCode());
            assertEquals(0, receipts(key)); assertEquals(before, state(second));
        }
        var purchase = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst(); var sale = fixtures.get(TradeOrder.Kind.SALE).getFirst();
        String purchaseKey = UUID.randomUUID().toString(), saleKey = UUID.randomUUID().toString();
        var received = data(write(batchPath(purchase), token, batch(purchase, 0, "0.5"), purchaseKey), 201);
        var afterReceipt = state(first); assertEquals(0, new BigDecimal("0.5").compareTo(afterReceipt.quantity()));
        var shipped = data(write(batchPath(sale), token, batch(sale, 0, "0.125"), saleKey), 201);
        var afterShipment = state(first); assertEquals(0, new BigDecimal("0.375").compareTo(afterShipment.quantity()));
        assertEquals(2, afterShipment.movements()); assertEquals(2, afterShipment.postings());
        assertEquals(403, request("GET", path(purchase.getKind()) + "/" + purchase.getId(), token, null).statusCode());
        assertEquals(received, data(write(batchPath(purchase), token, batch(purchase, 0, "0.5"), purchaseKey), 201));
        assertEquals(shipped, data(write(batchPath(sale), token, batch(sale, 0, "0.125"), saleKey), 201)); assertEquals(afterShipment, state(first));
        configure(role, WarehouseScope.custom(Set.of()));
        assertEquals(404, write(batchPath(purchase), token, batch(purchase, 0, "0.5"), purchaseKey).statusCode());
        assertEquals(404, write(batchPath(sale), token, batch(sale, 0, "0.125"), saleKey).statusCode());
        String fresh = UUID.randomUUID().toString();
        assertEquals(404, write(batchPath(purchase), token, batch(purchase, 1, "0.25"), fresh).statusCode());
        assertEquals(1, receipts(purchaseKey)); assertEquals(1, receipts(saleKey)); assertEquals(0, receipts(fresh)); assertEquals(afterShipment, state(first));
        configure(role, WarehouseScope.custom(Set.of(first)));
        assertEquals(received, data(write(batchPath(purchase), token, batch(purchase, 0, "0.5"), purchaseKey), 201)); assertEquals(afterShipment, state(first));
        data(write(batchPath(purchase), token, batch(purchase, 1, "0.25"), fresh), 201);
        assertEquals(0, new BigDecimal("0.625").compareTo(state(first).quantity())); assertEquals(3, state(first).postings());
    }

    /**
     * ALL允许第二仓库真实收发，第一仓库不受影响；撤回API后原回执仍403，匿名及Refresh401。
     * @throws Exception HTTP失败
     */
    @Test
    void keepsAllScopeAndFulfillmentApiIndependentAcrossWarehouses() throws Exception {
        configure(role, WarehouseScope.all()); var before = state(second);
        for (var kind : TradeOrder.Kind.values()) {
            var order = fixtures.get(kind).getLast(); String key = UUID.randomUUID().toString();
            assertEquals(403, write(batchPath(order), token, batch(order, 0, "0.25"), key).statusCode());
            assertEquals(0, receipts(key)); assertEquals(before, state(second));
        }
        grant(Set.of(PermissionCatalog.PURCHASE_FULFILL.permission(), PermissionCatalog.SALES_FULFILL.permission()));
        var purchase = fixtures.get(TradeOrder.Kind.PURCHASE).getLast(); var sale = fixtures.get(TradeOrder.Kind.SALE).getLast();
        String key = UUID.randomUUID().toString(); var input = batch(purchase, 0, "0.5");
        JsonNode received = data(write(batchPath(purchase), token, input, key), 201);
        data(write(batchPath(sale), token, batch(sale, 0, "0.125"), UUID.randomUUID().toString()), 201);
        var committed = state(second); assertEquals(0, new BigDecimal("0.375").compareTo(committed.quantity()));
        assertEquals(0, state(first).quantity().signum()); assertEquals(0, state(first).movements());
        grant(Set.of(PermissionCatalog.PURCHASE_READ.permission(), PermissionCatalog.SALES_READ.permission()));
        assertEquals(403, write(batchPath(purchase), token, input, key).statusCode());
        assertEquals(401, write(batchPath(purchase), null, input, key).statusCode());
        assertEquals(401, write(batchPath(purchase), refreshToken(), input, key).statusCode());
        assertEquals(1, receipts(key)); assertEquals(committed, state(second));
        grant(Set.of(PermissionCatalog.PURCHASE_FULFILL.permission(), PermissionCatalog.SALES_FULFILL.permission()));
        assertEquals(received, data(write(batchPath(purchase), token, input, key), 201)); assertEquals(committed, state(second));
    }

    /**
     * 构造固定订单行的本次分批输入，不包含可自报的仓库归属。
     * @param order 原订单
     * @param version 观察版本
     * @param quantity 本次数量
     * @return 输入
     */
    private Map<String, Object> batch(TradeOrder order, long version, String quantity) {
        return Map.of("version", version, "lines", List.of(Map.of("orderLineId", order.getLines().getFirst().getId(), "quantity", new BigDecimal(quantity))));
    }

    /**
     * 获取原订单履约HTTP路径。
     * @param order 原订单
     * @return 路径
     */
    private String batchPath(TradeOrder order) { return path(order.getKind()) + "/" + order.getId() + "/fulfillments"; }

    /**
     * 从本用例独立仓库读取完整效果快照，标量查询只用于断言。
     * @param warehouse 独立仓库
     * @return 当前数据库效果
     */
    private State state(UUID warehouse) {
        String id = warehouse.toString();
        var quantity = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM erp_stock_balance WHERE warehouse_id=?", BigDecimal.class, id).stripTrailingZeros();
        long balanceVersion = jdbc.queryForObject("SELECT COALESCE(SUM(version),0) FROM erp_stock_balance WHERE warehouse_id=?", Long.class, id);
        long orderVersion = jdbc.queryForObject("SELECT COALESCE(SUM(version),0) FROM erp_trade_order WHERE warehouse_id=?", Long.class, id);
        int postings = jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment p JOIN erp_trade_order o ON p.order_id=o.id WHERE o.warehouse_id=?", Integer.class, id);
        int movements = jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement m JOIN erp_stock_balance b ON m.balance_id=b.id WHERE b.warehouse_id=?", Integer.class, id);
        return new State(quantity, balanceVersion, orderVersion, postings, movements, events());
    }
}
