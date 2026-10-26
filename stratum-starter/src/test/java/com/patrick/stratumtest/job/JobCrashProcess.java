package com.patrick.stratumtest.job;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.starter.StratumStarterApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仅集成测试使用的真实JVM故障夹具，包在生产组件扫描根外，只有自身Process句柄可被结束。
 * <p>示例：try(var child=JobCrashProcess.start(code,name)){ child.crash(); }。</p>
 */
public final class JobCrashProcess implements AutoCloseable {
    private final Process process;
    private final Path directory;
    private boolean forced;

    /**
     * 保存自身进程和专用临时目录，不查找或结束其他Java进程。
     * @param process 本夹具实际创建的进程
     * @param directory 本夹具实际创建的目录
     */
    private JobCrashProcess(Process process, Path directory) { this.process = process; this.directory = directory; }

    /**
     * 使用测试工具链及类加载器真实路径启动独立完整应用，参数文件避免Windows命令长度限制。
     * @param code 当前用例唯一固定代码
     * @param name 当前用例唯一调度命名空间
     * @return 必须关闭的自有进程夹具
     * @throws Exception 类路径、临时文件或进程创建失败
     * @apiNote 示例：JobCrashProcess.start("trigger.uuid", "trigger-uuid")。
     */
    public static JobCrashProcess start(String code, String name) throws Exception {
        if (!code.matches("trigger\\.[a-f0-9-]{36}") || !name.matches("trigger-[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("故障进程只接受随机测试命名空间");
        }
        var directory = Files.createTempDirectory("stratum-job-crash-");
        var arguments = directory.resolve("java.args");
        String classpath = classpath().replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(arguments, "-Xms32m\n-Xmx256m\n-XX:MaxDirectMemorySize=64m\n-XX:ActiveProcessorCount=2\n-cp\n\""
                + classpath + "\"\n" + JobCrashProcess.class.getName() + "\n", StandardCharsets.UTF_8);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile());
        builder.environment().put("STRATUM_IT_JOB_CODE", code);
        builder.environment().put("STRATUM_IT_JOB_NAME", name);
        builder.environment().put("STRATUM_IT_JOB_STARTED_FILE", directory.resolve("started").toString());
        try { return new JobCrashProcess(builder.start(), directory); }
        catch (IOException failure) {
            for (var file : List.of("java.args", "process.log", "started")) Files.deleteIfExists(directory.resolve(file));
            Files.deleteIfExists(directory); throw failure;
        }
    }

    /**
     * 读取仅包含原执行UUID的开始标记，证明子进程实际进入处理器。
     * @param id 当前执行身份
     * @return 该原执行的实际处理已开始
     * @throws IOException 标记读取失败
     * @apiNote 示例：child.started(executionId)。
     */
    public boolean started(UUID id) throws IOException {
        var marker = directory.resolve("started");
        return Files.exists(marker) && Files.readString(marker, StandardCharsets.UTF_8).equals(id.toString());
    }

    /**
     * 核对本句柄是否仍存活，不枚举机器其他进程。
     * @return 自有进程仍存活时为true
     * @apiNote 示例：child.isAlive()。
     */
    public boolean isAlive() { return process.isAlive(); }

    /**
     * 模拟不可执行关闭钩子的进程死亡，仅强制结束本夹具创建的Process，最多等待十秒。
     * @throws InterruptedException 测试调用者中断
     * @throws IllegalStateException 自有进程未能退出
     * @apiNote 示例：确认started之后调用child.crash()。
     */
    public void crash() throws InterruptedException {
        if (process.isAlive()) { forced = true; process.destroyForcibly(); }
        if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("自有任务进程未退出");
    }

    /**
     * 确认自有进程实际退出后清理固定临时文件；提前异常退出保留诊断日志，绝不递归删除。
     * @throws Exception 等待退出或清理失败
     * @apiNote 示例：使用try-with-resources关闭。
     */
    @Override
    public void close() throws Exception {
        crash();
        if (!forced && process.exitValue() != 0) return;
        for (var file : List.of("started", "process.log", "java.args")) Files.deleteIfExists(directory.resolve(file));
        Files.deleteIfExists(directory);
    }

    /**
     * 合并Gradle测试应用类加载器和系统类路径，不猜测依赖文件名或修改全局环境。
     * @return 当前测试的真实文件类路径
     * @throws Exception 非法类路径URI
     */
    private static String classpath() throws Exception {
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
     * 子进程只允许连接显式本机stratum_it库，最长三分钟自动关闭作为测试异常后的后备期限。
     * @param args 不使用外部命令参数
     * @throws Exception 配置、启动或有界等待失败
     * @apiNote 示例：仅由start创建的独立java.exe入口调用。
     */
    public static void main(String[] args) throws Exception {
        String url = required("STRATUM_IT_DB_URL");
        if (!url.matches("jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/stratum_it")) throw new IllegalArgumentException("故障进程仅允许独立测试库");
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.datasource.username", required("STRATUM_IT_DB_USERNAME"));
        System.setProperty("spring.datasource.password", required("STRATUM_IT_DB_PASSWORD"));
        System.setProperty("spring.data.redis.host", "127.0.0.1");
        System.setProperty("spring.data.redis.port", required("STRATUM_IT_REDIS_PORT"));
        System.setProperty("spring.data.redis.password", System.getenv().getOrDefault("STRATUM_IT_REDIS_PASSWORD", ""));
        try (var context = new SpringApplicationBuilder(StratumStarterApplication.class, ProbeConfiguration.class).run(
                "--spring.main.web-application-type=none", "--stratum.jobs.enabled=true", "--stratum.jobs.workers=1",
                "--stratum.jobs.name=" + required("STRATUM_IT_JOB_NAME"),
                "--spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")) {
            new CountDownLatch(1).await(3, TimeUnit.MINUTES);
        }
    }

    /**
     * 获取必需测试配置，不将配置值写到异常。
     * @param name 配置名
     * @return 非空值
     * @throws IllegalArgumentException 未提供
     */
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("故障进程缺少测试配置：" + name);
        return value;
    }

    /** 位于生产扫描根外且只由子进程显式注册的无业务副作用故障处理器配置。 */
    @Configuration(proxyBeanMethods = false)
    public static class ProbeConfiguration {
        /**
         * 为子进程显式登记固定代码，实际进入后只写自身标记并有界等待被终止。
         * @return 子进程专用处理器
         */
        @Bean
        JobHandler crashProbe() {
            return new JobHandler() {
                /** {@inheritDoc} */
                @Override
                public String jobCode() { return required("STRATUM_IT_JOB_CODE"); }

                /** {@inheritDoc} */
                @Override
                public void execute(Submission input) throws InterruptedException {
                    var marker = Path.of(required("STRATUM_IT_JOB_STARTED_FILE")).toAbsolutePath().normalize();
                    var temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
                    if (!marker.getFileName().toString().equals("started") || !marker.getParent().getParent().equals(temporary)
                            || !marker.getParent().getFileName().toString().startsWith("stratum-job-crash-")) {
                        throw new IllegalArgumentException("故障标记必须位于本次临时目录");
                    }
                    try { Files.writeString(marker, input.executionId().toString(), StandardCharsets.UTF_8); }
                    catch (IOException failure) { throw new IllegalStateException("故障进程标记写入失败"); }
                    new CountDownLatch(1).await(90, TimeUnit.SECONDS);
                }
            };
        }
    }
}
