package com.patrick.stratum.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 使用实际Agent独立JVM验证消费适配器，不启动Broker或业务应用。 */
@Tag("otel-agent")
class RabbitTraceDirectAgentTest {
    /**
     * 活动Agent跨度不能被消息头或不可用追踪组件替换，确认和调用方上下文保持。
     * @throws Exception 子进程或输出读取失败
     */
    @Test
    void preservesActualAgentSpanWithoutBroker() throws Exception {
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        String classpath = Objects.requireNonNull(System.getProperty("stratum.test.runtime-classpath"));
        assertTrue(Files.isRegularFile(Path.of(agent)));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path output = Files.createTempFile("stratum-direct-consumer-agent-", ".log"); Process process = null;
        try {
            var builder = new ProcessBuilder(List.of(java.toString(), "-Xms32m", "-Xmx192m", "-XX:ActiveProcessorCount=2",
                    "-javaagent:" + agent, "-cp", classpath, RabbitTraceDirectAgentFixture.class.getName()));
            var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("OTEL_") || name.startsWith("STRATUM_")
                    || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            environment.put("OTEL_TRACES_EXPORTER", "none"); environment.put("OTEL_METRICS_EXPORTER", "none");
            environment.put("OTEL_LOGS_EXPORTER", "none"); environment.put("OTEL_TRACES_SAMPLER", "always_on");
            builder.redirectErrorStream(true).redirectOutput(output.toFile()); process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(40)), "消费追踪测试进程未退出");
            assertEquals(0, process.exitValue(), "消费追踪测试进程失败");
            try (var input = Files.newInputStream(output)) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                assertTrue(bytes.length <= 1024 * 1024, "测试输出超界");
                assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("STRATUM_DIRECT_AGENT_OK"));
            }
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    try { if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("消费追踪测试进程未退出"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                }
            } finally { Files.deleteIfExists(output); }
        }
    }
}
