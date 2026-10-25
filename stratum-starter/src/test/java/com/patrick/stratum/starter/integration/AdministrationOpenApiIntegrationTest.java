package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实HTTP生成文档核对管理接口模型，防止同名嵌套类型混淆和凭据字段误公开。 */
class AdministrationOpenApiIntegrationTest extends AuthenticatedHttpIntegrationTest {
    @LocalServerPort private int port;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 七种读取契约的数据字段必须与实际DTO一致，不能复用其他同名Row的模型。
     * @throws Exception HTTP失败
     */
    @Test
    void describesDistinctManagementReadModels() throws Exception {
        var document = document();
        assertAll(
                () -> fields(pageItem(document, "/api/auth/accounts"), Set.of("id", "username", "enabled", "version")),
                () -> fields(dataSchema(document, "/api/auth/accounts/{id}", "get", "200"), Set.of("id", "username", "enabled", "version")),
                () -> fields(pageItem(document, "/api/auth/roles"), Set.of("id", "code", "name", "version")),
                () -> fields(dataSchema(document, "/api/auth/roles/{id}", "get", "200"), Set.of("id", "code", "name", "version")),
                () -> fields(dataSchema(document, "/api/auth/accounts/{id}/roles", "get", "200"), Set.of("id", "version", "roleIds")),
                () -> fields(dataSchema(document, "/api/auth/roles/{id}/permissions", "get", "200"), Set.of("id", "version", "permissions")),
                () -> fields(pageItem(document, "/api/auth/permissions"), Set.of("code", "type", "name")));
        var grant = dataSchema(document, "/api/auth/roles/{id}/permissions", "get", "200").path("properties").path("permissions");
        fields(resolve(document, grant.path("items")), Set.of("code", "type"));
    }

    /**
     * 共享命名修正后，主数据、订单和库存的同名Row仍保留各自完整字段。
     * @throws Exception HTTP失败
     */
    @Test
    void preservesDistinctErpReadModels() throws Exception {
        var document = document();
        assertAll(
                () -> fields(pageItem(document, "/api/erp/masters/{kind}"), Set.of("id", "kind", "code", "name", "baseUnit", "version")),
                () -> fields(dataSchema(document, "/api/erp/masters/{kind}/{id}", "get", "200"), Set.of("id", "kind", "code", "name", "baseUnit", "version")),
                () -> fields(pageItem(document, "/api/erp/stock-balances"), Set.of("id", "materialId", "warehouseId", "quantity", "version")),
                () -> fields(pageItem(document, "/api/erp/stock-movements"), Set.of("id", "balanceId", "orderId", "postingId", "orderLineId", "direction", "quantity", "balanceAfter", "balanceVersion", "occurredAt")),
                () -> fields(pageItem(document, "/api/erp/orders/{kind}"), Set.of("id", "kind", "orderNumber", "partyId", "warehouseId", "totalAmount", "status", "createdAt", "version")),
                () -> fields(dataSchema(document, "/api/erp/orders/{kind}/{id}", "get", "200"), Set.of("order", "lines")),
                () -> fields(pageItem(document, "/api/erp/orders/{kind}/{id}/fulfillments"), Set.of("id", "orderId", "kind", "orderVersion", "createdAt", "lines")));
    }

    /**
     * 写请求模型只开放业务输入，密码为只写字符串，版本和完整关系集合必须显式提供。
     * @throws Exception HTTP失败
     */
    @Test
    void documentsRequiredVersionsAndWriteOnlyCredentials() throws Exception {
        var document = document();
        var account = input(document, "/api/auth/accounts", "post");
        fields(account, Set.of("username", "password")); required(account, "username", "password");
        assertEquals("string", account.path("properties").path("password").path("type").asText());
        assertTrue(account.path("properties").path("password").path("writeOnly").asBoolean());
        var status = input(document, "/api/auth/accounts/{id}/status", "patch");
        fields(status, Set.of("version", "enabled")); required(status, "version", "enabled");
        var memberships = input(document, "/api/auth/accounts/{id}/roles", "put");
        fields(memberships, Set.of("version", "roleIds")); required(memberships, "version", "roleIds");
        assertEquals(64, memberships.path("properties").path("roleIds").path("maxItems").asInt());
        fields(input(document, "/api/auth/roles", "post"), Set.of("code", "name"));
        var role = input(document, "/api/auth/roles/{id}", "put");
        fields(role, Set.of("version", "name", "permissions")); required(role, "version", "name", "permissions");
        assertEquals(1024, role.path("properties").path("permissions").path("maxItems").asInt());
        fields(resolve(document, role.path("properties").path("permissions").path("items")), Set.of("code", "type"));
        for (String path : List.of("/api/auth/accounts", "/api/auth/roles")) {
            fields(dataSchema(document, path, "post", "201"), Set.of("id", "version"));
        }
    }

    /**
     * 获取不经过业务响应包装的真实OpenAPI文档。
     * @return 生成文档
     * @throws Exception HTTP失败
     */
    private JsonNode document() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                    .timeout(Duration.ofSeconds(15)).header("X-Trace-Id", "management-openapi").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); return json.readTree(response.body());
        }
    }

    /**
     * 解析标准成功外壳内的数据模型。
     * @param document 完整文档
     * @param path 路由模板
     * @param method HTTP方法
     * @param status 成功状态
     * @return 已解析数据Schema
     */
    private JsonNode dataSchema(JsonNode document, String path, String method, String status) {
        var schema = document.path("paths").path(path).path(method).path("responses").path(status)
                .path("content").path("application/json").path("schema");
        return resolve(document, resolve(document, schema).path("properties").path("data"));
    }

    /**
     * 解析分页项类型，防止不同QueryResult泛型参数被同名模型覆盖。
     * @param document 完整文档
     * @param path 列表路由
     * @return 分页项Schema
     */
    private JsonNode pageItem(JsonNode document, String path) {
        return resolve(document, dataSchema(document, path, "get", "200").path("properties").path("items").path("items"));
    }

    /**
     * 读取JSON写入模型。
     * @param document 完整文档
     * @param path 写路由
     * @param method HTTP方法
     * @return 已解析输入Schema
     */
    private JsonNode input(JsonNode document, String path, String method) {
        return resolve(document, document.path("paths").path(path).path(method).path("requestBody")
                .path("content").path("application/json").path("schema"));
    }

    /**
     * 解析有限层本地模型引用，不跟随远端链接。
     * @param document 完整文档
     * @param schema 待解析模型
     * @return 已解析Schema
     */
    private JsonNode resolve(JsonNode document, JsonNode schema) {
        for (int depth = 0; schema.has("$ref") && depth < 10; depth++) {
            String reference = schema.path("$ref").asText(); assertTrue(reference.startsWith("#/components/schemas/"));
            schema = document.path("components").path("schemas").path(reference.substring("#/components/schemas/".length()));
        }
        assertFalse(schema.has("$ref")); return schema;
    }

    /**
     * 断言公开字段集合精确相等，不仅检查几个共有属性。
     * @param schema 已解析模型
     * @param expected 期望公开字段
     */
    private static void fields(JsonNode schema, Set<String> expected) {
        assertEquals(expected, schema.path("properties").properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet()));
    }

    /**
     * 核对必须显式提交的字段。
     * @param schema 输入模型
     * @param names 必填字段
     */
    private static void required(JsonNode schema, String... names) {
        var actual = new java.util.HashSet<String>(); schema.path("required").forEach(item -> actual.add(item.asText()));
        assertTrue(actual.containsAll(List.of(names)));
    }
}
