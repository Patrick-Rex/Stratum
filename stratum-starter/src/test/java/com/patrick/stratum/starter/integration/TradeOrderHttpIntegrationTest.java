package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.TradeOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP与数据库验证采购销售写协议、精确金额、幂等及输入边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TradeOrderHttpIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    private final JdbcTemplate jdbc;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final String trace = UUID.randomUUID().toString();
    private final List<String> masterIds = new ArrayList<>();
    private final Set<String> orderIds = new HashSet<>();
    private final Set<String> keys = new HashSet<>();

    /**
     * 当前测试独占的主数据。
     * @param material 物料
     * @param warehouse 仓库
     * @param supplier 供应商
     * @param customer 客户
     */
    private record Fixture(String material, String warehouse, String supplier, String customer) { }

    /**
     * 注入夹具和独立数据库断言依赖。
     * @param jdbc 断言及清理客户端
     * @param masters 主数据夹具仓储
     * @param unitOfWork 夹具工作单元
     */
    @Autowired
    TradeOrderHttpIntegrationTest(JdbcTemplate jdbc, MasterDataRepository masters, UnitOfWork unitOfWork) {
        this.jdbc = jdbc; this.masters = masters; this.unitOfWork = unitOfWork;
    }

    /** 两类订单自动编号、保持固定行顺序并在汇总后舍入，同键重放不重复创建。
     * @throws Exception HTTP失败
     */
    @Test
    void createsBothKindsWithExactTotalsAndReplay() throws Exception {
        var fixture = fixture(); String key = key();
        for (var kind : TradeOrder.Kind.values()) {
            String payload = payload(fixture, kind, "1", "0.005", 2);
            String path = "/api/erp/orders/" + kind;
            var created = send("POST", path, key, payload); assertEquals(201, created.statusCode(), created.body());
            var order = body(created).path("data"); String id = order.path("id").asText(); orderIds.add(id);
            assertEquals(path + "/" + id, created.headers().firstValue("Location").orElseThrow());
            assertEquals((kind == TradeOrder.Kind.PURCHASE ? "PO-" : "SO-") + id, order.path("orderNumber").asText());
            assertEquals("PENDING", order.path("status").asText()); assertEquals(0, order.path("version").asLong());
            assertEquals(0, new BigDecimal("0.01").compareTo(new BigDecimal(order.path("totalAmount").asText())));
            assertEquals(2, order.path("lines").size());
            assertNotEquals(order.path("lines").get(0).path("id"), order.path("lines").get(1).path("id"));
            assertEquals(order, body(send("POST", path, key, payload)).path("data"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key = ? AND scope = ?", Integer.class,
                    key, "trade:create:v1:" + kind + ":" + subjectId()));
            assertError(send("POST", path, key, payload.replace("0.005", "0.006")), 409, 40900);
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
    }

    /** 嵌套行数量/单价/条数、极端指数及缺失头均在写入前拒绝。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInvalidCreationInputsWithoutSideEffects() throws Exception {
        var fixture = fixture(); String path = "/api/erp/orders/PURCHASE";
        String valid = payload(fixture, TradeOrder.Kind.PURCHASE, "1", "1", 1);
        assertError(send("POST", path, null, valid), 400, 40000);
        assertError(send("POST", path, "invalid/key", valid), 400, 40000);
        for (String quantity : List.of("0", "-1", "0.0000000000001", "1e26", "1e2147483647", "1e-2147483647")) {
            assertError(send("POST", path, key(), payload(fixture, TradeOrder.Kind.PURCHASE, quantity, "1", 1)), 400, 40000);
        }
        for (String price : List.of("-1", "0.0000001", "1e13", "1e2147483647")) {
            assertError(send("POST", path, key(), payload(fixture, TradeOrder.Kind.PURCHASE, "1", price, 1)), 400, 40000);
        }
        assertError(send("POST", path, key(), payload(fixture, TradeOrder.Kind.PURCHASE, "1", "1", 101)), 400, 40000);
        assertError(send("POST", path, key(), payload(fixture, TradeOrder.Kind.PURCHASE, "1", "1", 0)), 400, 40000);
        assertError(send("POST", path, key(), valid.replaceFirst("\\[.*]", "[null]")), 400, 40000);
        assertError(send("POST", path, key(), "{}"), 400, 40000);
        assertError(send("POST", path, key(), payload(fixture, TradeOrder.Kind.PURCHASE, "1e25", "1e12", 1)), 400, 40000);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
        for (String key : keys) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key = ?", Integer.class, key));
    }

    /** 引用错误或类别不符返回404，未知路由类别返回400。
     * @throws Exception HTTP失败
     */
    @Test
    void validatesMasterReferencesAndKind() throws Exception {
        var fixture = fixture(); String payload = payload(fixture, TradeOrder.Kind.PURCHASE, "1", "1", 1);
        for (String invalid : List.of(payload.replace(fixture.supplier(), fixture.customer()),
                payload.replace(fixture.material(), fixture.warehouse()), payload.replace(fixture.warehouse(), UUID.randomUUID().toString()))) {
            assertError(send("POST", "/api/erp/orders/PURCHASE", key(), invalid), 404, 40400);
        }
        assertError(send("POST", "/api/erp/orders/INVALID", key(), payload), 400, 40000);
    }

    /** 创建文档保留请求模型、必填幂等头与201响应。
     * @throws Exception HTTP失败
     */
    @Test
    void documentsOrderCreation() throws Exception {
        var response = send("GET", "/v3/api-docs", null, null); assertEquals(200, response.statusCode());
        var paths = json.readTree(response.body()).path("paths");
        var operation = paths.path("/api/erp/orders/{kind}").path("post");
        assertTrue(operation.path("responses").has("201"));
        assertTrue(operation.path("requestBody").path("required").asBoolean());
        boolean found = false;
        for (var parameter : operation.path("parameters")) {
            if (parameter.path("name").asText().equals("Idempotency-Key")) { found = true; assertTrue(parameter.path("required").asBoolean()); }
        }
        assertTrue(found);
        var fulfillment = paths.path("/api/erp/orders/{kind}/{id}/fulfillments").path("post");
        assertTrue(fulfillment.path("responses").has("201"));
        assertTrue(fulfillment.path("requestBody").path("required").asBoolean());
    }

    /** 采购和销售分批推进状态，旧请求重放原快照，余额和历史批次只产生一次。
     * @throws Exception HTTP失败
     */
    @Test
    void fulfillsBothKindsInPartsAndReplaysHistoricalSnapshot() throws Exception {
        var fixture = fixture();
        for (var kind : TradeOrder.Kind.values()) {
            var order = create(fixture, kind, "1"); String path = fulfillmentPath(order); String key = key();
            String request = fulfillmentPayload(order, "0.4");
            var first = send("POST", path, key, request); assertEquals(201, first.statusCode(), first.body());
            var firstData = body(first).path("data"); assertEquals("PARTIAL", firstData.path("order").path("status").asText());
            assertEquals(path + "?postingId=" + firstData.path("postingId").asText(), first.headers().firstValue("Location").orElseThrow());
            var second = send("POST", path, key(), fulfillmentPayload(firstData.path("order"), "0.6"));
            assertEquals(201, second.statusCode(), second.body());
            assertEquals("COMPLETED", body(second).path("data").path("order").path("status").asText());
            var replay = send("POST", path, key, request); assertEquals(201, replay.statusCode()); assertEquals(firstData, body(replay).path("data"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id = ?", Integer.class, order.path("id").asText()));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement WHERE order_id = ?", Integer.class, order.path("id").asText()));
        }
        var stock = body(send("GET", "/api/erp/stock-balances?materialId=" + fixture.material(), null, null)).path("data");
        assertEquals(1, stock.path("total").asInt()); assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(stock.path("items").get(0).path("quantity").asText())));
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
    }

    /** 库存不足返回409并回滚订单、凭据、库存、事件和回执，收货后原键可成功重试。
     * @throws Exception HTTP失败
     */
    @Test
    void rollsBackInsufficientStockAndAllowsRetry() throws Exception {
        var fixture = fixture(); var sale = create(fixture, TradeOrder.Kind.SALE, "1");
        String request = fulfillmentPayload(sale, "1"); String requestKey = key();
        assertError(send("POST", fulfillmentPath(sale), requestKey, request), 409, 40900);
        assertEquals(0, jdbc.queryForObject("SELECT version FROM erp_trade_order WHERE id = ?", Long.class, sale.path("id").asText()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id = ?", Integer.class, sale.path("id").asText()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key = ?", Integer.class, requestKey));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
        var purchase = create(fixture, TradeOrder.Kind.PURCHASE, "1");
        assertEquals(201, send("POST", fulfillmentPath(purchase), key(), fulfillmentPayload(purchase, "1")).statusCode());
        var result = send("POST", fulfillmentPath(sale), requestKey, request);
        assertEquals(201, result.statusCode(), result.body());
        assertEquals("COMPLETED", body(result).path("data").path("order").path("status").asText());
    }

    /** 履约输入与业务冲突有明确区分，非法请求不会推进版本。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInvalidFulfillmentWithoutAdvancingOrder() throws Exception {
        var order = create(fixture(), TradeOrder.Kind.PURCHASE, "1"); String path = fulfillmentPath(order);
        String valid = fulfillmentPayload(order, "0.25"); String lineId = order.path("lines").get(0).path("id").asText();
        assertError(send("POST", path, null, valid), 400, 40000);
        for (String invalid : List.of("{}", valid.replace("\"version\":0,", ""), valid.replace("\"version\":0", "\"version\":-1"),
                valid.replace("0.25", "0"), valid.replace("0.25", "1e2147483647"), valid.replaceFirst("\\[.*]", "[null]"),
                valid.replace("]", "," + json.writeValueAsString(json.readTree(valid).path("lines").get(0)) + "]"))) {
            assertError(send("POST", path, key(), invalid), 400, 40000);
        }
        assertError(send("POST", path, key(), valid.replace(lineId, UUID.randomUUID().toString())), 409, 40900);
        assertError(send("POST", path, key(), valid.replace("0.25", "1.01")), 409, 40900);
        assertError(send("POST", path.replace("PURCHASE", "SALE"), key(), valid), 404, 40400);
        assertEquals(0, jdbc.queryForObject("SELECT version FROM erp_trade_order WHERE id = ?", Long.class, order.path("id").asText()));
        assertEquals(201, send("POST", path, key(), valid).statusCode());
        assertError(send("POST", path, key(), valid), 409, 40900);
    }

    /** 订单列表不加载明细，详情反映当前进度，履约Location读取固定历史快照。
     * @throws Exception HTTP失败
     */
    @Test
    void readsOrderDetailsAndFulfillmentLocations() throws Exception {
        var fixture = fixture(); var order = create(fixture, TradeOrder.Kind.PURCHASE, "1");
        String detailPath = "/api/erp/orders/PURCHASE/" + order.path("id").asText();
        var initial = send("GET", detailPath, null, null); assertEquals(200, initial.statusCode());
        assertEquals(0, body(initial).path("data").path("order").path("version").asLong());
        assertEquals(0, body(send("GET", fulfillmentPath(order), null, null)).path("data").path("total").asInt());
        var first = send("POST", fulfillmentPath(order), key(), fulfillmentPayload(order, "0.4"));
        assertEquals(201, first.statusCode(), first.body());
        var partial = body(first).path("data").path("order");
        assertEquals(201, send("POST", fulfillmentPath(order), key(), fulfillmentPayload(partial, "0.6")).statusCode());
        var current = body(send("GET", detailPath, null, null)).path("data");
        assertEquals("COMPLETED", current.path("order").path("status").asText());
        assertEquals(2, current.path("order").path("version").asLong());
        assertEquals(0, BigDecimal.ONE.compareTo(new BigDecimal(current.path("lines").get(0).path("fulfilled").asText())));
        assertEquals(order.path("lines").get(0).path("id").asText(), current.path("lines").get(0).path("id").asText());
        String createdAt = current.path("order").path("createdAt").asText();
        var listed = send("GET", "/api/erp/orders/PURCHASE?id=" + order.path("id").asText()
                + "&partyId=" + fixture.supplier() + "&warehouseId=" + fixture.warehouse() + "&status=COMPLETED"
                + "&totalAmountFrom=1&totalAmountTo=1&createdAtFrom=" + createdAt + "&createdAtTo=" + createdAt
                + "&sort=totalAmount,asc&sort=id,asc", null, null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, body(listed).path("data").path("total").asInt());
        assertEquals(current.path("order"), body(listed).path("data").path("items").get(0));
        assertFalse(body(listed).path("data").path("items").get(0).has("lines"));
        var historical = send("GET", first.headers().firstValue("Location").orElseThrow(), null, null);
        assertEquals(200, historical.statusCode(), historical.body());
        var history = body(historical).path("data"); assertEquals(1, history.path("total").asInt());
        assertEquals(1, history.path("items").get(0).path("orderVersion").asLong());
        assertEquals(0, new BigDecimal("0.4").compareTo(new BigDecimal(history.path("items").get(0).path("lines").get(0).path("quantity").asText())));
        var page = body(send("GET", fulfillmentPath(order) + "?orderVersionFrom=1&orderVersionTo=2&sort=orderVersion,asc&pageSize=1&page=2", null, null)).path("data");
        assertEquals(2, page.path("total").asInt()); assertEquals(2, page.path("items").get(0).path("orderVersion").asLong());
    }

    /** 订单与历史读取均阻止跨类别，非法状态、时间和版本参数返回400。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsMissingOrdersAndInvalidReadParameters() throws Exception {
        var order = create(fixture(), TradeOrder.Kind.SALE, "1");
        String path = "/api/erp/orders/PURCHASE/" + order.path("id").asText();
        assertError(send("GET", path, null, null), 404, 40400);
        assertError(send("GET", path + "/fulfillments", null, null), 404, 40400);
        assertError(send("GET", "/api/erp/orders/SALE/" + UUID.randomUUID() + "/fulfillments", null, null), 404, 40400);
        assertEquals(0, body(send("GET", "/api/erp/orders/PURCHASE?id=" + order.path("id").asText(), null, null)).path("data").path("total").asInt());
        for (String invalid : List.of("status=INVALID", "totalAmount=1e512", "totalAmountFrom=0", "sort=status,asc",
                "createdAt=2026-10-24T00:00:00.000000001Z", "unknown=x")) {
            assertError(send("GET", "/api/erp/orders/SALE?" + invalid, null, null), 400, 40000);
        }
        for (String invalid : List.of("orderVersion=1.5", "orderVersion=9223372036854775808", "orderVersionFrom=2&orderVersionTo=1",
                "orderVersionTo=1", "sort=quantity,asc", "createdAt=invalid")) {
            assertError(send("GET", fulfillmentPath(order) + "?" + invalid, null, null), 400, 40000);
        }
    }

    /** 读取端点的OpenAPI准确区分路径订单id与查询履约postingId。
     * @throws Exception HTTP失败
     */
    @Test
    void documentsOrderAndHistoryQueries() throws Exception {
        var response = send("GET", "/v3/api-docs", null, null); assertEquals(200, response.statusCode());
        var paths = json.readTree(response.body()).path("paths");
        for (String path : List.of("/api/erp/orders/{kind}", "/api/erp/orders/{kind}/{id}", "/api/erp/orders/{kind}/{id}/fulfillments")) {
            assertTrue(paths.path(path).path("get").path("responses").has("200"));
        }
        var parameters = paths.path("/api/erp/orders/{kind}/{id}/fulfillments").path("get").path("parameters");
        var identifiers = new HashSet<String>();
        for (var parameter : parameters) {
            if (Set.of("id", "postingId").contains(parameter.path("name").asText())) {
                identifiers.add(parameter.path("in").asText() + ":" + parameter.path("name").asText());
            }
        }
        assertEquals(Set.of("path:id", "query:postingId"), identifiers);
    }

    /** 累加余额超出存储上限是409冲突，整批履约及回执回滚而原余额保持不变。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsStockCapacityOverflowWithoutPartialPosting() throws Exception {
        var fixture = fixture(); String maximum = "99999999999999999999999999.999999999999";
        var first = send("POST", "/api/erp/orders/PURCHASE", key(), payload(fixture, TradeOrder.Kind.PURCHASE, maximum, "0", 1));
        assertEquals(201, first.statusCode(), first.body()); var order = body(first).path("data"); orderIds.add(order.path("id").asText());
        var receipt = send("POST", fulfillmentPath(order), key(), fulfillmentPayload(order, maximum));
        assertEquals(201, receipt.statusCode(), receipt.body());
        var second = create(fixture, TradeOrder.Kind.PURCHASE, "0.000000000001");
        String failedKey = key();
        assertError(send("POST", fulfillmentPath(second), failedKey, fulfillmentPayload(second, "0.000000000001")), 409, 40900);
        assertEquals(0, jdbc.queryForObject("SELECT version FROM erp_trade_order WHERE id = ?", Long.class, second.path("id").asText()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id = ?", Integer.class, second.path("id").asText()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement WHERE order_id = ?", Integer.class, second.path("id").asText()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key = ?", Integer.class, failedKey));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
        var result = body(send("GET", "/api/erp/stock-balances?materialId=" + fixture.material(), null, null)).path("data").path("items").get(0);
        assertEquals(0, new BigDecimal(maximum).compareTo(new BigDecimal(result.path("quantity").asText())));
        assertEquals(0, result.path("version").asLong());
    }

    /** 删除本测试订单、幂等键和主数据，不清空数据库。 */
    @AfterEach
    void clean() {
        unitOfWork.execute(() -> {
            jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", trace);
            for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key = ? AND scope LIKE ?", key, "trade:%:" + subjectId());
            for (String id : orderIds) jdbc.update("DELETE FROM erp_stock_movement WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id);
            for (String id : masterIds) jdbc.update("DELETE FROM erp_stock_balance WHERE material_id = ? OR warehouse_id = ?", id, id);
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 生成独立业务维度。
     * @return 四类主数据
     */
    private Fixture fixture() {
        return new Fixture(master(MasterData.Kind.MATERIAL), master(MasterData.Kind.WAREHOUSE), master(MasterData.Kind.SUPPLIER), master(MasterData.Kind.CUSTOMER));
    }

    /**
     * 保存本测试专属主数据。
     * @param kind 类别
     * @return UUID文本
     */
    private String master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "订单HTTP测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return value.getId();
    }

    /**
     * 创建并登记一行订单，供后续履约。
     * @param fixture 业务维度
     * @param kind 类别
     * @param quantity 订购数量
     * @return 已创建订单JSON
     * @throws Exception HTTP失败
     */
    private JsonNode create(Fixture fixture, TradeOrder.Kind kind, String quantity) throws Exception {
        var response = send("POST", "/api/erp/orders/" + kind, key(), payload(fixture, kind, quantity, "1", 1));
        assertEquals(201, response.statusCode(), response.body()); var order = body(response).path("data");
        orderIds.add(order.path("id").asText()); return order;
    }

    /**
     * 从订单快照定位履约资源。
     * @param order 订单JSON
     * @return 相对路径
     */
    private String fulfillmentPath(JsonNode order) {
        return "/api/erp/orders/" + order.path("kind").asText() + "/" + order.path("id").asText() + "/fulfillments";
    }

    /**
     * 构造首行履约的版本化请求。
     * @param order 当时订单快照
     * @param quantity 当次数量JSON数字
     * @return 请求JSON
     */
    private String fulfillmentPayload(JsonNode order, String quantity) {
        return "{\"version\":" + order.path("version").asLong() + ",\"lines\":[{\"orderLineId\":\""
                + order.path("lines").get(0).path("id").asText() + "\",\"quantity\":" + quantity + "}]}";
    }

    /**
     * 构造JSON数字输入，保留指数和精确小数供协议校验。
     * @param fixture 业务维度
     * @param kind 订单类别
     * @param quantity 数量JSON数字
     * @param price 单价JSON数字
     * @param count 明细行数
     * @return JSON请求体
     */
    private String payload(Fixture fixture, TradeOrder.Kind kind, String quantity, String price, int count) {
        String line = "{\"materialId\":\"" + fixture.material() + "\",\"quantity\":" + quantity + ",\"unitPrice\":" + price + "}";
        return "{\"partyId\":\"" + (kind == TradeOrder.Kind.PURCHASE ? fixture.supplier() : fixture.customer())
                + "\",\"warehouseId\":\"" + fixture.warehouse() + "\",\"lines\":[" + String.join(",", java.util.Collections.nCopies(count, line)) + "]}";
    }

    /**
     * 发送有界HTTP请求并关闭客户端。
     * @param method 方法
     * @param path 路径
     * @param key 幂等键或空
     * @param payload JSON或空
     * @return HTTP响应
     * @throws Exception 通信失败
     */
    private HttpResponse<String> send(String method, String path, String key, String payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken()).header("X-Trace-Id", trace).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow()); return response;
        }
    }

    /**
     * 读取标准响应并核对trace。
     * @param response HTTP响应
     * @return JSON对象
     */
    private JsonNode body(HttpResponse<String> response) {
        var value = json.readTree(response.body()); assertEquals(trace, value.path("traceId").asText()); return value;
    }

    /**
     * 核对错误码及明细结构。
     * @param response 响应
     * @param status 状态
     * @param code 错误码
     */
    private void assertError(HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, body(response).path("code").asInt());
        assertTrue(body(response).path("details").isArray());
    }

    /**
     * 生成并记录本测试幂等键。
     * @return UUID键
     */
    private String key() { String key = UUID.randomUUID().toString(); keys.add(key); return key; }
}
