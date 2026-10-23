package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.List;

import com.patrick.stratum.interfaceadapter.fixture.DocumentationProbeController;
import com.patrick.stratum.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实HTTP服务器验证OpenAPI与Knife4j静态展示入口的装配。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DocumentationProbeController.class)
class ApiDocumentationIntegrationTest extends MariaDbIntegrationTest {

    @LocalServerPort
    private int port;

    private final JsonMapper json = JsonMapper.builder().build();

    /** 文档JSON保持原始OpenAPI协议并携带响应关联头。
     * @throws Exception HTTP通信失败
     */
    @Test
    void servesOpenApiJsonWithoutBusinessEnvelope() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, "/v3/api-docs");
            assertEquals(200, response.statusCode());
            var document = json.readTree(response.body());
            assertTrue(document.path("openapi").asString().startsWith("3.0."));
            assertTrue(document.path("paths").isObject());
            assertFalse(document.has("code"));
            assertFalse(document.has("data"));
            assertFalse(document.path("paths").has("/validation/body"));
            assertEquals("docs-test", response.headers().firstValue("X-Trace-Id").orElseThrow());
        }
    }

    /** Knife4j使用的UI发现配置指向本应用生成文档。
     * @throws Exception HTTP通信失败
     */
    @Test
    void exposesUiDiscoveryConfiguration() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, "/v3/api-docs/swagger-config");
            assertEquals(200, response.statusCode());
            var configuration = json.readTree(response.body());
            assertFalse(configuration.has("code"));
            assertEquals("/v3/api-docs", configuration.path("urls").get(0).path("url").asString());
            assertEquals("Stratum", configuration.path("urls").get(0).path("name").asString());
        }
    }

    /** 页面及其实际引用的入口脚本可以加载，未被统一响应包装。
     * @throws Exception HTTP通信失败
     */
    @Test
    void servesKnife4jPageAndScripts() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, "/doc.html");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<html"));
            var scripts = Pattern.compile("<script[^>]*src=[\"']?([^\"' >]+)").matcher(response.body());
            int checked = 0;
            while (scripts.find()) {
                String path = scripts.group(1);
                if (!path.startsWith("/")) path = "/" + path;
                assertTrue(path.startsWith("/webjars/"), "页面脚本应为本地WebJar资源");
                var script = get(client, path);
                assertEquals(200, script.statusCode(), path);
                assertTrue(script.body().length() > 100, path);
                checked++;
            }
            assertTrue(checked > 0);
        }
    }

    /** 文档的数据模型、标准外壳和实际业务响应一致。
     * @throws Exception HTTP通信失败
     */
    @Test
    void documentsTheActualResponseEnvelope() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            JsonNode document = json.readTree(get(client, "/v3/api-docs").body());
            assertEquals("Stratum ERP API", document.path("info").path("title").asString());
            for (String suffix : List.of("object", "wrapped", "string", "empty", "created")) {
                String path = "/documentation-probes/" + suffix;
                var actual = get(client, path);
                int expectedStatus = suffix.equals("created") ? 201 : 200;
                assertEquals(expectedStatus, actual.statusCode());
                JsonNode result = json.readTree(actual.body());
                assertEquals(0, result.path("code").asInt());
                assertEquals("docs-test", result.path("traceId").asString());
                JsonNode operation = document.path("paths").path(path).path("get");
                JsonNode schema = resolveSchema(document, operation.path("responses")
                        .path(String.valueOf(expectedStatus)).path("content").path("application/json").path("schema"));
                for (String field : List.of("code", "message", "data", "details", "traceId")) {
                    assertTrue(schema.path("properties").has(field), path + "缺少" + field + ": " + schema);
                }
                if (List.of("object", "wrapped", "created").contains(suffix)) {
                    var dataSchema = resolveSchema(document, schema.path("properties").path("data"));
                    assertTrue(dataSchema.path("properties").has("name"));
                    assertFalse(dataSchema.path("properties").has("code"));
                    assertTrue(result.path("data").has("name"));
                }
                if (suffix.equals("empty")) assertTrue(result.path("data").isNull());
                if (suffix.equals("string")) assertTrue(result.path("data").isString());
            }
        }
    }

    /** 版本与错误文档保留标准结构，下载仍保持二进制语义。
     * @throws Exception HTTP通信失败
     */
    @Test
    void documentsVersionErrorsAndPreservesDownloads() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            JsonNode document = json.readTree(get(client, "/v3/api-docs").body());
            JsonNode operation = document.path("paths").path("/documentation-probes/object").path("get");
            boolean versionFound = false;
            for (JsonNode parameter : operation.path("parameters")) {
                if (parameter.path("name").asString().equals("X-API-Version")) {
                    versionFound = true;
                    assertFalse(parameter.path("required").asBoolean());
                }
            }
            assertTrue(versionFound);
            for (var code : List.of(CommonErrorCode.BAD_REQUEST, CommonErrorCode.NOT_FOUND, CommonErrorCode.CONFLICT, CommonErrorCode.INTERNAL_ERROR)) {
                String status = Integer.toString(code.getCode() / 100);
                var error = operation.path("responses").path(status);
                assertTrue(error.path("headers").has("X-Trace-Id"));
                JsonNode reference = error.path("content").path("application/json").path("schema");
                assertTrue(reference.has("$ref"));
                var properties = resolveSchema(document, reference).path("properties");
                assertTrue(properties.has("details"));
                assertEquals(code.getCode(), properties.path("code").path("example").asInt());
                assertEquals(code.getDefaultMessage(),
                        properties.path("message").path("example").asString());
            }
            var invalid = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/documentation-probes/object"))
                    .header("X-API-Version", "2").timeout(Duration.ofSeconds(15)).build();
            var failure = client.send(invalid, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, failure.statusCode());
            assertEquals(40001, json.readTree(failure.body()).path("code").asInt());
            var download = get(client, "/documentation-probes/download");
            assertEquals(200, download.statusCode());
            assertEquals(3, download.body().length());
            JsonNode downloadSchema = document.path("paths").path("/documentation-probes/download").path("get")
                    .path("responses").path("200").path("content").path("application/octet-stream").path("schema");
            assertFalse(downloadSchema.path("properties").has("code"));
        }
    }

    /**
     * 解析生成文档中的本地Schema引用。
     * @param document 完整文档
     * @param schema 当前模型
     * @return 解析后的模型
     */
    private JsonNode resolveSchema(JsonNode document, JsonNode schema) {
        if (!schema.has("$ref")) return schema;
        String reference = schema.path("$ref").asString();
        assertTrue(reference.startsWith("#/components/schemas/"));
        return document.path("components").path("schemas").path(reference.substring("#/components/schemas/".length()));
    }

    /**
     * 请求当前测试服务器，不连接外部服务。
     * @param client 自动关闭的HTTP客户端
     * @param path 本机绝对路径
     * @return UTF-8响应
     * @throws Exception HTTP通信失败
     */
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("X-Trace-Id", "docs-test").GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
