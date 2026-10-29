package com.patrick.stratum.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实Agent和无Agent的独立JVM中观察RabbitMQ生产监听器。 */
@Tag("otel-agent")
class RabbitTraceAgentIntegrationTest {
    /**
     * 验证无Agent时生产监听器恢复远端上下文，且不继承线程残留。
     * @throws Exception 进程或输出失败
     */
    @Test
    void observesListenerWithoutAgent() throws Exception {
        var probe = runFixture(false, false).fields();
        assertEquals("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01", probe.get(1));
        assertEquals("vendor=value", probe.get(2));
        assertEquals("none", probe.get(3));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb:true", probe.get(4)); assertEquals("false", probe.get(5));
        assertTrue(Integer.parseInt(probe.get(6)) >= 1); assertEquals("false", probe.get(7));
    }

    /**
     * 验证固定Agent的生产监听器是否建立属于原消息追踪的消费跨度。
     * @throws Exception 进程或输出失败
     */
    @Test
    void observesListenerWithAgent() throws Exception {
        var observation = runFixture(true, false); var probe = observation.fields();
        assertTrue(probe.get(1).startsWith("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-"), probe.toString());
        assertEquals("vendor=value", probe.get(2));
        assertTrue(probe.get(4).startsWith("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:"), probe.toString());
        assertFalse(probe.get(4).endsWith(":true"), probe.toString()); assertEquals(probe.get(3), probe.get(4));
        assertEquals("false", probe.get(5)); assertTrue(Integer.parseInt(probe.get(6)) >= 1); assertEquals("false", probe.get(7));
        String producerId = probe.get(1).substring(36, 52); String consumerId = probe.get(4).split(":")[1];
        var producer = observation.spans().get(producerId); var consumer = observation.spans().get(consumerId);
        assertNotNull(producer); assertNotNull(consumer); assertNotEquals(producerId, consumerId);
        assertEquals("stratum.topic publish", producer.name()); assertEquals("bbbbbbbbbbbbbbbb", producer.parent());
        assertEquals("erp.probe.created process", consumer.name()); assertEquals(producerId, consumer.parent());
    }

    /**
     * 消息语义约定切换后仍保留Agent处理跨度及与发布跨度的父链或链接。
     * @throws Exception 进程或输出失败
     */
    @Test
    void preservesAgentSpanWithMessagingSemconvOptIn() throws Exception {
        var observation = runFixture(true, true); var probe = observation.fields();
        assertTrue(probe.get(1).startsWith("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-"), probe.toString());
        assertEquals("vendor=value", probe.get(2)); assertEquals(probe.get(3), probe.get(4));
        assertEquals("false", probe.get(5)); assertTrue(Integer.parseInt(probe.get(6)) >= 1); assertEquals("false", probe.get(7));
        assertFalse(probe.get(4).startsWith("dddddddddddddddddddddddddddddddd:"));
        assertFalse(probe.get(4).endsWith(":true"));
        String producerId = probe.get(1).substring(36, 52); String consumerId = probe.get(4).split(":")[1];
        var consumer = observation.spans().get(consumerId); assertNotNull(consumer);
        assertTrue(producerId.equals(consumer.parent()) || consumer.links().contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:" + producerId));
    }

    /**
     * 启动带或不带Agent的独立JVM，分流采集并读取唯一有界探针。
     * @param agentEnabled 是否加载固定版本Agent
     * @param stableMessaging 是否启用新的消息语义约定
     * @return 消费探针和实际导出的同链路跨度
     * @throws Exception 进程或输出读取失败
     */
    private Observation runFixture(boolean agentEnabled, boolean stableMessaging) throws Exception {
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        String classpath = Objects.requireNonNull(System.getProperty("stratum.test.runtime-classpath"));
        assertTrue(Files.isRegularFile(Path.of(agent)));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms32m", "-Xmx256m", "-XX:ActiveProcessorCount=2"));
        if (agentEnabled) command.add("-javaagent:" + agent);
        command.addAll(List.of("-cp", classpath, RabbitTraceAgentFixture.class.getName()));
        Path output = Files.createTempFile("stratum-rabbit-trace-agent-", ".log");
        Path diagnostics = Files.createTempFile("stratum-rabbit-trace-agent-error-", ".log"); Process process = null;
        try {
            var builder = new ProcessBuilder(command); var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("OTEL_") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            environment.put("OTEL_TRACES_EXPORTER", "logging-otlp"); environment.put("OTEL_METRICS_EXPORTER", "none");
            environment.put("OTEL_LOGS_EXPORTER", "none"); environment.put("OTEL_TRACES_SAMPLER", "always_on");
            environment.put("OTEL_BSP_SCHEDULE_DELAY", "100");
            if (stableMessaging) environment.put("OTEL_SEMCONV_STABILITY_OPT_IN", "messaging");
            builder.redirectOutput(output.toFile()).redirectError(diagnostics.toFile()); process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(55)), "消息追踪测试进程未退出");
            var captured = new StringBuilder();
            for (Path path : List.of(output, diagnostics)) {
                try (var input = Files.newInputStream(path)) {
                    byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
                    if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("追踪探针输出超界");
                    captured.append(new String(bytes, StandardCharsets.UTF_8)).append('\n');
                }
            }
            String text = captured.toString();
            assertEquals(0, process.exitValue(), "消息追踪测试进程失败");
            var probes = text.lines().filter(line -> line.startsWith("STRATUM_TRACE_PROBE|")).toList();
            assertEquals(1, probes.size(), "缺少唯一消费探针");
            var fields = List.of(probes.getFirst().split("\\|", -1)); assertEquals(8, fields.size());
            var spans = new HashMap<String, SpanIdentity>();
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            for (String line : text.split("\\R")) {
                int offset = line.indexOf("{\"resource\""); if (offset < 0) continue;
                for (var scope : json.readTree(line.substring(offset)).path("scopeSpans")) for (var span : scope.path("spans")) {
                    String trace = span.path("traceId").asText();
                    if ("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".equals(trace) || fields.get(4).startsWith(trace + ":")) {
                        String id = span.path("spanId").asText(); var links = new ArrayList<String>();
                        for (var link : span.path("links")) links.add(link.path("traceId").asText() + ":" + link.path("spanId").asText());
                        assertNull(spans.put(id, new SpanIdentity(span.path("name").asText(), span.path("parentSpanId").asText(""), List.copyOf(links))));
                    }
                }
            }
            return new Observation(fields, Map.copyOf(spans));
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("消息追踪子进程未退出");
                }
            } finally {
                try { Files.deleteIfExists(output); } finally { Files.deleteIfExists(diagnostics); }
            }
        }
    }

    /**
     * 测试接收的固定探针与导出跨度。
     * @param fields 传输头及当前跨度
     * @param spans 按跨度ID索引的实际导出结果
     */
    private record Observation(List<String> fields, Map<String, SpanIdentity> spans) { }

    /**
     * 不包含正文或凭据的导出跨度快照。
     * @param name 固定Agent跨度名称
     * @param parent 直接父跨度ID
     * @param links 消息创建跨度链接
     */
    private record SpanIdentity(String name, String parent, List<String> links) { }
}
