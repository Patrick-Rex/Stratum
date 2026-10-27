package com.patrick.stratum.starter.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 使用专用数据库和真实登录验证原事件恢复HTTP协议与管理员授权。 */
class OutboxRecoveryHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private UnitOfWork work;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxConsumer consumer;
    private final List<String> events = new ArrayList<>();
    private final List<String> aggregates = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();

    /** 仅清理本测试创建的请求、事件和聚合。 */
    @AfterEach
    void cleanRecoveryRecords() {
        for (String id : requests) jdbc.update("DELETE FROM outbox_original_recovery_request WHERE request_id=?", id);
        for (String id : events) jdbc.update("DELETE FROM outbox_consumption WHERE event_id=?", id);
        for (String id : events) jdbc.update("DELETE FROM outbox_event WHERE event_id=?", id);
        for (String id : aggregates) jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id=?", id);
    }

    /**
     * 管理员可恢复原事件，重复键返回首次回执，不能跨事件复用。
     * @throws Exception HTTP通信失败
     */
    @Test
    void administratorRecoversAndReplaysStableReceipt() throws Exception {
        var event = failedEvent(); var key = requestId();
        var first = recovery(event, key, accessToken(), "1");
        var result = data(first, 200);
        assertEquals(event.toString(), result.path("eventId").asText());
        assertEquals(key.toString(), result.path("requestId").asText());
        assertEquals("RECOVERED", result.path("outcome").asText());
        assertEquals(3, result.path("previousFailures").asInt());
        assertTrue(first.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("PENDING", status(event));
        assertEquals(3, jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE event_id=?", Integer.class, event.toString()));
        assertEquals(subjectId(), jdbc.queryForObject("SELECT operator_id FROM outbox_original_recovery_request WHERE request_id=?", String.class, key.toString()));
        assertEquals(result, data(recovery(event, key, accessToken(), "1"), 200));
        var other = failedEvent();
        assertEquals(409, recovery(other, key, accessToken(), "1").statusCode());
        assertEquals("FAILED", status(other));
    }

    /**
     * 匿名、Refresh与普通账号不能恢复；旧请求在管理员撤权后不绕过当前认证。
     * @throws Exception HTTP通信失败
     */
    @Test
    void rejectsAnonymousRefreshOrdinaryAndRevokedAdministrator() throws Exception {
        var event = failedEvent(); var key = requestId();
        String username = "recover-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); var ordinary = login(username, password);
        assertEquals(401, recovery(event, key, null, "1").statusCode());
        assertEquals(401, recovery(event, key, refreshToken(), "1").statusCode());
        assertEquals(403, recovery(event, key, ordinary.accessToken(), "1").statusCode());
        assertEquals("FAILED", status(event)); assertEquals(0, requestCount(key));
        String adminRole = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        assertEquals(200, request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(adminRole))).statusCode());
        var promoted = login(username, password);
        assertEquals(200, recovery(event, key, promoted.accessToken(), "1").statusCode());
        assertEquals(200, request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 1, "roleIds", List.of())).statusCode());
        assertEquals(401, recovery(event, key, promoted.accessToken(), "1").statusCode());
        var demoted = login(username, password);
        assertEquals(403, recovery(event, key, demoted.accessToken(), "1").statusCode());
        assertEquals(1, requestCount(key));
    }

    /**
     * 真实消费凭据支持已消费回执，不把该结果说成重新发送。
     * @throws Exception HTTP通信失败
     */
    @Test
    void reportsAlreadyConsumedWithoutSchedulingAnotherDelivery() throws Exception {
        var event = failedEvent(); var key = requestId();
        var message = new OutboxMessage(event.toString(), "erp.probe.created", aggregates.getLast(),
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{}", "original-trace");
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(new Delivery(message, 1), Group.ERP_AUDIT));
        var result = data(recovery(event, key, accessToken(), "1"), 200);
        assertEquals("ALREADY_CONSUMED", result.path("outcome").asText());
        assertTrue(result.path("previousFailures").isNull());
        assertEquals("CONSUMED", status(event));
        assertEquals(result, data(recovery(event, key, accessToken(), "1"), 200));
    }

    /**
     * 非法身份键、缺失资源和未知版本不能改变事件或写入回执。
     * @throws Exception HTTP通信失败
     */
    @Test
    void validatesProtocolAndDocumentsTheEndpoint() throws Exception {
        var event = failedEvent(); var key = requestId();
        assertEquals(400, recovery(event, null, accessToken(), "1").statusCode());
        assertEquals(400, raw(event, "not-a-uuid", accessToken(), "1").statusCode());
        assertEquals(400, recovery(event, key, accessToken(), "2").statusCode());
        assertEquals(404, recovery(UUID.randomUUID(), key, accessToken(), "1").statusCode());
        assertEquals("FAILED", status(event)); assertEquals(0, requestCount(key));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(endpoint("/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var operation = json.readTree(response.body()).path("paths").path("/api/management/outbox/events/{eventId}/recoveries").path("post");
            assertTrue(operation.isObject());
            assertTrue(operation.path("parameters").toString().contains("Idempotency-Key"));
        }
    }

    /**
     * 同事务建立随机原事件，再在专用测试库模拟失败状态。
     * @return 本用例事件UUID
     */
    private UUID failedEvent() {
        UUID event = UUID.randomUUID(), aggregate = UUID.randomUUID();
        events.add(event.toString()); aggregates.add(aggregate.toString());
        var message = new OutboxMessage(event.toString(), "erp.probe.created", aggregate.toString(),
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{}", "original-trace");
        work.execute(() -> { outbox.append(message); return null; });
        jdbc.update("UPDATE outbox_event SET status='FAILED', retry_count=3 WHERE event_id=?", event.toString());
        return event;
    }

    /**
     * 分配本用例的请求UUID并记录清理范围。
     * @return 新UUID
     */
    private UUID requestId() { var id = UUID.randomUUID(); requests.add(id.toString()); return id; }

    /**
     * 发送真实恢复请求。
     * @param event 原事件UUID
     * @param key 可空请求UUID
     * @param token 可空Bearer
     * @param version 接口版本
     * @return HTTP响应
     * @throws Exception HTTP通信失败
     */
    private HttpResponse<String> recovery(UUID event, UUID key, String token, String version) throws Exception {
        return raw(event, key == null ? null : key.toString(), token, version);
    }

    /**
     * 发送带指定幂等头的请求，包含伪造身份头以验证不能改变主体。
     * @param event 原事件UUID
     * @param key 可空幂等头
     * @param token 可空Bearer
     * @param version 接口版本
     * @return HTTP响应
     * @throws Exception HTTP通信失败
     */
    private HttpResponse<String> raw(UUID event, String key, String token, String version) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint("/api/management/outbox/events/" + event + "/recoveries"))
                .timeout(Duration.ofSeconds(15)).header("X-API-Version", version).header("X-Trace-Id", "recovery-http")
                .header("X-User-Id", subjectId()).POST(HttpRequest.BodyPublishers.noBody());
        if (key != null) builder.header("Idempotency-Key", key);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 查询本测试原事件状态。
     * @param event 原事件UUID
     * @return 存储状态
     */
    private String status(UUID event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, event.toString()); }

    /**
     * 查询本测试请求回执数。
     * @param id 请求UUID
     * @return 回执数
     */
    private int requestCount(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_original_recovery_request WHERE request_id=?", Integer.class, id.toString()); }
}
