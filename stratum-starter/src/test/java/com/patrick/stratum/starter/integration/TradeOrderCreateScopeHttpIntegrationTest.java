package com.patrick.stratum.starter.integration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.trade.TradeOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真实订单创建必须同时满足创建API和目标仓库范围，撤回范围后原幂等键也拒绝。 */
class TradeOrderCreateScopeHttpIntegrationTest extends TradeOrderWriteScopeIntegrationTest {
    /**
     * 双类型创建默认拒绝；指定范围可创建和重放，其他仓库及撤回后原键均无写副作用。
     * @throws Exception HTTP失败
     */
    @Test
    void checksCurrentWarehouseBeforeCreationAndReceiptReplay() throws Exception {
        grant(Set.of(PermissionCatalog.PURCHASE_CREATE.permission(), PermissionCatalog.SALES_CREATE.permission()));
        for (var kind : TradeOrder.Kind.values()) {
            String key = UUID.randomUUID().toString(); int count = orderCount(kind);
            assertEquals(403, write(path(kind), token, input(kind, first), key).statusCode());
            assertEquals(0, receipts(key)); assertEquals(0, events()); assertEquals(count, orderCount(kind));
        }
        configure(role, WarehouseScope.custom(Set.of(first)));
        for (var kind : TradeOrder.Kind.values()) {
            String key = UUID.randomUUID().toString(); int count = orderCount(kind);
            var created = data(write(path(kind), token, input(kind, first), key), 201);
            int committedEvents = events(); assertEquals(1, receipts(key)); assertEquals(count + 1, orderCount(kind));
            assertEquals(created, data(write(path(kind), token, input(kind, first), key), 201));
            assertEquals(committedEvents, events()); assertEquals(count + 1, orderCount(kind));
            assertEquals(403, request("GET", path(kind) + "/" + created.path("id").asText(), token, null).statusCode());
            String denied = UUID.randomUUID().toString();
            assertEquals(403, write(path(kind), token, input(kind, second), denied).statusCode());
            assertEquals(0, receipts(denied)); assertEquals(committedEvents, events()); assertEquals(count + 1, orderCount(kind));
            configure(role, WarehouseScope.custom(Set.of()));
            assertEquals(403, write(path(kind), token, input(kind, first), key).statusCode());
            assertEquals(1, receipts(key)); assertEquals(committedEvents, events()); assertEquals(count + 1, orderCount(kind));
            configure(role, WarehouseScope.custom(Set.of(first)));
            assertEquals(created, data(write(path(kind), token, input(kind, first), key), 201)); assertEquals(committedEvents, events());
        }
    }

    /**
     * ALL范围不能替代创建API，只有创建权限仍可操作全部仓库；匿名和Refresh均401。
     * @throws Exception HTTP失败
     */
    @Test
    void keepsApiAndWarehouseChecksIndependent() throws Exception {
        configure(role, WarehouseScope.all());
        for (var kind : TradeOrder.Kind.values()) {
            String key = UUID.randomUUID().toString();
            assertEquals(403, write(path(kind), token, input(kind, second), key).statusCode());
            assertEquals(401, write(path(kind), null, input(kind, second), key).statusCode());
            assertEquals(401, write(path(kind), refreshToken(), input(kind, second), key).statusCode());
            assertEquals(0, receipts(key)); assertEquals(0, events());
        }
        grant(Set.of(PermissionCatalog.PURCHASE_CREATE.permission(), PermissionCatalog.SALES_CREATE.permission()));
        for (var kind : TradeOrder.Kind.values()) for (UUID warehouse : List.of(first, second)) {
            var created = data(write(path(kind), token, input(kind, warehouse), UUID.randomUUID().toString()), 201);
            assertEquals(warehouse.toString(), created.path("warehouseId").asText());
        }
    }

    /**
     * 从固定真实夹具构造新订单输入，只允许请求指定目标仓库。
     * @param kind 类别
     * @param warehouse 目标仓库
     * @return 输入
     */
    private Map<String, Object> input(TradeOrder.Kind kind, UUID warehouse) {
        var source = fixtures.get(kind).getFirst();
        return Map.of("partyId", source.getPartyId(), "warehouseId", warehouse,
                "lines", List.of(Map.of("materialId", source.getLines().getFirst().getMaterialId(), "quantity", 1, "unitPrice", 1)));
    }

    /**
     * 统计本用例独立交易方订单，验证拒绝没有业务写入。
     * @param kind 类别
     * @return 数量
     */
    private int orderCount(TradeOrder.Kind kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE party_id=?", Integer.class, fixtures.get(kind).getFirst().getPartyId().toString());
    }
}
