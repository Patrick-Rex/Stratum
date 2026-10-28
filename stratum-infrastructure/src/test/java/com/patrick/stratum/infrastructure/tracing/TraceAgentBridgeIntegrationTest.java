package com.patrick.stratum.infrastructure.tracing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实Agent独立JVM验证标准上下文组件与导出跨度互通。 */
@Tag("otel-agent")
class TraceAgentBridgeIntegrationTest {
    /**
     * 检查真实导出的父子跨度与空历史上下文根跨度，不启动收集服务。
     * @throws Exception 进程或输出读取失败
     */
    @Test void exportsRestoredChildAndIndependentRoot() throws Exception {
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        String classpath = Objects.requireNonNull(System.getProperty("stratum.test.runtime-classpath"));
        assertTrue(Files.isRegularFile(Path.of(agent)));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path output = Files.createTempFile("stratum-trace-bridge-agent-", ".log"); Process process = null;
        try {
            var builder = new ProcessBuilder(List.of(java.toString(), "-Xms32m", "-Xmx192m", "-XX:ActiveProcessorCount=2",
                    "-javaagent:" + agent, "-cp", classpath, TraceAgentFixture.class.getName()));
            var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("OTEL_") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            environment.put("OTEL_TRACES_EXPORTER", "logging-otlp"); environment.put("OTEL_METRICS_EXPORTER", "none");
            environment.put("OTEL_LOGS_EXPORTER", "none"); environment.put("OTEL_TRACES_SAMPLER", "always_on");
            environment.put("OTEL_BSP_SCHEDULE_DELAY", "100");
            builder.redirectErrorStream(true).redirectOutput(output.toFile()); process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(45)), "Agent测试进程未在上限内退出");
            assertEquals(0, process.exitValue(), "Agent测试进程失败");
            var spans = readSpans(output);
            var parent = spans.get("stratum-test-parent"); var child = spans.get("stratum-test-restored"); var root = spans.get("stratum-test-root");
            assertNotNull(parent); assertNotNull(child); assertNotNull(root);
            for (var span : List.of(parent, child, root)) {
                assertTrue(span.trace().matches("[0-9a-f]{32}")); assertNotEquals("0".repeat(32), span.trace());
                assertTrue(span.id().matches("[0-9a-f]{16}")); assertNotEquals("0".repeat(16), span.id());
            }
            assertEquals(parent.trace(), child.trace()); assertEquals(parent.id(), child.parent());
            assertEquals("", parent.parent()); assertEquals("", root.parent()); assertNotEquals(parent.trace(), root.trace());
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    try { if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("Agent测试进程未退出"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                }
            } finally { Files.deleteIfExists(output); }
        }
    }

    /**
     * 有界解析本进程OTLP输出中三个固定测试跨度，不输出原始日志。
     * @param output 测试独有输出文件
     * @return 按固定名称索引的跨度身份
     * @throws Exception 读取失败或输出超界
     */
    private java.util.Map<String, SpanIdentity> readSpans(Path output) throws Exception {
        String content;
        try (var input = Files.newInputStream(output)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("Agent测试输出超界");
            content = new String(bytes, StandardCharsets.UTF_8);
        }
        assertFalse(content.contains("private-fixture"));
        var json = JsonMapper.builder().build(); var spans = new HashMap<String, SpanIdentity>();
        for (String line : content.split("\\R")) {
            int offset = line.indexOf("{\"resource\""); if (offset < 0) continue;
            for (var scope : json.readTree(line.substring(offset)).path("scopeSpans")) for (var span : scope.path("spans")) {
                String name = span.path("name").asText();
                if (List.of("stratum-test-parent", "stratum-test-restored", "stratum-test-root").contains(name)) {
                    assertNull(spans.put(name, new SpanIdentity(span.path("traceId").asText(), span.path("spanId").asText(),
                            span.path("parentSpanId").asText(""))));
                }
            }
        }
        return spans;
    }

    /**
     * 测试跨度身份快照，不包含业务数据。
     * @param trace 标准trace ID
     * @param id 标准span ID
     * @param parent 父span ID，根跨度为空
     */
    private record SpanIdentity(String trace, String id, String parent) { }
}
