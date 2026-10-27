package com.patrick.stratum.starter.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.core.ConditionTimeoutException;

import static org.awaitility.Awaitility.await;

/**
 * 为单个集成测试类提供独占的本机 MariaDB 容器。
 *
 * <p>使用示例：{@code try (var database = IsolatedMariaDbFixture.start()) {
 * String url = database.jdbcUrl(); }}。调用者必须先关闭使用数据库的应用上下文，再关闭夹具。
 */
final class IsolatedMariaDbFixture implements AutoCloseable {
    private static final String OWNER_LABEL = "com.patrick.stratum.integration-owner";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_OUTPUT_BYTES = 16_384;

    private final String owner = UUID.randomUUID().toString();
    private final String containerName = "stratum-it-" + owner;
    private final String database = "stratum_it_" + UUID.randomUUID().toString().replace("-", "");
    private final String username = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final String password = UUID.randomUUID().toString() + UUID.randomUUID();
    private final String rootPassword = UUID.randomUUID().toString() + UUID.randomUUID();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String dockerHost;
    private final Thread shutdownHook;
    private volatile String jdbcUrl;
    private boolean createStarted;
    private boolean createConfirmed;

    /**
     * 创建夹具并注册关闭兜底。
     * @param dockerHost 已限定为本机的 Docker 端点
     */
    private IsolatedMariaDbFixture(String dockerHost) {
        this.dockerHost = dockerHost;
        this.shutdownHook = Thread.ofPlatform().name("stratum-it-mariadb-cleanup-" + owner).unstarted(() -> {
            try {
                close();
            } catch (RuntimeException ignored) {
                // JVM 退出时不输出可能包含环境信息的底层异常。
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * 创建并验证独立容器；失败时尝试清理已创建资源。
     * @return 已就绪的夹具
     * @throws IllegalStateException Docker 不可用或数据库未就绪
     */
    static IsolatedMariaDbFixture start() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String host = os.contains("windows") ? "npipe:////./pipe/dockerDesktopLinuxEngine"
                : os.contains("linux") ? "unix:///var/run/docker.sock" : null;
        if (host == null) {
            throw new IllegalStateException("独立数据库夹具仅支持本机 Windows 或 Linux Docker");
        }
        IsolatedMariaDbFixture fixture = new IsolatedMariaDbFixture(host);
        try {
            fixture.initialize();
            return fixture;
        } catch (RuntimeException | Error failure) {
            try {
                fixture.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * 返回已验证的回环地址 JDBC URL。
     * @return 测试数据库连接地址
     */
    String jdbcUrl() {
        if (jdbcUrl == null || closed.get()) {
            throw new IllegalStateException("独立数据库尚未就绪或已经关闭");
        }
        return jdbcUrl;
    }

    /**
     * 返回本次生成的非 root 数据库账号。
     * @return 数据库账号
     */
    String username() {
        return username;
    }

    /**
     * 返回本次生成的数据库密码，调用方不得记录该值。
     * @return 数据库密码
     */
    String password() {
        return password;
    }

    /** 创建容器并通过 JDBC 验证数据库身份。 */
    private void initialize() {
        if (!"linux".equals(docker(List.of("info", "--format", "{{.OSType}}"), Map.of()).strip())) {
            throw new IllegalStateException("独立数据库夹具要求本机 Linux Docker 引擎");
        }
        docker(List.of("image", "inspect", "mariadb:11.8", "--format", "{{.Id}}"), Map.of());
        createStarted = true;
        docker(List.of("create", "--pull=never", "--name", containerName,
                "--label", OWNER_LABEL + "=" + owner,
                "--publish", "127.0.0.1::3306",
                "--env", "MARIADB_DATABASE=" + database,
                "--env", "MARIADB_USER=" + username,
                "--env", "MARIADB_ROOT_HOST=localhost",
                "--env", "MARIADB_ROOT_PASSWORD",
                "--env", "MARIADB_PASSWORD",
                "--tmpfs", "/var/lib/mysql:rw,size=512m",
                "mariadb:11.8"), Map.of("MARIADB_ROOT_PASSWORD", rootPassword, "MARIADB_PASSWORD", password));
        createConfirmed = true;
        String id = ownedContainerId();
        if (id == null) {
            throw new IllegalStateException("无法验证独立数据库容器身份");
        }
        docker(List.of("start", id), Map.of());
        String port = docker(List.of("port", id, "3306/tcp"), Map.of()).strip();
        if (!port.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
            throw new IllegalStateException("独立数据库未绑定到本机回环地址");
        }
        int portNumber = Integer.parseInt(port.substring(port.lastIndexOf(':') + 1));
        if (portNumber < 1 || portNumber > 65_535) {
            throw new IllegalStateException("独立数据库端口无效");
        }
        String url = "jdbc:mariadb://" + port + "/" + database;
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "2000");
        properties.setProperty("socketTimeout", "2000");
        try {
            await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(200)).atMost(READY_TIMEOUT).until(() -> {
                try (var connection = DriverManager.getConnection(url, properties)) {
                    if (!database.equals(connection.getCatalog())) {
                        throw new IllegalStateException("独立数据库身份校验失败");
                    }
                    return true;
                } catch (SQLException ignored) {
                    return false;
                }
            });
        } catch (ConditionTimeoutException failure) {
            throw new IllegalStateException("独立数据库在限定时间内未就绪");
        }
        jdbcUrl = url;
    }

    /**
     * 按随机名称和所有者标签核验容器，并返回不可变容器 ID。
     * @return 容器 ID；未创建时返回 null
     */
    private String ownedContainerId() {
        String output = docker(List.of("ps", "--all", "--no-trunc", "--quiet",
                "--filter", "name=^/" + containerName + "$",
                "--filter", "label=" + OWNER_LABEL + "=" + owner), Map.of()).strip();
        if (output.isEmpty()) {
            return null;
        }
        if (!output.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("独立数据库容器身份不唯一");
        }
        // 标签过滤与完整ID共同固定身份；标签不可变，第二次核验名称无需在Windows命令行嵌套引号。
        String identity = docker(List.of("inspect", "--format", "{{.Id}} {{.Name}}", output), Map.of()).strip();
        if (!identity.equals(output + " /" + containerName)) {
            throw new IllegalStateException("独立数据库容器身份校验失败");
        }
        return output;
    }

    /**
     * 在限定时间内执行本机 Docker 命令；凭据仅通过子进程环境传递。
     * @param arguments Docker 命令参数
     * @param environment 需要传给子进程的环境变量
     * @return 命令标准输出
     * @throws IllegalStateException 命令失败或超时
     */
    private String docker(List<String> arguments, Map<String, String> environment) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("--host");
        command.add(dockerHost);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("DOCKER_CONTEXT");
        builder.environment().remove("DOCKER_HOST");
        builder.environment().remove("DOCKER_TLS_VERIFY");
        builder.environment().remove("DOCKER_CERT_PATH");
        builder.environment().putAll(environment);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = null;
        try {
            process = builder.start();
            Process runningProcess = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> readBounded(runningProcess.getInputStream(), output));
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("本机 Docker 命令超时");
            }
            reader.join(1_000);
            if (reader.isAlive() || process.exitValue() != 0) {
                throw new IllegalStateException("本机 Docker 命令失败");
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法执行本机 Docker 命令");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本机 Docker 命令被中断");
        } finally {
            if (process != null && process.isAlive()) {
                stopProcess(process);
            }
        }
    }

    /**
     * 强制终止子进程，在有限时间内确认退出并保留调用者的中断标志。
     * @param process 待终止的 Docker CLI 进程
     * @throws IllegalStateException 子进程未在限定时间内退出
     */
    private static void stopProcess(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            process.destroyForcibly();
            long deadline = System.nanoTime() + PROCESS_STOP_TIMEOUT.toNanos();
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("本机 Docker 子进程未能在限定时间内退出");
                }
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 消费全部输出但只保留有界字节，避免子进程阻塞或无限占用内存。
     * @param input 子进程输出流
     * @param output 有界输出缓存
     */
    private static void readBounded(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                int remaining = MAX_OUTPUT_BYTES - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(remaining, count));
                }
            }
        } catch (IOException ignored) {
            // 命令状态由调用线程检查，不输出子进程内容。
        }
    }

    /** 关闭并移除身份匹配的容器；清理失败时保留 JVM 退出兜底。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            String id = ownedContainerId();
            if (id != null) {
                docker(List.of("rm", "--force", "--volumes", id), Map.of());
            } else if (createStarted && !createConfirmed) {
                // 客户端超时后守护进程仍可能完成创建，保留退出兜底并报告不确定结果。
                throw new IllegalStateException("容器创建结果不确定，需检查自建容器：" + containerName);
            }
            if (Thread.currentThread() != shutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        } catch (RuntimeException failure) {
            closed.set(false);
            throw failure;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
