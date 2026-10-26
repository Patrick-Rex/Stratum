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

import org.springframework.data.redis.core.StringRedisTemplate;

/** 管理本用例独立Gateway可执行JAR及随机Redis命名空间，不把网关类加入Starter类路径。 */
final class GatewayProcessFixture implements AutoCloseable {
    private static final Pattern STARTED_PORT = Pattern.compile("Netty started on port ([0-9]+) ");
    private final Process process;
    private final Path output;
    private final String namespace;
    private final StringRedisTemplate redis;
    private int port;
    private boolean preserveOutput;

    /**
     * 保存仅本夹具创建的资源，启动就绪失败亦由本对象清理。
     * @param process 独立Java子进程
     * @param output 临时UTF8输出
     * @param namespace 唯一Redis命名空间
     * @param redis 测试清理客户端
     */
    private GatewayProcessFixture(Process process, Path output, String namespace, StringRedisTemplate redis) {
        this.process = process; this.output = output; this.namespace = namespace; this.redis = redis;
    }

    /**
     * 使用测试JDK25启动独立JAR，所有非秘密参数以列表传入，Redis口令仅置于子进程环境。
     * @param upstreamPort 已就绪的本机ERP端口
     * @param redis 只用于本命名空间清理
     * @return 已通过health的子进程夹具
     * @throws Exception JAR缺失、启动或就绪失败
     */
    static GatewayProcessFixture start(int upstreamPort, StringRedisTemplate redis) throws Exception {
        Objects.requireNonNull(redis, "redis");
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("stratum.test.gateway-jar"), "必须通过集成任务提供Gateway JAR"));
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Gateway可执行JAR不存在");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String namespace = "it-" + UUID.randomUUID(); Path output = Files.createTempFile("stratum-gateway-it-", ".log");
        var builder = new ProcessBuilder(List.of(java.toString(), "-Xms32m", "-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:ActiveProcessorCount=2", "-jar", jar.toString(), "--server.port=0", "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off", "--stratum.gateway.routing.enabled=true", "--stratum.gateway.routing.upstream=http://127.0.0.1:" + upstreamPort,
                "--stratum.gateway.routing.gray-enabled=false", "--stratum.gateway.rate-limit.namespace=" + namespace));
        var environment = builder.environment();
        environment.keySet().removeIf(name -> name.startsWith("STRATUM_") || name.startsWith("SPRING_") || name.startsWith("OTEL_")
                || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS"));
        environment.put("STRATUM_GATEWAY_REDIS_HOST", "127.0.0.1");
        environment.put("STRATUM_GATEWAY_REDIS_PORT", Objects.requireNonNull(System.getenv("STRATUM_IT_REDIS_PORT"), "必须提供独立Redis端口"));
        environment.put("STRATUM_GATEWAY_REDIS_PASSWORD", System.getenv().getOrDefault("STRATUM_IT_REDIS_PASSWORD", ""));
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        var fixture = new GatewayProcessFixture(builder.start(), output, namespace, redis);
        try { fixture.awaitReady(); return fixture; }
        catch (Exception | Error failure) {
            fixture.preserveOutput = true;
            try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * 在30秒上界内解析实际随机端口并执行直接健康请求，不使用Thread.sleep或外部代理。
     * @throws Exception 进程退出、输出超限或就绪失败
     */
    private void awaitReady() throws Exception {
        long started = System.nanoTime();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).proxy(new java.net.ProxySelector() {
            /** {@inheritDoc} */
            @Override public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
            /** {@inheritDoc} */
            @Override public void connectFailed(URI uri, java.net.SocketAddress address, IOException failure) { }
        }).build()) {
            while (System.nanoTime() - started < Duration.ofSeconds(30).toNanos()) {
                if (process.waitFor(Duration.ofMillis(100))) throw new IllegalStateException("Gateway子进程提前退出，日志：" + output);
                if (port == 0) {
                    var matcher = STARTED_PORT.matcher(readOutput()); if (matcher.find()) port = Integer.parseInt(matcher.group(1));
                }
                if (port != 0) {
                    try {
                        var response = client.send(HttpRequest.newBuilder(endpoint("/actuator/health")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200 && response.body().contains("\"UP\"")) return;
                    } catch (IOException ignored) { /* 仅在就绪总时限内继续探测，异常不包含连接凭据。 */ }
                }
            }
        }
        throw new IllegalStateException("Gateway子进程就绪超时，日志：" + output);
    }

    /**
     * 读取最多1MiB自身输出，拒绝无限日志积累；暂存不完整UTF8尾部不影响ASCII端口/关联探测。
     * @return 当前UTF8输出
     * @throws IOException 输出无法读取或超界
     */
    private String readOutput() throws IOException {
        try (var input = Files.newInputStream(output)) {
            byte[] bytes = input.readNBytes(1048577);
            if (bytes.length > 1048576) throw new IOException("Gateway测试输出超界");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 获取随机端口上的固定业务入口，不允许调用方改变目标主机。
     * @param path 以斜线开头的相对路径
     * @return 本机Gateway URI
     */
    URI endpoint(String path) {
        if (!path.startsWith("/")) throw new IllegalArgumentException("测试入口必须为相对路径");
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /**
     * 核对实际访问日志含同一安全关联标识，不读取或打印令牌内容。
     * @param traceId 用例生成的关联标识
     * @return 是否存在该JSON属性
     * @throws IOException 输出读取失败
     */
    boolean hasTrace(String traceId) throws IOException { return readOutput().contains("\"traceId\":\"" + traceId + "\""); }

    /**
     * 返回本夹具唯一的限流空间，供组合测试精确操纵/核对自己的桶。
     * @return 随机命名空间
     */
    String namespace() { return namespace; }

    /**
     * 仅退出自身Java进程并删除四个确定的Redis键，成功退出后删除临时输出。
     * @throws Exception 进程未在上界退出、清理失败或线程被中断
     */
    @Override public void close() throws Exception {
        boolean stopped = false;
        try {
            process.destroy();
            if (!process.waitFor(Duration.ofSeconds(10))) { process.destroyForcibly(); if (!process.waitFor(Duration.ofSeconds(5))) throw new IllegalStateException("Gateway测试进程未退出"); }
            stopped = true;
        } catch (InterruptedException failure) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw failure; }
        finally {
            String prefix = "{" + namespace + "}:rate:";
            redis.delete(List.of(prefix + "tokens", prefix + "times", prefix + "expires", prefix + "policy"));
            if (stopped && !preserveOutput) Files.deleteIfExists(output);
        }
    }
}
