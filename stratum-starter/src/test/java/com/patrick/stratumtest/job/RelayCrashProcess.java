package com.patrick.stratumtest.job;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.starter.StratumStarterApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 仅由集成测试启动的独立Relay JVM，在真实领取后阻塞并允许测试结束此进程。
 * <p>示例：使用try-with-resources启动夹具，确认started后调用crash。</p>
 */
public final class RelayCrashProcess implements AutoCloseable {
    private final Process process;
    private final Path directory;
    private boolean forced;

    /**
     * 保存实际创建的进程和私有临时目录。
     * @param process 自有进程
     * @param directory 自有目录
     */
    private RelayCrashProcess(Process process, Path directory) { this.process = process; this.directory = directory; }

    /**
     * 以随机专用数据库和调度名启动子JVM，连接凭据仅经子进程环境传递。
     * @param url 测试独占数据库地址
     * @param username 测试独占数据库账号
     * @param password 测试独占数据库密码
     * @param name 测试独占调度名
     * @return 可关闭的进程夹具
     * @throws Exception 创建目录、类路径或进程失败
     * @apiNote 示例：仅使用由隔离夹具生成的随机库和调度名调用。
     */
    public static RelayCrashProcess start(String url, String username, String password, String name) throws Exception {
        if (!url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/stratum_it_[a-f0-9]{32}")
                || !username.matches("it_[a-f0-9]{20}") || !name.matches("relaytest-[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("故障进程仅接受独占测试库和调度名");
        }
        var directory = Files.createTempDirectory("stratum-relay-crash-");
        var arguments = directory.resolve("java.args");
        String classpath = classpath().replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(arguments, "-Xms32m\n-Xmx256m\n-XX:MaxDirectMemorySize=64m\n-XX:ActiveProcessorCount=2\n-cp\n\""
                + classpath + "\"\n" + RelayCrashProcess.class.getName() + "\n", StandardCharsets.UTF_8);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile());
        builder.environment().put("STRATUM_RELAY_IT_DB_URL", url);
        builder.environment().put("STRATUM_RELAY_IT_DB_USERNAME", username);
        builder.environment().put("STRATUM_RELAY_IT_DB_PASSWORD", password);
        builder.environment().put("STRATUM_RELAY_IT_JOB_NAME", name);
        builder.environment().put("STRATUM_RELAY_IT_MARKER", directory.resolve("started").toString());
        try { return new RelayCrashProcess(builder.start(), directory); }
        catch (IOException failure) {
            for (String file : List.of("java.args", "process.log", "started")) Files.deleteIfExists(directory.resolve(file));
            Files.deleteIfExists(directory); throw failure;
        }
    }

    /**
     * 确认真实发布端口已经收到指定的原事件。
     * @param eventId 原事件身份
     * @return 真实领取并进入发布端口时为true
     * @throws IOException 标记读取失败
     * @apiNote 示例：等待child.started(eventId)后模拟死亡。
     */
    public boolean started(String eventId) throws IOException {
        var marker = directory.resolve("started");
        return Files.exists(marker) && Files.readString(marker, StandardCharsets.UTF_8).equals(eventId);
    }

    /**
     * 检查本夹具创建的进程是否存活。
     * @return 当前进程存活时为true
     * @apiNote 示例：等待标记时检查child.isAlive()。
     */
    public boolean isAlive() { return process.isAlive(); }

    /**
     * 强制终止本夹具的进程，模拟不能执行关闭钩子的故障。
     * @throws InterruptedException 等待被中断
     * @apiNote 示例：确认事件已领取后调用child.crash()。
     */
    public void crash() throws InterruptedException {
        if (process.isAlive()) { forced = true; process.destroyForcibly(); }
        if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("自有Relay进程未退出");
    }

    /**
     * 关闭自有进程并清理固定私有文件；非预期提前退出保留诊断日志。
     * @throws Exception 关闭或文件清理失败
     * @apiNote 示例：使用try-with-resources管理夹具。
     */
    @Override public void close() throws Exception {
        crash();
        if (!forced && process.exitValue() != 0) return;
        for (String file : List.of("started", "process.log", "java.args")) Files.deleteIfExists(directory.resolve(file));
        Files.deleteIfExists(directory);
    }

