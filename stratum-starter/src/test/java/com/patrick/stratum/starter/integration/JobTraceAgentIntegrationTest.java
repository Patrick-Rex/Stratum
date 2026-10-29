package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratumtest.job.JobTraceAgentFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实Agent、Quartz及独立数据库核验任务执行的标准追踪。 */
@Tag("otel-agent")
class JobTraceAgentIntegrationTest {
    /**
     * 正常任务及自动重试关联原登记上下文，历史任务生成独立根跨度。
     * @throws Exception 进程或输出读取失败
     */
    @Test
    void tracesQuartzTasksAndRetriesFromPersistedContext() throws Exception {
        var observed = runFixture();
        var normal = observed.sample("normal", 1); var historical = observed.sample("historical", 1);
        var first = observed.sample("retry", 1); var second = observed.sample("retry", 2);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb:true", normal.before());
        assertEquals("normal", normal.state());
        assertEquals("none", historical.before());
        assertEquals("none", historical.state());
        assertEquals("cccccccccccccccccccccccccccccccc:dddddddddddddddd:true", first.before());
        assertEquals("retry", first.state()); assertEquals(first.state(), second.state());
        assertEquals(first.before(), second.before()); assertNotEquals(first.span(), second.span());
        verifySpan(observed, "normal", normal, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb");
        verifySpan(observed, "retry", first, "cccccccccccccccccccccccccccccccc", "dddddddddddddddd");
        verifySpan(observed, "retry", second, "cccccccccccccccccccccccccccccccc", "dddddddddddddddd");
        String historicalTrace = historical.span().split(":")[0];
        assertNotEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", historicalTrace);
        assertNotEquals("cccccccccccccccccccccccccccccccc", historicalTrace);
        verifySpan(observed, "historical", historical, historicalTrace, "");
        for (var entry : observed.samples().entrySet()) {
            var sample = entry.getValue(); assertEquals("false", sample.baggage());
            var log = observed.logs().get(entry.getKey()); assertNotNull(log, entry.getKey());
            String[] current = sample.span().split(":"); assertEquals(2, current.length);
            assertEquals(current[0], log.trace()); assertEquals(current[1], log.span());
            assertEquals("job-flow-" + entry.getKey().split(":")[0], log.business());
        }
    }

    /**
     * 核验实际导出跨度及其父节点。
     * @param observed 本次子进程的采样与跨度
     * @param scenario 固定场景名称
     * @param sample 一次实际执行采样
     * @param expectedTrace 预期追踪标识
     * @param expectedParent 预期父跨度；根跨度为空
     */
    private void verifySpan(Observation observed, String scenario, Sample sample, String expectedTrace, String expectedParent) {
        String[] active = sample.span().split(":"); assertEquals(2, active.length);
        assertEquals(expectedTrace, active[0]); assertTrue(active[1].matches("[0-9a-f]{16}"));
        assertNotEquals("0000000000000000", active[1]);
        var span = observed.spans().get(active[1]); assertNotNull(span);
        assertEquals(expectedTrace, span.trace()); assertEquals("stratum.test.job-work." + scenario, span.name());
        assertEquals(expectedParent, span.parent());
    }

    /**
     * 以独立JVM和固定Agent运行生产应用、Quartz及测试处理器。
     * @return 实际工作采样、结构化日志及导出跨度
     * @throws Exception 启动、等待或输出读取失败
     */
    private Observation runFixture() throws Exception {
        String agent = Objects.requireNonNull(System.getProperty("stratum.test.otel-agent"));
        String classpath = classpath();
        assertTrue(Files.isRegularFile(Path.of(agent)));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path directory = Files.createTempDirectory("stratum-job-trace-");
        Path arguments = directory.resolve("java.args"); Path stdout = directory.resolve("stdout.log"); Path stderr = directory.resolve("stderr.log");
        Process process = null;
        try {
            Files.writeString(arguments, "-Xms32m\n-Xmx384m\n-XX:MaxDirectMemorySize=64m\n-XX:ActiveProcessorCount=2\n"
                    + quote("-javaagent:" + agent) + "\n-cp\n" + quote(classpath) + "\n" + JobTraceAgentFixture.class.getName() + "\n", StandardCharsets.UTF_8);
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments);
            var environment = builder.environment();
            environment.keySet().removeIf(name -> name.startsWith("STRATUM_") || name.startsWith("SPRING_") || name.startsWith("OTEL_")
                    || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
            for (String name : List.of("STRATUM_IT_DB_URL", "STRATUM_IT_DB_USERNAME", "STRATUM_IT_DB_PASSWORD", "STRATUM_IT_REDIS_PORT")) {
                environment.put(name, requiredEnvironment(name));
            }
            String runId = UUID.randomUUID().toString().replace("-", "");
            environment.put("STRATUM_IT_JOB_NAME", "jobtrace-" + runId); environment.put("STRATUM_IT_JOB_CODE", "jobtrace." + runId);
            environment.put("OTEL_TRACES_EXPORTER", "logging-otlp"); environment.put("OTEL_METRICS_EXPORTER", "none");
            environment.put("OTEL_LOGS_EXPORTER", "none"); environment.put("OTEL_TRACES_SAMPLER", "always_on");
            environment.put("OTEL_BSP_SCHEDULE_DELAY", "100"); environment.put("OTEL_INSTRUMENTATION_JDBC_ENABLED", "false");
            builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()); process = builder.start();
            assertTrue(process.waitFor(Duration.ofSeconds(110)), "任务追踪独立进程未退出");
            assertEquals(0, process.exitValue(), "任务追踪独立进程失败");
            return parse(read(stdout), read(stderr));
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(Duration.ofSeconds(5))) {
                        process.destroyForcibly();
                        if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("任务追踪测试进程未退出");
                    }
                }
            } finally {
                try { Files.deleteIfExists(stdout); } finally {
                    try { Files.deleteIfExists(stderr); } finally {
                        try { Files.deleteIfExists(arguments); } finally { Files.deleteIfExists(directory); }
                    }
                }
            }
        }
    }

    /**
     * 从两路独立输出提取受限探针、登记字段的日志及实际OTLP跨度。
     * @param stdout 应用输出
     * @param stderr Agent诊断输出
     * @return 不含任务正文或凭据的采样结果
     */
    private Observation parse(String stdout, String stderr) {
        var json = JsonMapper.builder().build(); var samples = new HashMap<String, Sample>(); var logs = new HashMap<String, LogIdentity>();
        assertEquals(1, stdout.lines().filter(line -> line.equals("STRATUM_JOB_DONE|normal|historical|retry")).count());
        for (String line : stdout.split("\\R")) {
            if (line.startsWith("STRATUM_JOB_TRACE|")) {
                var fields = List.of(line.split("\\|", -1)); assertEquals(7, fields.size());
                assertNull(samples.put(fields.get(1) + ":" + fields.get(2), new Sample(fields.get(3), fields.get(4), fields.get(5), fields.get(6))));
            } else if (line.startsWith("{\"timestamp\"")) {
                var row = json.readTree(line); String message = row.path("message").asText();
                if (message.matches("job-trace-fixture (normal|historical|retry) [12]")) {
                    var parts = message.split(" ");
                    assertNull(logs.put(parts[1] + ":" + parts[2], new LogIdentity(row.path("traceId").asText(),
                            row.path("spanId").asText(), row.path("businessTraceId").asText())));
                }
            }
        }
        assertEquals(4, samples.size()); assertEquals(4, logs.size());
        var spans = new HashMap<String, SpanIdentity>();
        for (String line : stderr.split("\\R")) {
            int offset = line.indexOf("{\"resource\""); if (offset < 0) continue;
            for (var scope : json.readTree(line.substring(offset)).path("scopeSpans")) for (var span : scope.path("spans")) {
                String name = span.path("name").asText();
                if (name.startsWith("stratum.test.job-work.")) {
                    String id = span.path("spanId").asText();
                    assertNull(spans.put(id, new SpanIdentity(span.path("traceId").asText(), name, span.path("parentSpanId").asText(""))));
                }
            }
        }
        return new Observation(Map.copyOf(samples), Map.copyOf(logs), Map.copyOf(spans));
    }

    /**
     * 有界读取本用例专有输出文件。
     * @param path 临时输出路径
     * @return UTF-8文本
     * @throws Exception 读取失败或超过上限
     */
    private String read(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("任务追踪进程输出超界");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 为Java参数文件编码一个带空格的路径或类路径。
     * @param value 原始参数
     * @return Java参数文件中的转义值
     */
    private String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /**
     * 从测试类加载器和系统路径收集当前实际类路径，避免放入Windows进程命令行。
     * @return 文件类路径
     * @throws Exception 类路径URI非法
     */
    private String classpath() throws Exception {
        var entries = new LinkedHashSet<String>();
        for (var loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
            }
        }
        entries.addAll(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        return String.join(java.io.File.pathSeparator, entries);
    }

    /**
     * 读取不输出原值的测试环境配置。
     * @param name 固定环境变量名
     * @return 非空配置
     * @throws IllegalStateException 环境配置缺失
     */
    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少任务追踪测试配置：" + name);
        return value;
    }

    /**
     * 一次真实任务进入时和测试跨度内的身份。
     * @param before 进入处理器时的跨度
     * @param span 测试子跨度
     * @param baggage 不应跨任务边界的测试标记
     * @param state 上游规范状态的测试成员
     */
    private record Sample(String before, String span, String baggage, String state) { }

    /**
     * 结构化日志的标准和业务关联。
     * @param trace 标准trace ID
     * @param span 标准span ID
     * @param business 业务关联ID
     */
    private record LogIdentity(String trace, String span, String business) { }

    /**
     * 实际导出的有限跨度身份。
     * @param trace 标准trace ID
     * @param name 固定跨度名称
     * @param parent 父跨度ID
     */
    private record SpanIdentity(String trace, String name, String parent) { }

    /**
     * 子进程记录的采样、日志和跨度。
     * @param samples 按场景及尝试索引的采样
     * @param logs 对应的结构化日志
     * @param spans 真实导出跨度
     */
    private record Observation(Map<String, Sample> samples, Map<String, LogIdentity> logs, Map<String, SpanIdentity> spans) {
        /**
         * 取得必需的场景采样。
         * @param scenario 场景名称
         * @param attempt 实际尝试次数
         * @return 对应采样
         * @throws NullPointerException 必需采样缺失
         */
        Sample sample(String scenario, int attempt) { return Objects.requireNonNull(samples.get(scenario + ":" + attempt)); }
    }
}
