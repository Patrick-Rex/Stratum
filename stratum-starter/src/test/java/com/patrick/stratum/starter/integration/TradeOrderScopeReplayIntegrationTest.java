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

import static org.junit.jupiter.api.Assertions.*;

/** 组合验收：不能将原无权目标改成有权目标并沿用旧键，以绕过范围检查读取旧回执或新增业务效果。 */
class TradeOrderScopeReplayIntegrationTest extends TradeOrderWriteScopeIntegrationTest {
    /**
     * 采购销售原仓库撤回后403；改为允许仓库仍因请求指纹冲突409，不返回原仓库快照。
     * @throws Exception HTTP失败
     */
    @Test
    void cannotReplayReceiptByChangingCreationWarehouse() throws Exception {
        grant(Set.of(PermissionCatalog.PURCHASE_CREATE.permission(), PermissionCatalog.SALES_CREATE.permission()));
        for (var kind : TradeOrder.Kind.values()) {
            configure(role, WarehouseScope.all()); var source = fixtures.get(kind).getFirst(); String key = UUID.randomUUID().toString();
            var original = data(write(path(kind), token, createInput(source, second), key), 201); int committed = events();
            configure(role, WarehouseScope.custom(Set.of(first)));
            assertEquals(403, write(path(kind), token, createInput(source, second), key).statusCode());
            assertEquals(409, write(path(kind), token, createInput(source, first), key).statusCode());
            assertEquals(1, receipts(key)); assertEquals(committed, events());
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE party_id=?", Integer.class, source.getPartyId().toString()));
            configure(role, WarehouseScope.all());
            assertEquals(original, data(write(path(kind), token, createInput(source, second), key), 201)); assertEquals(committed, events());
        }
    }

    /**
     * 原采购订单撤回后404；改为允许仓库另一订单并沿用旧键409，两个仓库均无重复写入。
     * @throws Exception HTTP失败
     */
    @Test
    void cannotReplayReceiptByChangingFulfillmentOrder() throws Exception {
        grant(Set.of(PermissionCatalog.PURCHASE_FULFILL.permission())); configure(role, WarehouseScope.all());
        var original = fixtures.get(TradeOrder.Kind.PURCHASE).getLast(); var substituted = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst();
        String key = UUID.randomUUID().toString(); String originalPath = path(original.getKind()) + "/" + original.getId() + "/fulfillments";
        var received = data(write(originalPath, token, batch(original), key), 201); int committed = events();
        configure(role, WarehouseScope.custom(Set.of(first)));
        assertEquals(404, write(originalPath, token, batch(original), key).statusCode());
        String substitutedPath = path(substituted.getKind()) + "/" + substituted.getId() + "/fulfillments";
        assertEquals(409, write(substitutedPath, token, batch(substituted), key).statusCode());
        assertEquals(1, receipts(key)); assertEquals(committed, events());
        assertEquals(0, jdbc.queryForObject("SELECT version FROM erp_trade_order WHERE id=?", Long.class, substituted.getId().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_balance WHERE warehouse_id=?", Integer.class, first.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id=?", Integer.class, original.getId().toString()));
        assertEquals(0, new BigDecimal("0.5").compareTo(jdbc.queryForObject("SELECT quantity FROM erp_stock_balance WHERE warehouse_id=?", BigDecimal.class, second.toString())));
        configure(role, WarehouseScope.all()); assertEquals(received, data(write(originalPath, token, batch(original), key), 201)); assertEquals(committed, events());
    }

    /**
     * 构造固定创建输入，仅替换目标仓库。
     * @param source 独立订单维度
     * @param warehouse 请求仓库
     * @return 创建输入
     */
    private Map<String, Object> createInput(TradeOrder source, UUID warehouse) {
        return Map.of("partyId", source.getPartyId(), "warehouseId", warehouse, "lines",
                List.of(Map.of("materialId", source.getLines().getFirst().getMaterialId(), "quantity", 1, "unitPrice", 1)));
    }

    /**
     * 构造原订单半单位首批输入，替换订单同时更换其合法行标识。
     * @param order 请求原订单
     * @return 履约输入
     */
    private Map<String, Object> batch(TradeOrder order) {
        return Map.of("version", 0, "lines", List.of(Map.of("orderLineId", order.getLines().getFirst().getId(), "quantity", new BigDecimal("0.5"))));
    }
}
