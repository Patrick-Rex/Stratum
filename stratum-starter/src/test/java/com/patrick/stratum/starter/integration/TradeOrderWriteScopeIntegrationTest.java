package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.Permission;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.*;

/** 仓库范围写HTTP夹具：固定真实账号、独立幂等键与trace，并精确清理自身新订单和事件回执。 */
abstract class TradeOrderWriteScopeIntegrationTest extends TradeOrderScopeIntegrationTest {
    @LocalServerPort private int port;
    protected final String trace = UUID.randomUUID().toString();
    private final Set<String> keys = new HashSet<>();
    private final Set<String> createdOrders = new HashSet<>();
    private long roleVersion = 1;

    /** 在原订单夹具清理前移除本用例HTTP库存效果、新增订单、事件和回执。 */
    @AfterEach
    void cleanWrites() {
        for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key=?", key);
        jdbc.update("DELETE FROM outbox_event WHERE trace_id=?", trace);
        for (var values : fixtures.values()) for (var order : values) {
            jdbc.update("DELETE FROM erp_stock_movement WHERE order_id=?", order.getId().toString());
        }
        for (UUID warehouse : java.util.List.of(first, second)) jdbc.update("DELETE FROM erp_stock_balance WHERE warehouse_id=?", warehouse.toString());
        for (String id : createdOrders) {
            jdbc.update("DELETE FROM erp_order_line WHERE order_id=?", id);
            jdbc.update("DELETE FROM erp_trade_order WHERE id=?", id);
        }
    }

    /**
     * 只更换本测试角色API权限，不更换普通账号会话或仓库范围。
     * @param permissions 完整权限集合
     * @throws Exception HTTP失败
     */
    protected void grant(Set<Permission> permissions) throws Exception {
        roleVersion = data(request("PUT", "/api/auth/roles/" + role, accessToken(),
                Map.of("version", roleVersion, "name", "订单范围", "permissions", permissions)), 200).path("version").asLong();
    }

    /**
     * 统计本测试链路事件。
     * @return 事件数
     */
    protected int events() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id=?", Integer.class, trace); }

    /**
     * 统计本测试随机键回执。
     * @param key 幂等键
     * @return 回执数
     */
    protected int receipts(String key) { return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE request_key=?", Integer.class, key); }

    /**
     * 发送有限时长写请求，伪造管理员头不能替代真实Bearer；登记仅本测试创建的资源。
     * @param path 路径
     * @param access 可空Access
     * @param input 请求体
     * @param key 随机幂等键
     * @return 原始响应
     * @throws Exception HTTP失败
     */
    protected HttpResponse<String> write(String path, String access, Object input, String key) throws Exception {
        keys.add(key);
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Trace-Id", trace).header("X-User-Id", subjectId())
                .header("Idempotency-Key", key);
        if (access != null) builder.header("Authorization", "Bearer " + access);
        builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); var body = json.readTree(response.body());
            assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow()); assertEquals(trace, body.path("traceId").asText());
            if (response.statusCode() == 201 && !path.contains("/fulfillments")) createdOrders.add(body.path("data").path("id").asText());
            if (response.statusCode() >= 400) assertTrue(body.path("data").isNull());
            return response;
        }
    }
}
