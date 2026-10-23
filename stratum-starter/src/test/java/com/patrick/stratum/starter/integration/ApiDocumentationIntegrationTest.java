package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实HTTP服务器验证OpenAPI与Knife4j静态展示入口的装配。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
