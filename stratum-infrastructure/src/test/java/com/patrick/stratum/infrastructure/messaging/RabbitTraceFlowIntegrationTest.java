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
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实Broker中验证正常、历史和重投消息的Agent与无Agent追踪组合。 */
@Tag("otel-agent")
class RabbitTraceFlowIntegrationTest {
    /**
     * 无Agent时传播原始上下文，历史消息保持空值，重投保留原消息上下文。
     * @throws Exception 测试进程或输出失败
     */
    @Test
    void carriesOriginalOrEmptyContextWithoutAgent() throws Exception {
        var flow = observe(false); var normal = flow.sample("normal", 1); var historical = flow.sample("historical", 1);
        var retry = flow.sample("retry", 1); var retried = flow.sample("retry", 2);
        assertEquals("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01", normal.parent());
        assertEquals("a=one", normal.state()); assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb:true", normal.current());
        assertEquals("null", historical.parent()); assertEquals("null", historical.state()); assertEquals("none", historical.current());
        assertEquals("00-cccccccccccccccccccccccccccccccc-ffffffffffffffff-01", retry.parent());
        assertEquals("c=two", retry.state()); assertEquals("cccccccccccccccccccccccccccccccc:ffffffffffffffff:true", retry.current());
        assertEquals(retry.parent(), retried.parent()); assertEquals(retry.state(), retried.state()); assertEquals(retry.current(), retried.current());
        assertTrue(flow.spans().isEmpty());
    }

    /**
     * 固定Agent为正常、独立历史和每次重投建立真实关联跨度。
     * @throws Exception 测试进程或输出失败
     */
    @Test
    void preservesAgentSpansForOriginalHistoricalAndRedeliveredMessages() throws Exception {
        var flow = observe(true); var normal = flow.sample("normal", 1); var historical = flow.sample("historical", 1);
        var retry = flow.sample("retry", 1); var retried = flow.sample("retry", 2);
        assertTrue(normal.parent().startsWith("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-")); assertEquals("a=one", normal.state());
        assertTrue(retry.parent().startsWith("00-cccccccccccccccccccccccccccccccc-")); assertEquals("c=two", retry.state());
        assertEquals(retry.parent(), retried.parent()); assertEquals(retry.state(), retried.state());
        assertNotEquals(retry.current(), retried.current());
        verifySpans(flow, normal, "bbbbbbbbbbbbbbbb");
        verifySpans(flow, retry, "ffffffffffffffff"); verifySpans(flow, retried, "ffffffffffffffff");
        verifySpans(flow, historical, "");
        String historicalTrace = historical.parent().substring(3, 35);
        assertNotEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", historicalTrace);
        assertNotEquals("cccccccccccccccccccccccccccccccc", historicalTrace);
        assertNotEquals("dddddddddddddddddddddddddddddddd", historicalTrace);
    }

