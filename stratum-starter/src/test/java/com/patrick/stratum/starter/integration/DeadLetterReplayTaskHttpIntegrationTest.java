package com.patrick.stratum.starter.integration;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立数据库、真实登录和HTTP验证任务协议及管理员授权。 */
class DeadLetterReplayTaskHttpIntegrationTest extends AdministrationHttpIntegrationTest {
    private static final String PATH = "/api/management/outbox/replay-tasks";
    @Autowired private UnitOfWork work;
    @Autowired private JobExecutionRepository executions;
    private final List<UUID> keys = new ArrayList<>();

    /** 仅清理本测试登记的任务及其尝试。 */
    @AfterEach
    void cleanTasks() {
        for (var key : keys) {
            var tasks = jdbc.queryForList("SELECT task_id FROM dead_letter_replay_task WHERE request_id=? AND task_id IS NOT NULL", String.class, key.toString());
            jdbc.update("DELETE FROM dead_letter_replay_task WHERE request_id=?", key.toString());
            for (var task : tasks) {
                jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", task);
                jdbc.update("DELETE FROM dead_job WHERE execution_id=?", task);
                jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", task);
                jdbc.update("DELETE FROM job_execution WHERE execution_id=?", task);
            }
        }
    }

    /**
     * 登记、重复登记、查询及重复取消保持同一任务和安全状态。
     * @throws Exception HTTP通信失败
     */
    @Test
    void registersQueriesAndCancelsIdempotently() throws Exception {
        var key = key(); var archive = UUID.randomUUID();
        var created = register(key.toString(), archive.toString(), accessToken(), "1");
        var task = UUID.fromString(data(created, 202).path("taskId").asText());
        assertEquals(PATH + "/" + task, created.headers().firstValue("Location").orElseThrow());
        assertNoCache(created);
        assertEquals(subjectId(), jdbc.queryForObject("SELECT operator_id FROM dead_letter_replay_task WHERE request_id=?", String.class, key.toString()));
        assertEquals(task.toString(), data(register(key.toString(), archive.toString(), accessToken(), "1"), 202).path("taskId").asText());
        assertEquals(409, register(key.toString(), UUID.randomUUID().toString(), accessToken(), "1").statusCode());
        var pending = request("GET", PATH + "/" + task, accessToken(), null);
        assertNoCache(pending);
        var state = data(pending, 200);
        assertEquals("PENDING", state.path("status").asText()); assertEquals(0, state.path("progress").asInt());
        assertTrue(state.path("lastError").isNull()); assertEquals("replay-http", state.path("traceId").asText());
        assertEquals(5, state.size());
        var cancelled = request("PUT", PATH + "/" + task + "/cancellation", accessToken(), null);
        assertNoCache(cancelled); assertEquals(task.toString(), data(cancelled, 200).path("taskId").asText());
        assertEquals(task.toString(), data(request("PUT", PATH + "/" + task + "/cancellation", accessToken(), null), 200).path("taskId").asText());
        assertEquals("CANCELLED", data(request("GET", PATH + "/" + task, accessToken(), null), 200).path("status").asText());
        assertEquals(task.toString(), data(register(key.toString(), archive.toString(), accessToken(), "1"), 202).path("taskId").asText());
    }

