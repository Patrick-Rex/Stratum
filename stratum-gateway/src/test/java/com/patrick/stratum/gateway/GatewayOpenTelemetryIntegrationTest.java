package com.patrick.stratum.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 在独立网关JVM中验证Agent的真实HTTP跨度和业务关联头。 */
@Tag("otel-agent")
class GatewayOpenTelemetryIntegrationTest {
    private static final Pattern STARTED = Pattern.compile("Netty started on port ([0-9]+) ");
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 验证有上游和无上游时，真实Agent为网关建立服务及客户端跨度。
     * @throws Exception 进程、Redis或HTTP操作失败
     */
    @Test
    void propagatesClientSpanAndPreservesBusinessId() throws Exception {
        String jar = Objects.requireNonNull(System.getProperty("stratum.test.gateway-jar"));
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        assertTrue(Files.isRegularFile(Path.of(jar))); assertTrue(Files.isRegularFile(Path.of(agent)));
        String redisPort = Objects.requireNonNull(System.getenv("STRATUM_IT_REDIS_PORT"), "必须提供独立Redis端口");
        var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(redisPort));
        String password = System.getenv().getOrDefault("STRATUM_IT_REDIS_PASSWORD", "");
        if (!password.isEmpty()) redisConfig.setPassword(password);
        var factory = new LettuceConnectionFactory(redisConfig); factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        var parentHeader = new AtomicReference<String>(); var businessHeader = new AtomicReference<String>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/probe", exchange -> {
            parentHeader.set(exchange.getRequestHeaders().getFirst("traceparent"));
            businessHeader.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        upstream.start();
        String namespace = "it-" + UUID.randomUUID();
        Path output = Files.createTempFile("stratum-gateway-otel-it-", ".log"); Process process = null;
        try {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            var builder = new ProcessBuilder(List.of(java.toString(), "-Xms32m", "-Xmx256m", "-XX:MaxDirectMemorySize=64m",
                    "-XX:ActiveProcessorCount=2", "-javaagent:" + agent, "-jar", jar, "--server.port=0", "--server.address=127.0.0.1",
                    "--spring.main.banner-mode=off", "--stratum.gateway.routing.enabled=true",
                    "--stratum.gateway.routing.upstream=http://127.0.0.1:" + upstream.getAddress().getPort(),
                    "--stratum.gateway.rate-limit.namespace=" + namespace));
            var env = builder.environment();
            env.keySet().removeIf(name -> name.startsWith("STRATUM_") || name.startsWith("SPRING_") || name.startsWith("OTEL_")
                    || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            env.put("STRATUM_GATEWAY_REDIS_HOST", "127.0.0.1"); env.put("STRATUM_GATEWAY_REDIS_PORT", redisPort);
            env.put("STRATUM_GATEWAY_REDIS_PASSWORD", password);
            env.put("OTEL_TRACES_EXPORTER", "logging-otlp"); env.put("OTEL_METRICS_EXPORTER", "none"); env.put("OTEL_LOGS_EXPORTER", "none");
            env.put("OTEL_TRACES_SAMPLER", "always_on"); env.put("OTEL_BSP_SCHEDULE_DELAY", "100");
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            process = builder.start(); Process child = process;
            await().atMost(Duration.ofSeconds(90)).until(() -> {
                if (!child.isAlive()) throw new IllegalStateException("网关追踪测试子进程提前退出");
                return STARTED.matcher(readOutput(output)).find();
            });
            var port = STARTED.matcher(readOutput(output)); assertTrue(port.find());
            URI uri = URI.create("http://127.0.0.1:" + port.group(1) + "/api/probe");
            String trace = UUID.randomUUID().toString().replace("-", "");
            String parent = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String business = "business-" + UUID.randomUUID();
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("traceparent", "00-" + trace + "-" + parent + "-01")
                        .header("X-Trace-Id", business).GET().build();
                assertEquals(204, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                assertEquals(business, businessHeader.get());
                var forwarded = TRACEPARENT.matcher(Objects.requireNonNull(parentHeader.get(), "未转发标准追踪头"));
                assertTrue(forwarded.matches()); assertEquals(trace, forwarded.group(1)); assertNotEquals(parent, forwarded.group(2));
                String clientSpan = forwarded.group(2);
                await().atMost(Duration.ofSeconds(15)).until(() -> linkedSpans(readOutput(output), trace, parent, clientSpan)
                        && hasGatewayLog(readOutput(output), trace, business));
                parentHeader.set(null);
                assertEquals(204, client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode());
                var root = TRACEPARENT.matcher(Objects.requireNonNull(parentHeader.get(), "无上游时未生成标准追踪头"));
                assertTrue(root.matches()); assertNotEquals(trace, root.group(1));
                assertEquals(1, Integer.parseInt(root.group(3), 16) & 1);
                String rootTrace = root.group(1); String rootClient = root.group(2);
                await().atMost(Duration.ofSeconds(15)).until(() -> linkedSpans(readOutput(output), rootTrace, "", rootClient));
            }
            assertTrue(child.isAlive());
        } finally {
            try {
                if (process != null) {
                    process.destroy();
                    try { if (!process.waitFor(Duration.ofSeconds(10))) { process.destroyForcibly();
                        if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("网关追踪测试进程未退出"); } }
                    catch (InterruptedException failure) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw failure; }
                }
            } finally {
                upstream.stop(0);
                try { String prefix = "{" + namespace + "}:rate:";
                    redis.delete(List.of(prefix + "tokens", prefix + "times", prefix + "expires", prefix + "policy")); }
                finally { factory.destroy(); Files.deleteIfExists(output); }
            }
        }
    }

    /**
     * 核对真实网关访问日志携带业务关联及有效标准追踪标识。
     * @param output 子进程输出
     * @param trace 预期标准追踪标识
     * @param business 预期业务关联标识
     * @return 是否找到同一访问事件
     */
    private boolean hasGatewayLog(String output, String trace, String business) {
        for (String line : output.split("\\R")) {
            if (!line.startsWith("{\"timestamp\"")) continue;
            var row = json.readTree(line);
            if ("网关访问".equals(row.path("message").asText()) && trace.equals(row.path("traceId").asText())
                    && business.equals(row.path("businessTraceId").asText())
                    && row.path("spanId").asText().matches("[0-9a-f]{16}")
                    && !row.path("spanId").asText().equals("0".repeat(16))) return true;
        }
        return false;
    }

    /**
     * 核对导出的服务跨度和转发客户端跨度为同一追踪的父子关系。
     * @param output 进程输出
     * @param trace 追踪标识
     * @param parent 服务跨度的父标识，根跨度为空
     * @param client 客户端跨度标识
     * @return 是否存在匹配的服务与客户端跨度
     */
    private boolean linkedSpans(String output, String trace, String parent, String client) {
        String serverId = null; String clientParent = null;
        for (String line : output.split("\\R")) {
            int offset = line.indexOf("{\"resource\""); if (offset < 0) continue;
            var root = json.readTree(line.substring(offset));
            for (var scope : root.path("scopeSpans")) for (var span : scope.path("spans")) {
                if (!trace.equals(span.path("traceId").asText())) continue;
                String kind = span.path("kind").asText(); String id = span.path("spanId").asText();
                String spanParent = span.path("parentSpanId").asText("");
                if (("2".equals(kind) || "SPAN_KIND_SERVER".equals(kind)) && parent.equals(spanParent)) serverId = id;
                if (("3".equals(kind) || "SPAN_KIND_CLIENT".equals(kind)) && client.equals(id)) clientParent = spanParent;
            }
        }
        return serverId != null && serverId.equals(clientParent);
    }

    /**
     * 有界读取测试进程输出，不泄露配置值。
     * @param path 测试独占的临时日志文件
     * @return 当前UTF-8文本
     * @throws IOException 读取失败或超过上限
     */
    private String readOutput(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IOException("网关追踪测试输出超界");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
