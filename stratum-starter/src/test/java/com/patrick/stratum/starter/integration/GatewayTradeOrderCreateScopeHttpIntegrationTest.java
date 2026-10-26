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

/** 经实际Gateway JAR复用创建API/仓库/重放矩阵，并联合验证真实认证和入口限流。 */
class GatewayTradeOrderCreateScopeHttpIntegrationTest extends TradeOrderCreateScopeHttpIntegrationTest {
    /** {@inheritDoc} */
    @Override protected boolean gatewayEnabled() { return true; }

    /**
     * 经网关登录、刷新、本人识别与退出，伪造操作者不能授权；空桶拒绝不能到达ERP写入。
     * @throws Exception 本机真实HTTP失败
     */
    @Test void authenticatesThroughGatewayAndRejectsBeforeErpWrites() throws Exception {
        String username = "gateway-login-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID ordinaryId = createAccount(username, password);
        var login = request("POST", "/auth/login", null, Map.of("username", username, "password", password));
        assertEquals(200, login.statusCode(), "经网关登录失败");
        var session = rememberSession(json.readTree(login.body()).path("data"));
        var me = data(request("GET", "/auth/me?userId=" + subjectId(), session.accessToken(), null), 200);
        assertEquals(ordinaryId.toString(), me.path("userId").asText()); assertEquals(0, me.path("featurePermissions").size());
        assertEquals(403, request("GET", path(TradeOrder.Kind.PURCHASE), session.accessToken(), null).statusCode());
        assertEquals(401, request("GET", "/auth/me", null, null).statusCode());
        assertEquals(401, request("GET", "/auth/me", session.refreshToken(), null).statusCode());
        var refreshed = request("POST", "/auth/refresh", null, Map.of("refreshToken", session.refreshToken()));
        assertEquals(200, refreshed.statusCode(), "经网关刷新失败");
        var replacement = rememberSession(json.readTree(refreshed.body()).path("data"));
        assertEquals(ordinaryId.toString(), data(request("GET", "/auth/me", replacement.accessToken(), null), 200).path("userId").asText());
        assertEquals(200, request("POST", "/auth/logout", replacement.accessToken(), null).statusCode());
        assertEquals(401, request("GET", "/auth/me", replacement.accessToken(), null).statusCode());
        assertEquals(0, data(request("GET", path(TradeOrder.Kind.PURCHASE), token, null), 200).path("total").asInt());
        configure(role, WarehouseScope.custom(Set.of(first)));
        var visible = data(request("GET", path(TradeOrder.Kind.PURCHASE), token, null), 200);
        assertEquals(2, visible.path("total").asInt());
        assertTrue(visible.path("items").valueStream().allMatch(item -> first.toString().equals(item.path("warehouseId").asText())));
        String detail = path(TradeOrder.Kind.PURCHASE) + "/" + fixtures.get(TradeOrder.Kind.PURCHASE).getFirst().getId();
        assertEquals(200, request("GET", detail, token, null).statusCode());
        configure(role, WarehouseScope.custom(Set.of()));
        assertEquals(404, request("GET", detail, token, null).statusCode());
        assertEquals(0, data(request("GET", path(TradeOrder.Kind.PURCHASE), token, null), 200).path("total").asInt());
        grant(Set.of(PermissionCatalog.PURCHASE_CREATE.permission())); configure(role, WarehouseScope.all());
        var order = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst(); String key = UUID.randomUUID().toString();
        var input = Map.of("partyId", order.getPartyId(), "warehouseId", first,
                "lines", List.of(Map.of("materialId", order.getLines().getFirst().getMaterialId(), "quantity", 1, "unitPrice", 1)));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE party_id=?", Integer.class, order.getPartyId().toString());
        String prefix = "{" + gateway.namespace() + "}:rate:";
        scopeRedis.opsForHash().put(prefix + "tokens", "7f000001", "0");
        scopeRedis.opsForHash().put(prefix + "times", "7f000001", Long.toString(Long.MAX_VALUE / 2));
        var rejected = write(path(TradeOrder.Kind.PURCHASE), token, input, key);
        assertEquals(429, rejected.statusCode()); assertEquals(40002, json.readTree(rejected.body()).path("code").asInt());
        assertEquals(0, receipts(key)); assertEquals(0, events());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE party_id=?", Integer.class, order.getPartyId().toString()));
    }
}