    /**
     * 三个操作均拒绝匿名、Refresh和普通账号，撤权后不能复用旧任务。
     * @throws Exception HTTP通信失败
     */
    @Test
    void enforcesCurrentAdministratorForEveryOperation() throws Exception {
        var key = key(); var archive = UUID.randomUUID();
        var task = UUID.fromString(data(register(key.toString(), archive.toString(), accessToken(), "1"), 202).path("taskId").asText());
        String username = "replay-http-" + UUID.randomUUID(); String password = UUID.randomUUID().toString();
        UUID user = createAccount(username, password); var ordinary = login(username, password);
        for (String token : new String[] { null, refreshToken(), ordinary.accessToken() }) {
            int expected = token == null || token.equals(refreshToken()) ? 401 : 403;
            assertEquals(expected, register(key.toString(), archive.toString(), token, "1").statusCode());
            assertEquals(expected, request("GET", PATH + "/" + task, token, null).statusCode());
            assertEquals(expected, request("PUT", PATH + "/" + task + "/cancellation", token, null).statusCode());
        }
        String role = jdbc.queryForObject("SELECT id FROM auth_role WHERE code='administrator'", String.class);
        assertEquals(200, request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", List.of(role))).statusCode());
        var promoted = login(username, password);
        assertEquals(200, request("GET", PATH + "/" + task, promoted.accessToken(), null).statusCode());
        assertEquals(200, request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 1, "roleIds", List.of())).statusCode());
        assertEquals(401, request("GET", PATH + "/" + task, promoted.accessToken(), null).statusCode());
        var demoted = login(username, password);
        assertEquals(403, request("GET", PATH + "/" + task, demoted.accessToken(), null).statusCode());
        assertEquals(403, request("PUT", PATH + "/" + task + "/cancellation", demoted.accessToken(), null).statusCode());
        assertEquals(403, register(key.toString(), archive.toString(), demoted.accessToken(), "1").statusCode());
    }

    /**
     * 非法请求和已开始任务不会被取消，OpenAPI记录地址及协议。
     * @throws Exception HTTP通信失败
     */
    @Test
    void validatesProtocolAndDocumentsEndpoints() throws Exception {
        var key = key(); var archive = UUID.randomUUID();
        assertEquals(400, register(null, archive.toString(), accessToken(), "1").statusCode());
        assertEquals(400, register("invalid", archive.toString(), accessToken(), "1").statusCode());
        assertEquals(400, register(key.toString(), "invalid", accessToken(), "1").statusCode());
        assertEquals(400, register(key.toString(), archive.toString(), accessToken(), "2").statusCode());
        assertEquals(404, request("GET", PATH + "/" + UUID.randomUUID(), accessToken(), null).statusCode());
        assertEquals(404, request("PUT", PATH + "/" + UUID.randomUUID() + "/cancellation", accessToken(), null).statusCode());
        var task = UUID.fromString(data(register(key.toString(), archive.toString(), accessToken(), "1"), 202).path("taskId").asText());
        assertTrue(work.execute(() -> executions.claim(task)).isPresent());
        assertEquals("RUNNING", data(request("GET", PATH + "/" + task, accessToken(), null), 200).path("status").asText());
        assertEquals(409, request("PUT", PATH + "/" + task + "/cancellation", accessToken(), null).statusCode());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(endpoint("/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var paths = json.readTree(response.body()).path("paths");
            var post = paths.path(PATH).path("post");
            assertTrue(post.isObject()); assertTrue(post.path("parameters").toString().contains("Idempotency-Key"));
            assertTrue(post.path("responses").path("202").path("headers").has("Location"));
            assertTrue(paths.path(PATH + "/{taskId}").path("get").path("responses").has("403"));
            assertTrue(paths.path(PATH + "/{taskId}/cancellation").path("put").path("responses").has("409"));
        }
    }

    /**
     * 分配本用例请求身份并登记清理范围。
     * @return 唯一请求UUID
     */
    private UUID key() { var id = UUID.randomUUID(); keys.add(id); return id; }

    /**
     * 使用真实网络发送登记请求，伪造身份头不能改变认证主体。
     * @param key 可空幂等头
     * @param archive 目标归档文本
     * @param token 可空Bearer
     * @param version 接口版本
     * @return HTTP响应
     * @throws Exception HTTP通信失败
     */
    private HttpResponse<String> register(String key, String archive, String token, String version) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint(PATH)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-API-Version", version)
                .header("X-Trace-Id", "replay-http").header("X-User-Id", subjectId())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("archiveId", archive))));
        if (key != null) builder.header("Idempotency-Key", key);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 验证管理响应禁止缓存。
     * @param response 原始响应
     */
    private void assertNoCache(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
    }
}