    /**
     * 核对发布跨度的原始父节点，以及消费跨度与发布跨度的直接父链。
     * @param flow 本次实际采样及导出
     * @param sample 一次消息消费
     * @param originalParent 原事件父跨度；历史事件为空
     */
    private void verifySpans(Flow flow, Sample sample, String originalParent) {
        assertTrue(sample.parent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
        String trace = sample.parent().substring(3, 35); String producerId = sample.parent().substring(36, 52);
        String[] current = sample.current().split(":"); assertEquals(3, current.length);
        assertEquals(trace, current[0]); assertEquals("false", current[2]); assertNotEquals(producerId, current[1]);
        var producer = flow.spans().get(producerId); var consumer = flow.spans().get(current[1]);
        assertNotNull(producer); assertNotNull(consumer);
        assertEquals(trace, producer.trace()); assertEquals("stratum.topic publish", producer.name()); assertEquals(originalParent, producer.parent());
        assertEquals(trace, consumer.trace()); assertEquals("erp.probe.created process", consumer.name()); assertEquals(producerId, consumer.parent());
    }

    /**
     * 在独立JVM执行生产消息组件并读取受限探针和实际导出跨度。
     * @param agentEnabled 是否加载固定Agent
     * @return 消费采样和跨度身份
     * @throws Exception 进程或文件操作失败
     */
    private Flow observe(boolean agentEnabled) throws Exception {
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        String classpath = Objects.requireNonNull(System.getProperty("stratum.test.runtime-classpath"));
        assertTrue(Files.isRegularFile(Path.of(agent)));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms32m", "-Xmx256m", "-XX:ActiveProcessorCount=2"));
        if (agentEnabled) command.add("-javaagent:" + agent);
        command.addAll(List.of("-cp", classpath, RabbitTraceFlowFixture.class.getName()));
        Path output = Files.createTempFile("stratum-rabbit-flow-", ".log");
        Path diagnostics = Files.createTempFile("stratum-rabbit-flow-error-", ".log"); Process process = null;
        try {
            var builder = new ProcessBuilder(command); var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("OTEL_") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            environment.put("OTEL_TRACES_EXPORTER", "logging-otlp"); environment.put("OTEL_METRICS_EXPORTER", "none");
            environment.put("OTEL_LOGS_EXPORTER", "none"); environment.put("OTEL_TRACES_SAMPLER", "always_on");
            environment.put("OTEL_BSP_SCHEDULE_DELAY", "100");
            builder.redirectOutput(output.toFile()).redirectError(diagnostics.toFile()); process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(55)), "消息流程测试进程未退出");
            assertEquals(0, process.exitValue(), "消息流程测试进程失败");
            String stdout = readOutput(output); String stderr = readOutput(diagnostics);
            var samples = new HashMap<String, Sample>();
            for (String line : stdout.split("\\R")) {
                if (!line.startsWith("STRATUM_FLOW|")) continue;
                var fields = List.of(line.split("\\|", -1)); assertEquals(8, fields.size());
                var sample = new Sample(fields.get(3), fields.get(4), fields.get(5));
                assertEquals("flow-" + fields.get(1), fields.get(6)); assertEquals("false", fields.get(7));
                assertNull(samples.put(fields.get(1) + ":" + fields.get(2), sample));
            }
            assertEquals(4, samples.size());
            var spans = new HashMap<String, SpanIdentity>(); var json = JsonMapper.builder().build();
            for (String line : stderr.split("\\R")) {
                int offset = line.indexOf("{\"resource\""); if (offset < 0) continue;
                for (var scope : json.readTree(line.substring(offset)).path("scopeSpans")) for (var span : scope.path("spans")) {
                    String id = span.path("spanId").asText();
                    assertNull(spans.put(id, new SpanIdentity(span.path("traceId").asText(), span.path("name").asText(),
                            span.path("parentSpanId").asText(""))));
                }
            }
            return new Flow(Map.copyOf(samples), Map.copyOf(spans));
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("消息流程子进程未退出");
                }
            } finally { try { Files.deleteIfExists(output); } finally { Files.deleteIfExists(diagnostics); } }
        }
    }

    /**
     * 有界读取本次测试进程输出。
     * @param output 本测试临时文件
     * @return 解码文本
     * @throws Exception 文件读取失败或输出超界
     */
    private String readOutput(Path output) throws Exception {
        try (var input = Files.newInputStream(output)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("消息流程测试输出超界");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 一次实际消费的消息头和当前跨度。
     * @param parent 消息追踪父头
     * @param state 消息追踪状态头
     * @param current 消费入口跨度
     */
    private record Sample(String parent, String state, String current) { }

    /**
     * 导出跨度的有限身份。
     * @param trace 追踪标识
     * @param name Agent跨度名称
     * @param parent 父跨度标识
     */
    private record SpanIdentity(String trace, String name, String parent) { }

    /**
     * 采样及导出结果。
     * @param samples 按场景和次数索引的消费采样
     * @param spans 按跨度标识索引的导出结果
     */
    private record Flow(Map<String, Sample> samples, Map<String, SpanIdentity> spans) {
        /**
         * 获取必需的消费采样。
         * @param label 场景名称
         * @param attempt 消费次数
         * @return 对应采样
         */
        Sample sample(String label, int attempt) { return Objects.requireNonNull(samples.get(label + ":" + attempt)); }
    }
}