    /**
     * 从当前测试类加载器获取实际类路径，避免猜测依赖位置。
     * @return 平台类路径
     * @throws Exception 路径URI非法
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
     * 仅使用已限定的本机独占测试库及专用Broker启动完整应用。
     * @param args 不使用的外部参数
     * @throws Exception 启动或等待失败
     * @apiNote 示例：由start创建的独立Java进程调用。
     */
    public static void main(String[] args) throws Exception {
        String url = required("STRATUM_RELAY_IT_DB_URL"), username = required("STRATUM_RELAY_IT_DB_USERNAME");
        String vhost = required("STRATUM_IT_MQ_VHOST"), mqUser = required("STRATUM_IT_MQ_USERNAME");
        if (!url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/stratum_it_[a-f0-9]{32}")
                || !username.matches("it_[a-f0-9]{20}") || !vhost.matches("stratum-it-[a-f0-9]{32}") || !vhost.equals(mqUser)) {
            throw new IllegalArgumentException("故障进程隔离配置无效");
        }
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.datasource.username", username);
        System.setProperty("spring.datasource.password", required("STRATUM_RELAY_IT_DB_PASSWORD"));
        System.setProperty("spring.data.redis.host", "127.0.0.1");
        System.setProperty("spring.data.redis.port", required("STRATUM_IT_REDIS_PORT"));
        System.setProperty("stratum.messaging.host", "127.0.0.1");
        System.setProperty("stratum.messaging.port", required("STRATUM_IT_MQ_PORT"));
        System.setProperty("stratum.messaging.virtual-host", vhost);
        System.setProperty("stratum.messaging.username", mqUser);
        System.setProperty("stratum.messaging.password", required("STRATUM_IT_MQ_PASSWORD"));
        try (var context = new SpringApplicationBuilder(StratumStarterApplication.class, ProbeConfiguration.class).run(
                "--spring.main.web-application-type=none", "--stratum.jobs.enabled=true", "--stratum.jobs.workers=2",
                "--stratum.jobs.name=" + required("STRATUM_RELAY_IT_JOB_NAME"), "--stratum.jobs.relay.enabled=true",
                "--stratum.jobs.relay.cron=0/1 * * * * ?", "--stratum.jobs.relay.batch-size=2",
                "--stratum.jobs.relay.reconciliation-limit=4", "--stratum.messaging.enabled=true",
                "--spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")) {
            new CountDownLatch(1).await(3, TimeUnit.MINUTES);
        }
    }

    /**
     * 获取必需环境值，不将敏感内容写入异常。
     * @param name 环境名
     * @return 非空值
     */
    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("故障进程缺少配置：" + name);
        return value;
    }

    /**
     * 仅子进程显式注册的探针，进入发布时保留真实事件身份。
     * <p>示例：由故障进程的SpringApplicationBuilder显式注册本配置。</p>
     */
    @Configuration(proxyBeanMethods = false)
    public static class ProbeConfiguration {
        /**
         * 创建不发送消息的阻塞探针，证明事件已由真实Relay领取。
         * @return 唯一优先发布端口
         */
        @Bean @Primary
        OutboxPublisher crashPublisher() {
            return delivery -> {
                var marker = Path.of(required("STRATUM_RELAY_IT_MARKER")).toAbsolutePath().normalize();
                var temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
                if (!marker.getFileName().toString().equals("started") || !marker.getParent().getParent().equals(temporary)
                        || !marker.getParent().getFileName().toString().startsWith("stratum-relay-crash-")) {
                    throw new IllegalArgumentException("故障标记不属于本次临时目录");
                }
                try {
                    Files.writeString(marker, delivery.message().eventId(), StandardCharsets.UTF_8);
                    new CountDownLatch(1).await(90, TimeUnit.SECONDS);
                } catch (IOException failure) { throw new IllegalStateException("故障标记写入失败"); }
                catch (InterruptedException signal) { Thread.currentThread().interrupt(); }
                return OutboxPublisher.PublishResult.UNAVAILABLE;
            };
        }
    }
}
