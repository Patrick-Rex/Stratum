package com.patrick.stratum.gateway;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 共用真实本机上游与独立网关上下文，所有资源由各测试用例按引用顺序关闭。 */
abstract class GatewayHttpIntegrationTest {
    /**
     * 本机上游夹具，不接受外部目标，使用单个默认HTTP执行线程。
     * @param server 独立HTTP服务
     * @param address 随机端口根地址
     * @param requests 实际收到的请求数
     */
    protected record Upstream(HttpServer server, URI address, AtomicInteger requests) implements AutoCloseable {
        /** 停止仅本夹具创建的HTTP服务。 */
        @Override public void close() { server.stop(0); }
    }

    /**
     * 创建独立回显服务，输入读取上限4KiB；未指定执行器时使用JDK单线程默认执行器。
     * @param name 固定上游标识
     * @return 可关闭上游
     * @throws Exception 端口或HTTP初始化失败
     */
    protected Upstream upstream(String name) throws Exception {
        return upstream(exchange -> {
                byte[] input = exchange.getRequestBody().readNBytes(4097);
                if (input.length > 4096) { exchange.sendResponseHeaders(413, -1); return; }
                var received = new java.util.LinkedHashMap<String, String>();
                for (String header : List.of(CorrelationIdPolicy.TRACE_HEADER, CorrelationIdPolicy.REQUEST_HEADER,
                        "traceparent", "tracestate", "Authorization", "X-API-Version", "Idempotency-Key")) {
                    received.put(header, java.util.Objects.toString(exchange.getRequestHeaders().getFirst(header), ""));
                }
                byte[] output = JsonMapper.builder().build().writeValueAsBytes(Map.of("source", name,
                        "path", exchange.getRequestURI().toASCIIString(), "method", exchange.getRequestMethod(),
                        "body", new String(input, StandardCharsets.UTF_8), "headers", received));
                exchange.getResponseHeaders().set(CorrelationIdPolicy.TRACE_HEADER, "upstream-override");
                exchange.getResponseHeaders().set(CorrelationIdPolicy.REQUEST_HEADER, "upstream-request");
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, output.length);
                exchange.getResponseBody().write(output);
        });
    }

    /**
     * 创建可注入故障行为的计数服务，由共用包装器关闭每个交换。
     * @param handler 有界测试处理器
     * @return 可关闭上游
     * @throws Exception 端口或服务初始化失败
     */
    protected Upstream upstream(HttpHandler handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); var requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start(); return new Upstream(server, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), requests);
    }

    /**
     * 使用CLI配置启动独立随机端口网关，配置变化通过新上下文启动验证。
     * @param stable 稳定上游
     * @param gray 灰度上游
     * @param enabled 是否转发
     * @param grayEnabled 是否灰度
     * @param host 可选精确域名
     * @param extraArguments 额外CLI配置
     * @return 必须由调用方关闭的上下文
     */
    protected ConfigurableApplicationContext gateway(Upstream stable, Upstream gray, boolean enabled, boolean grayEnabled, String host, String... extraArguments) {
        return gateway(stable.address(), gray.address(), enabled, grayEnabled, host, extraArguments);
    }

    /**
     * 通过固定URI启动网关，允许使用受测试持有但未监听的本机端口。
     * @param stable 稳定上游URI
     * @param gray 灰度上游URI
     * @param enabled 是否转发
     * @param grayEnabled 是否灰度
     * @param host 可选精确域名
     * @param extraArguments 额外CLI配置
     * @return 可关闭上下文
     */
    protected ConfigurableApplicationContext gateway(URI stable, URI gray, boolean enabled, boolean grayEnabled, String host, String... extraArguments) {
        return startGateway(true, stable, gray, enabled, grayEnabled, host, extraArguments);
    }

    /**
     * 启动使用真实Redis限流器的网关，调用方必须提供独立Redis配置。
     * @param stable 稳定上游
     * @param gray 灰度上游
     * @param extraArguments 独立Redis及令牌策略CLI参数
     * @return 可关闭网关上下文
     */
    protected ConfigurableApplicationContext realRateGateway(Upstream stable, Upstream gray, String... extraArguments) {
        return startGateway(false, stable.address(), gray.address(), true, true, "", extraArguments);
    }

    /**
     * 组装固定启动参数，仅普通协议测试注入显式限流替身，不提供生产配置旁路。
     * @param protocolStub 是否使用协议测试替身
     * @param stable 稳定上游地址
     * @param gray 灰度上游地址
     * @param enabled 是否转发
     * @param grayEnabled 是否灰度
     * @param host 可选入口域名
     * @param extraArguments 额外CLI参数
     * @return 可关闭上下文
     */
    private ConfigurableApplicationContext startGateway(boolean protocolStub, URI stable, URI gray, boolean enabled, boolean grayEnabled, String host, String... extraArguments) {
        var arguments = new java.util.ArrayList<>(List.of( "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--stratum.gateway.routing.enabled=" + enabled,
                "--stratum.gateway.routing.upstream=" + stable, "--stratum.gateway.routing.gray-upstream=" + gray,
                "--stratum.gateway.routing.gray-enabled=" + grayEnabled, "--stratum.gateway.routing.gray-value=v2.0", "--stratum.gateway.routing.host=" + host));
        arguments.addAll(List.of(extraArguments));
        var application = new SpringApplication(GatewayApplication.class);
        if (protocolStub) application.addInitializers(context -> {
            var generic = (org.springframework.context.support.GenericApplicationContext) context;
            generic.registerBean("protocolTestLimiter", com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.class, () -> {
                var limiter = org.mockito.Mockito.mock(com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.class);
                org.mockito.Mockito.when(limiter.consume(org.mockito.ArgumentMatchers.any(java.net.InetAddress.class)))
                        .thenReturn(reactor.core.publisher.Mono.just(new com.patrick.stratum.gateway.ratelimit.RedisGatewayRateLimiter.Decision(true, 40, 0, false)));
                return limiter;
            }, definition -> definition.setPrimary(true));
        });
        return application.run(arguments.toArray(String[]::new));
    }

    /**
     * 向已启动网关发送有限时长请求，调用后关闭客户端。
     * @param gateway 随机端口上下文
     * @param path 固定请求路径
     * @param headers 测试头
     * @param body 可空POST体
     * @return 原始HTTP响应
     * @throws Exception 请求失败
     */
    protected HttpResponse<String> request(ConfigurableApplicationContext gateway, String path, Map<String, String> headers, String body) throws Exception {
        var request = HttpRequest.newBuilder(gatewayUri(gateway, path)).timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        if (body == null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body));
        return send(request.build());
    }

    /**
     * 执行已构造的有界请求并关闭客户端。
     * @param request 有请求超时的测试请求
     * @return HTTP响应
     * @throws Exception 连接或读取失败
     */
    protected HttpResponse<String> send(HttpRequest request) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 获取随机端口网关的固定请求URI。
     * @param gateway 网关上下文
     * @param path 固定路径
     * @return 本机请求URI
     */
    protected URI gatewayUri(ConfigurableApplicationContext gateway, String path) {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /**
     * 检查回显来自预期固定上游。
     * @param expected 标识
     * @param response HTTP响应
     */
    protected void assertSource(String expected, HttpResponse<String> response) {
        assertEquals(200, response.statusCode()); assertEquals(expected, JsonMapper.builder().build().readTree(response.body()).path("source").asText());
    }
}
