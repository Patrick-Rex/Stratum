package com.patrick.stratum.starter.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 独立JVM使用真实Java Agent验证标准HTTP追踪和既有业务关联协议。 */
@Tag("otel-agent")
class OpenTelemetryAgentIntegrationTest {
    private static final Pattern STARTED_PORT = Pattern.compile("Tomcat started on port ([0-9]+) ");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 真实Agent建立服务跨度，同进程日志保留双标识且本机指标可抓取。
     * @throws Exception 进程、HTTP或文件操作失败
     */
    @Test
    void preservesBusinessCorrelationAndContinuesW3cTrace() throws Exception {
        String agent = requiredProperty("stratum.test.otel-agent"); String jar = requiredProperty("stratum.test.starter-jar");
        assertTrue(Files.isRegularFile(Path.of(agent))); assertTrue(Files.isRegularFile(Path.of(jar)));
        String database = requiredEnvironment("STRATUM_IT_DB_URL");
        if (!database.matches("jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/stratum_it")) {
            throw new IllegalStateException("追踪测试仅允许本机独立stratum_it数据库");
        }
        Path output = Files.createTempFile("stratum-otel-agent-it-", ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        var builder = new ProcessBuilder(List.of(java.toString(), "-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
                "-javaagent:" + agent, "-jar", jar, "--server.port=0", "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off", "--spring.flyway.validate-on-migrate=false"));
        var environment = builder.environment();
        environment.keySet().removeIf(name -> name.startsWith("STRATUM_") || name.startsWith("SPRING_") || name.startsWith("OTEL_")
                || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
        environment.put("STRATUM_DB_URL", database);
        environment.put("STRATUM_DB_USERNAME", requiredEnvironment("STRATUM_IT_DB_USERNAME"));
        environment.put("STRATUM_DB_PASSWORD", requiredEnvironment("STRATUM_IT_DB_PASSWORD"));
        environment.put("STRATUM_REDIS_HOST", "127.0.0.1");
        environment.put("STRATUM_REDIS_PORT", requiredEnvironment("STRATUM_IT_REDIS_PORT"));
        environment.put("STRATUM_REDIS_PASSWORD", System.getenv().getOrDefault("STRATUM_IT_REDIS_PASSWORD", ""));
        environment.put("OTEL_TRACES_EXPORTER", "logging-otlp");
        environment.put("OTEL_METRICS_EXPORTER", "none"); environment.put("OTEL_LOGS_EXPORTER", "none");
        environment.put("OTEL_TRACES_SAMPLER", "always_on"); environment.put("OTEL_BSP_SCHEDULE_DELAY", "100");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = null;
        try {
            process = builder.start(); Process child = process;
            await().atMost(Duration.ofSeconds(90)).until(() -> {
                if (!child.isAlive()) throw new IllegalStateException("追踪测试子进程提前退出");
                return STARTED_PORT.matcher(readOutput(output)).find();
            });
            var portMatcher = STARTED_PORT.matcher(readOutput(output)); assertTrue(portMatcher.find());
            URI endpoint = URI.create("http://127.0.0.1:" + portMatcher.group(1) + "/actuator/health");
            String trace = UUID.randomUUID().toString().replace("-", "");
            String parent = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String business = "business-" + UUID.randomUUID();
            String cookie = "test-cookie-" + UUID.randomUUID();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                        .header("traceparent", "00-" + trace + "-" + parent + "-01").header("X-Trace-Id", business).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode()); assertEquals(business, response.headers().firstValue("X-Trace-Id").orElseThrow());
                var rootRequest = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                        .header("X-Trace-Id", "root-" + UUID.randomUUID()).GET().build();
                assertEquals(200, client.send(rootRequest, HttpResponse.BodyHandlers.discarding()).statusCode());
                URI docs = URI.create("http://127.0.0.1:" + portMatcher.group(1) + "/v3/api-docs");
                var docsRequest = HttpRequest.newBuilder(docs).timeout(Duration.ofSeconds(15))
                        .header("traceparent", "00-" + trace + "-" + parent + "-01").header("X-Trace-Id", business)
                        .header("Cookie", "session=" + cookie).GET().build();
                assertEquals(200, client.send(docsRequest, HttpResponse.BodyHandlers.discarding()).statusCode());
                URI metrics = URI.create("http://127.0.0.1:" + portMatcher.group(1) + "/actuator/prometheus");
                var metricsRequest = HttpRequest.newBuilder(metrics).timeout(Duration.ofSeconds(15))
                        .header("X-Forwarded-For", "203.0.113.8").GET().build();
                var scrape = client.send(metricsRequest, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, scrape.statusCode()); assertTrue(scrape.body().contains("jvm_memory_used_bytes"));
                assertTrue(scrape.body().contains("http_server_requests_seconds_count"));
                assertFalse(scrape.body().contains(business)); assertFalse(scrape.body().contains(trace));
            }
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                String exported = readOutput(output);
                return hasServerSpan(exported, trace, parent) && hasServerSpan(exported, null, "")
                        && hasApplicationLog(exported, business, trace);
            });
            assertFalse(readOutput(output).contains(cookie));
            assertTrue(child.isAlive());
        } finally {
            try {
                if (process != null) {
                    process.destroy();
                    try {
                        if (!process.waitFor(Duration.ofSeconds(10))) {
                            process.destroyForcibly();
                            if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("追踪测试子进程未退出");
                        }
                    } catch (InterruptedException interrupted) {
                        process.destroyForcibly(); Thread.currentThread().interrupt(); throw interrupted;
                    }
                }
            } finally { Files.deleteIfExists(output); }
        }
    }

    /**
     * 在真实JSON请求日志中核对业务关联与Agent提供的有效标准跨度。
     * @param output 本测试子进程输出
     * @param business 业务关联ID
     * @param trace 标准trace ID
     * @return 是否存在匹配日志
     */
    private boolean hasApplicationLog(String output, String business, String trace) {
        for (String line : output.split("\\R")) {
            if (!line.startsWith("{\"timestamp\"")) continue;
            var row = json.readTree(line);
            if (business.equals(row.path("businessTraceId").asText()) && trace.equals(row.path("traceId").asText())
                    && row.path("spanId").asText().matches("[0-9a-f]{16}")
                    && !row.path("spanId").asText().equals("0000000000000000")) return true;
        }
        return false;
    }

    /**
     * 在OTLP JSON导出中核对同一个HTTP服务跨度的追踪身份和父跨度。
     * @param output 本测试子进程输出
     * @param expectedTrace 指定trace，空值表示检查由Agent生成的合法trace
     * @param expectedParent 指定父跨度，无上游时为空字符串
     * @return 是否找到匹配的健康请求服务跨度
     */
    private boolean hasServerSpan(String output, String expectedTrace, String expectedParent) {
        for (String line : output.split("\\R")) {
            int offset = line.indexOf("{\"resource\"");
            if (offset < 0) continue;
            var root = json.readTree(line.substring(offset));
            for (var scope : root.path("scopeSpans")) {
                for (var span : scope.path("spans")) {
                    String trace = span.path("traceId").asText();
                    String parent = span.path("parentSpanId").asText("");
                    String kind = span.path("kind").asText();
                    if ("GET /actuator/health".equals(span.path("name").asText())
                            && ("2".equals(kind) || "SPAN_KIND_SERVER".equals(kind))
                            && TRACE_ID.matcher(trace).matches() && !trace.equals("00000000000000000000000000000000")
                            && (expectedTrace == null || expectedTrace.equals(trace)) && expectedParent.equals(parent)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 有界读取本用例进程输出，不打印或暴露原始日志。
     * @param output 仅本用例拥有的临时文件
     * @return 当前输出文本
     * @throws IOException 文件无法读取或超界
     */
    private String readOutput(Path output) throws IOException {
        try (var input = Files.newInputStream(output)) {
            byte[] bytes = input.readNBytes(MAX_OUTPUT_BYTES + 1);
            if (bytes.length > MAX_OUTPUT_BYTES) throw new IOException("追踪测试输出超界");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 获取测试任务传入的必要文件路径。
     * @param name 属性名称
     * @return 非空路径
     */
    private String requiredProperty(String name) { return Objects.requireNonNull(System.getProperty(name), "缺少追踪测试文件配置"); }

    /**
     * 获取测试专用环境配置，不在错误中输出配置值。
     * @param name 环境变量名称
     * @return 非空值
     */
    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少追踪测试环境变量：" + name);
        return value;
    }
}
