package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.TradeOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP验证采购销售的读取、创建、履约隔离及撤权后幂等重放不得修改库存。 */
class TradeOrderAuthorizationHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    private final String trace = UUID.randomUUID().toString();
    private final Map<MasterData.Kind, String> masterIds = new EnumMap<>(MasterData.Kind.class);
    private final Set<String> orderIds = new HashSet<>();
    private final List<String> keys = new ArrayList<>();
    private UUID role;
    private long roleVersion;
    private String token;

    /** 清理本测试订单、履约、库存维度、事件与回执；父夹具精确清理自己的认证数据。 */
    @AfterEach
    void cleanBusiness() {
        work.execute(() -> {
            jdbc.update("DELETE FROM outbox_event WHERE trace_id=?", trace);
            for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key=?", key);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_stock_movement WHERE order_id=?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id=?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment WHERE order_id=?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id=?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id=?", id);
            for (String id : masterIds.values()) jdbc.update("DELETE FROM erp_stock_balance WHERE material_id=? OR warehouse_id=?", id, id);
            for (String id : masterIds.values()) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
            return null;
        });
    }

    /**
     * 普通和仅功能权限账号不能读订单，精确读取授权才开放同类摘要、详情及历史。
     * @throws Exception HTTP失败
     */
    @Test
    void isolatesReadKindsAndRejectsFeatureOnlyAccess() throws Exception {
        prepare(); var orders = new EnumMap<TradeOrder.Kind, JsonNode>(TradeOrder.Kind.class);
        for (var kind : TradeOrder.Kind.values()) orders.put(kind, create(kind, accessToken(), UUID.randomUUID().toString()));
        for (var kind : TradeOrder.Kind.values()) assertEquals(403, erp("GET", path(kind), token, null, null).statusCode());
        grant(Set.of(new Permission("erp.purchase", Permission.Type.FEAT), new Permission("erp.sales", Permission.Type.FEAT)));
        for (var kind : TradeOrder.Kind.values()) {
            assertEquals(403, erp("GET", path(kind), token, null, null).statusCode());
            assertEquals(403, erp("POST", path(kind), token, input(kind), UUID.randomUUID().toString()).statusCode());
            assertEquals(403, erp("POST", fulfillmentPath(kind, orders.get(kind)), token, batch(orders.get(kind)), UUID.randomUUID().toString()).statusCode());
            assertEquals(401, erp("GET", path(kind), null, null, null).statusCode());
        }
        for (var allowed : TradeOrder.Kind.values()) {
            grant(Set.of(permission(allowed, "read")));
            for (var actual : TradeOrder.Kind.values()) {
                int expected = actual == allowed ? 200 : 403; var order = orders.get(actual);
                assertEquals(expected, erp("GET", path(actual), token, null, null).statusCode());
                assertEquals(expected, erp("GET", path(actual) + "/" + order.path("id").asText(), token, null, null).statusCode());
                assertEquals(expected, erp("GET", fulfillmentPath(actual, order), token, null, null).statusCode());
            }
            assertEquals(403, erp("POST", path(allowed), token, input(allowed), UUID.randomUUID().toString()).statusCode());
            assertEquals(403, erp("POST", fulfillmentPath(allowed, orders.get(allowed)), token, batch(orders.get(allowed)), UUID.randomUUID().toString()).statusCode());
        }
    }

    /**
     * 两类创建授权各自生效，不隐含读取或履约；撤权时原成功创建回执不可重放。
     * @throws Exception HTTP失败
     */
    @Test
    void separatesCreationAndDeniesReplayAfterRevocation() throws Exception {
        prepare();
        for (var allowed : TradeOrder.Kind.values()) {
            grant(Set.of(permission(allowed, "create")));
            var other = allowed == TradeOrder.Kind.PURCHASE ? TradeOrder.Kind.SALE : TradeOrder.Kind.PURCHASE;
            String rejectedKey = UUID.randomUUID().toString();
            assertEquals(403, erp("POST", path(other), token, input(other), rejectedKey).statusCode()); assertEquals(0, receipts(rejectedKey));
            String key = UUID.randomUUID().toString(); var order = create(allowed, token, key);
            assertEquals(order, create(allowed, token, key));
            assertEquals(403, erp("GET", path(allowed), token, null, null).statusCode());
            String fulfillmentKey = UUID.randomUUID().toString();
            assertEquals(403, erp("POST", fulfillmentPath(allowed, order), token, batch(order), fulfillmentKey).statusCode());
            assertEquals(0, receipts(fulfillmentKey));
            int before = events(); grant(Set.of());
            assertEquals(403, erp("POST", path(allowed), token, input(allowed), key).statusCode());
            assertEquals(before, events()); assertEquals(1, receipts(key));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id=?", Integer.class, order.path("id").asText()));
        }
    }

    /**
     * 单独履约授权可完成采购收货及销售出库，错误类别与撤权重放均不改变版本、流水或余额。
     * @throws Exception HTTP失败
     */
    @Test
    void isolatesFulfillmentAndPreservesStockOnDeniedOperations() throws Exception {
        prepare();
        var purchase = create(TradeOrder.Kind.PURCHASE, accessToken(), UUID.randomUUID().toString());
        var sale = create(TradeOrder.Kind.SALE, accessToken(), UUID.randomUUID().toString());
        for (var allowed : TradeOrder.Kind.values()) {
            var order = allowed == TradeOrder.Kind.PURCHASE ? purchase : sale;
            var other = allowed == TradeOrder.Kind.PURCHASE ? TradeOrder.Kind.SALE : TradeOrder.Kind.PURCHASE;
            var otherOrder = allowed == TradeOrder.Kind.PURCHASE ? sale : purchase;
            grant(Set.of(permission(allowed, "fulfill")));
            String rejected = UUID.randomUUID().toString();
            assertEquals(403, erp("POST", fulfillmentPath(other, otherOrder), token, batch(otherOrder), rejected).statusCode());
            assertEquals(0, receipts(rejected));
            assertEquals(403, erp("POST", path(allowed), token, input(allowed), UUID.randomUUID().toString()).statusCode());
            assertEquals(403, erp("GET", fulfillmentPath(allowed, order), token, null, null).statusCode());
            String key = UUID.randomUUID().toString();
            var response = erp("POST", fulfillmentPath(allowed, order), token, batch(order), key); assertEquals(201, response.statusCode());
            var replay = erp("POST", fulfillmentPath(allowed, order), token, batch(order), key); assertEquals(201, replay.statusCode());
            assertEquals(json.readTree(response.body()).path("data"), json.readTree(replay.body()).path("data"));
            BigDecimal balance = jdbc.queryForObject("SELECT quantity FROM erp_stock_balance WHERE material_id=? AND warehouse_id=?", BigDecimal.class,
                    masterIds.get(MasterData.Kind.MATERIAL), masterIds.get(MasterData.Kind.WAREHOUSE));
            assertEquals(0, balance.compareTo(allowed == TradeOrder.Kind.PURCHASE ? BigDecimal.ONE : BigDecimal.ZERO));
            int before = events(); grant(Set.of());
            assertEquals(403, erp("POST", fulfillmentPath(allowed, order), token, batch(order), key).statusCode());
            assertEquals(before, events()); assertEquals(1, receipts(key));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement WHERE order_id=?", Integer.class, order.path("id").asText()));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM erp_trade_order WHERE id=?", Long.class, order.path("id").asText()));
            assertEquals(0, balance.compareTo(jdbc.queryForObject("SELECT quantity FROM erp_stock_balance WHERE material_id=? AND warehouse_id=?", BigDecimal.class,
                    masterIds.get(MasterData.Kind.MATERIAL), masterIds.get(MasterData.Kind.WAREHOUSE))));
        }
    }

    /**
     * 准备独立主数据和空API权限、显式ALL仓库范围普通账号，测试会话始终复用以验证实时授权变化。
     * @throws Exception HTTP失败
     */
    private void prepare() throws Exception {
        for (var kind : MasterData.Kind.values()) {
            var value = MasterData.create(kind, UUID.randomUUID().toString(), "订单鉴权", kind == MasterData.Kind.MATERIAL ? "件" : null);
            masterIds.put(kind, value.getId()); work.execute(() -> { masters.save(value); return null; });
        }
        String username = "trade-rbac-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); role = createRole("trade-rbac-" + UUID.randomUUID(), "订单权限");
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        data(request("PUT", "/api/auth/roles/" + role + "/warehouse-scope", accessToken(),
                Map.of("version", 0, "scopeType", "ALL", "warehouseIds", Set.of())), 200);
        token = login(username, password).accessToken();
    }

    /**
     * 经真实管理HTTP替换角色权限。
     * @param grants 精确权限集合
     * @throws Exception HTTP失败
     */
    private void grant(Set<Permission> grants) throws Exception {
        roleVersion = data(request("PUT", "/api/auth/roles/" + role, accessToken(),
                Map.of("version", roleVersion, "name", "订单权限", "permissions", grants)), 200).path("version").asLong();
    }

    /**
     * 依公开权限编码构造测试期望，不调用待测映射。
     * @param kind 订单类别
     * @param action 操作编码
     * @return API权限
     */
    private Permission permission(TradeOrder.Kind kind, String action) {
        return new Permission("erp." + (kind == TradeOrder.Kind.PURCHASE ? "purchase" : "sales") + "." + action, Permission.Type.API);
    }

    /**
     * 提供两单位订单，以便验证分批一单位履约。
     * @param kind 订单类别
     * @return 固定且可重复序列化的创建输入
     */
    private Map<String, Object> input(TradeOrder.Kind kind) {
        return Map.of("partyId", masterIds.get(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER),
                "warehouseId", masterIds.get(MasterData.Kind.WAREHOUSE), "lines",
                List.of(Map.of("materialId", masterIds.get(MasterData.Kind.MATERIAL), "quantity", 2, "unitPrice", 1)));
    }

    /**
     * 从创建结果生成首次一单位履约输入。
     * @param order 初始订单
     * @return 分批输入
     */
    private Map<String, Object> batch(JsonNode order) {
        return Map.of("version", 0, "lines", List.of(Map.of("orderLineId", order.path("lines").get(0).path("id").asText(), "quantity", 1)));
    }

    /**
     * 创建或重放订单。
     * @param kind 类别
     * @param access 已授权Access
     * @param key 幂等键
     * @return 原始订单结果
     * @throws Exception HTTP失败
     */
    private JsonNode create(TradeOrder.Kind kind, String access, String key) throws Exception {
        var response = erp("POST", path(kind), access, input(kind), key); assertEquals(201, response.statusCode());
        return json.readTree(response.body()).path("data");
    }

    /**
     * 生成订单路径。
     * @param kind 类别
     * @return 集合路径
     */
    private String path(TradeOrder.Kind kind) { return "/api/erp/orders/" + kind; }

    /**
     * 生成已有订单履约路径。
     * @param kind 类别
     * @param order 订单结果
     * @return 履约路径
     */
    private String fulfillmentPath(TradeOrder.Kind kind, JsonNode order) { return path(kind) + "/" + order.path("id").asText() + "/fulfillments"; }

    /**
     * 统计本测试随机幂等键回执。
     * @param key 随机幂等键
     * @return 回执数量
     */
    private int receipts(String key) { return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key=?", Integer.class, key); }

    /**
     * 统计本测试链路事件，用于验证拒绝操作无副作用。
     * @return 事件数量
     */
    private int events() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id=?", Integer.class, trace); }

    /**
     * 发送有界ERP请求，记录本测试清理资源，并附带不能授权的伪造管理员头。
     * @param method HTTP方法
     * @param path 路由
     * @param access 可空令牌
     * @param input 可空输入
     * @param key 可空幂等键
     * @return HTTP响应
     * @throws Exception HTTP失败
     */
    private HttpResponse<String> erp(String method, String path, String access, Object input, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Trace-Id", trace).header("X-User-Id", subjectId());
        if (access != null) builder.header("Authorization", "Bearer " + access);
        if (key != null) { keys.add(key); builder.header("Idempotency-Key", key); }
        builder.method(method, input == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); var body = json.readTree(response.body());
            assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow()); assertEquals(trace, body.path("traceId").asText());
            if (response.statusCode() == 201 && !path.contains("/fulfillments")) orderIds.add(body.path("data").path("id").asText());
            if (response.statusCode() == 403) assertEquals(40300, body.path("code").asInt());
            return response;
        }
    }
}
