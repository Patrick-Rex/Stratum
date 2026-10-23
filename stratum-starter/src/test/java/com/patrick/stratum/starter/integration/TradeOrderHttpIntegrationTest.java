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
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP与数据库验证采购销售写协议、精确金额、幂等及输入边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TradeOrderHttpIntegrationTest extends MariaDbIntegrationTest {
    @LocalServerPort private int port;
    private final JdbcTemplate jdbc;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JsonMapper json = JsonMapper.builder().build();
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
                    key, "trade:create:v1:" + kind + ":local-erp"));
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
        var operation = json.readTree(response.body()).path("paths").path("/api/erp/orders/{kind}").path("post");
        assertTrue(operation.path("responses").has("201"));
        assertTrue(operation.path("requestBody").path("required").asBoolean());
        boolean found = false;
        for (var parameter : operation.path("parameters")) {
            if (parameter.path("name").asText().equals("Idempotency-Key")) { found = true; assertTrue(parameter.path("required").asBoolean()); }
        }
        assertTrue(found);
    }

    /** 删除本测试订单、幂等键和主数据，不清空数据库。 */
    @AfterEach
    void clean() {
        unitOfWork.execute(() -> {
            jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", trace);
            for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key = ? AND scope LIKE 'trade:%:local-erp'", key);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id);
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
                .header("X-Trace-Id", trace).header("Content-Type", "application/json");
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
