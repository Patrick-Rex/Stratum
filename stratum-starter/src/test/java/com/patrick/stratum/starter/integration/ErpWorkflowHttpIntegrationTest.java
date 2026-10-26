package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.patrick.stratum.application.masterdata.MasterDataApplicationService;
import com.patrick.stratum.common.base.cache.VersionedCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 从HTTP创建全部主数据开始完成进销存闭环，只用数据库与缓存作独立断言及清理。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ErpWorkflowHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    private final JdbcTemplate jdbc;
    private final VersionedCache cache;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    protected final String trace = UUID.randomUUID().toString();
    private final List<String> masterIds = new ArrayList<>();
    private final List<String> orderIds = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    /**
     * 注入独立断言和清理依赖，业务操作全部通过HTTP。
     * @param jdbc 数据库断言客户端
     * @param cache 版本缓存
     * @param redis 专属缓存键清理客户端
     */
    @Autowired
    ErpWorkflowHttpIntegrationTest(JdbcTemplate jdbc, VersionedCache cache, StringRedisTemplate redis) {
        this.jdbc = jdbc; this.cache = cache; this.redis = redis;
    }

    /** HTTP闭环覆盖主数据、分批采购、库存不足回滚、补货后销售重试及Outbox/回执一致性。
     * @throws Exception HTTP失败
     */
    @Test
    void completesPurchaseAndSaleFromHttpMasterData() throws Exception {
        var material = master("MATERIAL"); var warehouse = master("WAREHOUSE");
        var supplier = master("SUPPLIER"); var customer = master("CUSTOMER");
        String materialPath = "/api/erp/masters/MATERIAL/" + material.path("id").asText();
        var renamed = send("PATCH", materialPath, key(), Map.of("version", 0, "name", "闭环物料改名"), 200).path("data");
        assertEquals("闭环物料改名", renamed.path("name").asText());
        assertEquals(renamed, send("GET", materialPath, null, null, 200).path("data"));
        var purchase = order("PURCHASE", supplier, warehouse, material, "2.5", "2.3456");
        assertEquals(0, new BigDecimal("5.86").compareTo(new BigDecimal(purchase.path("totalAmount").asText())));
        var firstReceipt = send("POST", fulfillmentPath(purchase), key(), fulfill(purchase, "1"), 201).path("data");
        assertEquals("PARTIAL", firstReceipt.path("order").path("status").asText());
        assertStock(material, "1");
        var sale = order("SALE", customer, warehouse, material, "2", "3.005");
        assertEquals(0, new BigDecimal("6.01").compareTo(new BigDecimal(sale.path("totalAmount").asText())));
        String saleKey = key(); var saleRequest = fulfill(sale, "2");
        assertEquals(40900, send("POST", fulfillmentPath(sale), saleKey, saleRequest, 409).path("code").asInt());
        var pending = send("GET", orderPath(sale), null, null, 200).path("data");
        assertEquals("PENDING", pending.path("order").path("status").asText());
        assertEquals(0, pending.path("order").path("version").asLong());
        assertEquals(0, send("GET", fulfillmentPath(sale), null, null, 200).path("data").path("total").asInt());
        assertStock(material, "1");
        var secondReceipt = send("POST", fulfillmentPath(purchase), key(), fulfill(firstReceipt.path("order"), "1.5"), 201).path("data");
        assertEquals("COMPLETED", secondReceipt.path("order").path("status").asText());
        var issued = send("POST", fulfillmentPath(sale), saleKey, saleRequest, 201).path("data");
        assertEquals("COMPLETED", issued.path("order").path("status").asText());
        assertEquals(issued, send("POST", fulfillmentPath(sale), saleKey, saleRequest, 201).path("data"));
        assertStock(material, "0.5");
        assertEquals(2, send("GET", fulfillmentPath(purchase), null, null, 200).path("data").path("total").asInt());
        var history = send("GET", fulfillmentPath(sale), null, null, 200).path("data").path("items").get(0);
        var movement = send("GET", "/api/erp/stock-movements?postingId=" + issued.path("postingId").asText(), null, null, 200)
                .path("data").path("items").get(0);
        assertEquals(history.path("id"), movement.path("postingId"));
        assertEquals(history.path("lines").get(0).path("orderLineId"), movement.path("orderLineId"));
        assertEquals("ISSUE", movement.path("direction").asText());
        assertEquals(40900, send("DELETE", materialPath + "?version=1", key(), null, 409).path("code").asInt());
        assertEquals(18, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
        int receipts = keys.stream().mapToInt(key -> jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key = ?", Integer.class, key)).sum();
        assertEquals(10, receipts);
        var cached = cache.get(MasterDataApplicationService.cacheKey(material.path("id").asText()),
                MasterDataApplicationService.CACHE_POLICY, () -> { throw new AssertionError("主数据提交后的缓存未命中"); });
        assertTrue(cached.isPresent()); assertEquals(1, cached.orElseThrow().version());
    }

    /** 不支持的版本在业务写入前返回版本错误，不能产生主数据或回执。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsUnsupportedVersionBeforeWriting() throws Exception {
        String key = key();
        var request = HttpRequest.newBuilder(endpoint("/api/erp/masters/CUSTOMER"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken()).header("X-Trace-Id", trace).header("X-API-Version", "2")
                .header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("code", UUID.randomUUID().toString(), "name", "版本测试")))).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertEquals(40001, json.readTree(response.body()).path("code").asInt());
            assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key = ?", Integer.class, key));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
    }

    /** 只清理本测试随机标识对应的数据库行及缓存，不停止共享服务。 */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", trace);
        for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key = ? AND scope LIKE ?", key, "%:" + subjectId());
        for (String id : orderIds) jdbc.update("DELETE FROM erp_stock_movement WHERE order_id = ?", id);
        for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id = ?", id);
        for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment WHERE order_id = ?", id);
        for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id);
        for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id);
        for (String id : masterIds) jdbc.update("DELETE FROM erp_stock_balance WHERE material_id = ? OR warehouse_id = ?", id, id);
        for (String id : masterIds) {
            jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            String key = MasterDataApplicationService.cacheKey(id);
            cache.invalidate(key, 100, MasterDataApplicationService.CACHE_POLICY);
            redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
        }
    }

    /**
     * 经HTTP创建并记录四类主数据。
     * @param kind 类别
     * @return 主数据JSON
     * @throws Exception HTTP失败
     */
    private JsonNode master(String kind) throws Exception {
        var input = new java.util.HashMap<String, Object>(Map.of("code", UUID.randomUUID().toString(), "name", "闭环" + kind));
        if (kind.equals("MATERIAL")) input.put("baseUnit", "千克");
        var value = send("POST", "/api/erp/masters/" + kind, key(), input, 201).path("data");
        masterIds.add(value.path("id").asText()); return value;
    }

    /**
     * 经HTTP创建一行订单并记录清理范围。
     * @param kind 类别
     * @param party 交易方
     * @param warehouse 仓库
     * @param material 物料
     * @param quantity 数量
     * @param price 单价
     * @return 订单JSON
     * @throws Exception HTTP失败
     */
    private JsonNode order(String kind, JsonNode party, JsonNode warehouse, JsonNode material, String quantity, String price) throws Exception {
        var input = Map.of("partyId", party.path("id").asText(), "warehouseId", warehouse.path("id").asText(),
                "lines", List.of(Map.of("materialId", material.path("id").asText(), "quantity", new BigDecimal(quantity), "unitPrice", new BigDecimal(price))));
        var order = send("POST", "/api/erp/orders/" + kind, key(), input, 201).path("data");
        orderIds.add(order.path("id").asText()); return order;
    }

    /**
     * 从观察版本创建首行履约请求。
     * @param order 订单快照
     * @param quantity 数量
     * @return JSON可序列化输入
     */
    private Map<String, Object> fulfill(JsonNode order, String quantity) {
        return Map.of("version", order.path("version").asLong(), "lines", List.of(Map.of("orderLineId",
                order.path("lines").get(0).path("id").asText(), "quantity", new BigDecimal(quantity))));
    }

    /**
     * 取得订单路径。
     * @param order 订单JSON
     * @return 相对路径
     */
    private String orderPath(JsonNode order) { return "/api/erp/orders/" + order.path("kind").asText() + "/" + order.path("id").asText(); }

    /**
     * 取得履约路径。
     * @param order 订单JSON
     * @return 相对路径
     */
    private String fulfillmentPath(JsonNode order) { return orderPath(order) + "/fulfillments"; }

    /**
     * 经查询接口核对当前精确库存。
     * @param material 物料JSON
     * @param expected 期望数量
     * @throws Exception HTTP失败
     */
    private void assertStock(JsonNode material, String expected) throws Exception {
        var page = send("GET", "/api/erp/stock-balances?materialId=" + material.path("id").asText(), null, null, 200).path("data");
        assertEquals(1, page.path("total").asInt());
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(page.path("items").get(0).path("quantity").asText())));
    }

    /**
     * 发送真实HTTP并检查标准外壳、版本及关联标识。
     * @param method 方法
     * @param path 路径
     * @param key 幂等键或空
     * @param payload 请求对象或空
     * @param status 期望状态
     * @return 标准JSON响应
     * @throws Exception HTTP失败
     */
    private JsonNode send(String method, String path, String key, Object payload, int status) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken()).header("X-Trace-Id", trace).header("X-API-Version", "1").header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode(), response.body());
            assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow());
            var value = json.readTree(response.body()); assertEquals(trace, value.path("traceId").asText());
            if (status < 400) assertEquals(0, value.path("code").asInt()); else assertTrue(value.path("details").isArray());
            return value;
        }
    }

    /**
     * 提供业务请求入口，子类可复用全部断言经独立网关执行。
     * @param path 固定相对业务路径
     * @return 本机直连入口
     */
    protected URI endpoint(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    /**
     * 为本次用例记录唯一幂等键。
     * @return UUID键
     */
    private String key() { String key = UUID.randomUUID().toString(); keys.add(key); return key; }
}
