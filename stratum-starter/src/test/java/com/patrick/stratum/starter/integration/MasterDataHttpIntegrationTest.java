package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.patrick.stratum.application.masterdata.MasterDataApplicationService;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.domain.masterdata.MasterData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实HTTP、命令事务与MariaDB/Redis验证主数据写协议。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MasterDataHttpIntegrationTest extends MariaDbIntegrationTest {
    @LocalServerPort private int port;
    private final JdbcTemplate jdbc;
    private final VersionedCache cache;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String trace = UUID.randomUUID().toString();
    private final Set<String> ids = new HashSet<>();
    private final Set<String> keys = new HashSet<>();

    /**
     * 注入测试清理依赖，不在控制器中访问持久化设施。
     * @param jdbc 独立断言与清理客户端
     * @param cache 版本缓存
     * @param redis 测试缓存清理客户端
     */
    @Autowired
    MasterDataHttpIntegrationTest(JdbcTemplate jdbc, VersionedCache cache, StringRedisTemplate redis) {
        this.jdbc = jdbc; this.cache = cache; this.redis = redis;
    }

    /** 四类创建、改名、删除可重放，固定主体且Location/trace/空响应符合协议。
     * @throws Exception HTTP失败
     */
    @Test
    void writesAndReplaysAllMasterKinds() throws Exception {
        for (var kind : MasterData.Kind.values()) {
            String path = "/api/erp/masters/" + kind;
            String payload = payload(kind, "原名称"); String createKey = key();
            var created = send("POST", path, createKey, payload);
            assertEquals(201, created.statusCode(), created.body());
            var data = body(created).path("data"); String id = data.path("id").asText(); ids.add(id);
            assertEquals(path + "/" + id, created.headers().firstValue("Location").orElseThrow());
            assertEquals(data, body(send("POST", path, createKey, payload)).path("data"));
            assertEquals("master:create:v1:" + kind + ":local-erp", jdbc.queryForObject(
                    "SELECT scope FROM idempotency_receipt WHERE request_key = ?", String.class, createKey));
            String renameKey = key(); String rename = "{\"name\":\"新名称\",\"version\":0}";
            var renamed = send("PATCH", path + "/" + id, renameKey, rename);
            assertEquals(200, renamed.statusCode(), renamed.body());
            assertEquals("新名称", body(renamed).path("data").path("name").asText());
            assertEquals(1, body(renamed).path("data").path("version").asLong());
            assertEquals(body(renamed).path("data"), body(send("PATCH", path + "/" + id, renameKey, rename)).path("data"));
            assertError(send("PATCH", path + "/" + id, key(), rename), 409, 40900);
            String deleteKey = key();
            for (int replay = 0; replay < 2; replay++) {
                var deleted = send("DELETE", path + "/" + id + "?version=1", deleteKey, null);
                assertEquals(204, deleted.statusCode(), deleted.body()); assertEquals("", deleted.body());
            }
            assertError(send("PATCH", path + "/" + id, key(), rename), 404, 40400);
        }
    }

    /** 拒绝缺失/非法幂等头、类别、版本、文本、单位和JSON，响应不泄漏输入。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsInvalidProtocolAndDomainInputs() throws Exception {
        String path = "/api/erp/masters/MATERIAL";
        assertError(send("POST", path, null, payload(MasterData.Kind.MATERIAL, "名称")), 400, 40000);
        assertError(send("POST", path, "invalid/key", payload(MasterData.Kind.MATERIAL, "名称")), 400, 40000);
        assertError(send("POST", "/api/erp/masters/INVALID", key(), "{}"), 400, 40000);
        for (String invalid : List.of("{}", "{", "{\"code\":\"A\",\"name\":\"B\"}",
                "{\"code\":\"A\",\"name\":\"B\",\"baseUnit\":\"\"}",
                "{\"code\":\"A\",\"name\":\"B\\u0000secret\",\"baseUnit\":\"个\"}")) {
            var response = send("POST", path, key(), invalid);
            assertError(response, 400, 40000); assertFalse(response.body().contains("secret"));
        }
        assertError(send("POST", "/api/erp/masters/CUSTOMER", key(), payload(MasterData.Kind.MATERIAL, "名称")), 400, 40000);
        String missing = path + "/" + UUID.randomUUID();
        assertError(send("PATCH", missing, key(), "{\"name\":\"名称\"}"), 400, 40000);
        assertError(send("PATCH", missing, key(), "{\"name\":\"名称\",\"version\":-1}"), 400, 40000);
        assertError(send("DELETE", missing, key(), null), 400, 40000);
        assertError(send("DELETE", missing + "?version=-1", key(), null), 400, 40000);
        assertError(send("DELETE", path + "/invalid?version=0", key(), null), 400, 40000);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
    }

    /** 同键不同请求冲突，同编码唯一约束和跨类别操作映射明确错误。
     * @throws Exception HTTP失败
     */
    @Test
    void preservesConflictsAndKindIsolation() throws Exception {
        String path = "/api/erp/masters/MATERIAL"; String payload = payload(MasterData.Kind.MATERIAL, "原名称"); String key = key();
        var created = send("POST", path, key, payload); assertEquals(201, created.statusCode());
        String id = body(created).path("data").path("id").asText(); ids.add(id);
        assertError(send("POST", path, key, payload.replace("原名称", "新名称")), 409, 40900);
        assertError(send("POST", path, key(), payload), 409, 40900);
        assertError(send("DELETE", "/api/erp/masters/CUSTOMER/" + id + "?version=0", key(), null), 404, 40400);
        jdbc.update("INSERT INTO stratum_it_master_data_reference (id, master_data_id) VALUES (?, ?)", UUID.randomUUID().toString(), id);
        assertError(send("DELETE", path + "/" + id + "?version=0", key(), null), 409, 40900);
    }

    /** 新端点的OpenAPI保留幂等头、创建状态和空删除响应。
     * @throws Exception HTTP失败
     */
    @Test
    void documentsWriteProtocol() throws Exception {
        var response = send("GET", "/v3/api-docs", null, null);
        assertEquals(200, response.statusCode());
        var document = json.readTree(response.body());
        var post = document.path("paths").path("/api/erp/masters/{kind}").path("post");
        assertTrue(post.path("responses").has("201"));
        assertTrue(post.path("requestBody").path("required").asBoolean());
        boolean keyDocumented = false;
        for (var parameter : post.path("parameters")) {
            if (parameter.path("name").asText().equals("Idempotency-Key")) {
                keyDocumented = true; assertTrue(parameter.path("required").asBoolean());
            }
        }
        assertTrue(keyDocumented);
        var deleted = document.path("paths").path("/api/erp/masters/{kind}/{id}").path("delete");
        assertTrue(deleted.path("responses").has("204"));
        assertFalse(deleted.path("responses").path("204").has("content"));
    }

    /** 只清理本测试的随机键、主数据、事件与缓存。 */
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", trace);
        for (String key : keys) jdbc.update("DELETE FROM idempotency_receipt WHERE request_key = ? AND scope LIKE 'master:%:local-erp'", key);
        for (String id : ids) {
            jdbc.update("DELETE FROM stratum_it_master_data_reference WHERE master_data_id = ?", id);
            jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            String cacheKey = MasterDataApplicationService.cacheKey(id);
            cache.invalidate(cacheKey, 100, MasterDataApplicationService.CACHE_POLICY);
            redis.delete(List.of(cacheKey, "null:" + cacheKey.substring(4), "version:" + cacheKey.substring(4)));
        }
    }

    /**
     * 发送有界HTTP请求并检查关联头，关闭本次客户端。
     * @param method 方法
     * @param path 路径
     * @param key 幂等键，空表示省略
     * @param payload JSON，空表示无请求体
     * @return 原始HTTP响应
     * @throws Exception 通信失败
     */
    private HttpResponse<String> send(String method, String path, String key, String payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("X-Trace-Id", trace).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        try (var client = HttpClient.newHttpClient()) {
            var result = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(trace, result.headers().firstValue("X-Trace-Id").orElseThrow()); return result;
        }
    }

    /**
     * 读取统一响应并核对关联标识。
     * @param response 原始响应
     * @return JSON对象
     */
    private JsonNode body(HttpResponse<String> response) {
        var value = json.readTree(response.body()); assertEquals(trace, value.path("traceId").asText()); return value;
    }

    /**
     * 核对标准错误。
     * @param response 响应
     * @param status HTTP状态
     * @param code 业务码
     */
    private void assertError(HttpResponse<String> response, int status, int code) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, body(response).path("code").asInt());
        assertTrue(body(response).path("details").isArray());
    }

    /**
     * 创建隔离的输入JSON。
     * @param kind 类别
     * @param name 名称
     * @return JSON文本
     */
    private String payload(MasterData.Kind kind, String name) {
        return "{\"code\":\"" + UUID.randomUUID() + "\",\"name\":\"" + name + "\""
                + (kind == MasterData.Kind.MATERIAL ? ",\"baseUnit\":\"个\"" : "") + "}";
    }

    /**
     * 记录本测试独占的随机幂等键。
     * @return 随机键
     */
    private String key() { String value = UUID.randomUUID().toString(); keys.add(value); return value; }
}
