package com.patrick.stratum.gateway.route;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.config.HttpClientFactory;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.config.HttpClientSslConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.resources.ConnectionProvider;

/** 通过官方HTTP客户端扩展点限制连接、等待和网络超时，关闭额外重试及自动重定向。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProxyConfiguration.ProxyProperties.class)
public class GatewayProxyConfiguration {
    /**
     * 有界上游资源配置，每个固定上游最多配置数量的连接和等待者。
     * @param connectTimeout TCP连接超时
     * @param responseTimeout 响应读取及请求写入超时
     * @param maximumConnections 每上游连接上限
     * @param maximumPending 每上游等待者上限
     * @param acquireTimeout 连接池等待超时
     */
    @ConfigurationProperties("stratum.gateway.proxy")
    public record ProxyProperties(@DefaultValue("2s") Duration connectTimeout, @DefaultValue("5s") Duration responseTimeout,
            @DefaultValue("128") int maximumConnections, @DefaultValue("256") int maximumPending,
            @DefaultValue("1s") Duration acquireTimeout) {
        /**
         * 拒绝无界、过小或过大的资源及超时配置。
         * @param connectTimeout 50毫秒至30秒
         * @param responseTimeout 100毫秒至60秒
         * @param maximumConnections 1至1024
         * @param maximumPending 1至4096
         * @param acquireTimeout 50毫秒至10秒
         * @throws IllegalArgumentException 配置超界
         * @throws NullPointerException 超时缺失
         * @apiNote 示例：{@code new ProxyProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 128, 256, Duration.ofSeconds(1))}。
         */
        public ProxyProperties {
            Objects.requireNonNull(connectTimeout, "connectTimeout"); Objects.requireNonNull(responseTimeout, "responseTimeout");
            Objects.requireNonNull(acquireTimeout, "acquireTimeout");
            if (connectTimeout.compareTo(Duration.ofMillis(50)) < 0 || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || responseTimeout.compareTo(Duration.ofMillis(100)) < 0 || responseTimeout.compareTo(Duration.ofSeconds(60)) > 0
                    || acquireTimeout.compareTo(Duration.ofMillis(50)) < 0 || acquireTimeout.compareTo(Duration.ofSeconds(10)) > 0
                    || maximumConnections < 1 || maximumConnections > 1024 || maximumPending < 1 || maximumPending > 4096) {
                throw new IllegalArgumentException("上游连接、等待容量或超时配置超界");
            }
        }
    }

    /**
     * 创建随应用关闭的固定连接池，同时限制等待队列，不沿用框架的无限等待配置。
     * @param properties 已校验资源配置
     * @return Spring管理的连接提供者
     * @apiNote 示例：通过stratum.gateway.proxy.maximum-connections配置每个上游连接容量。
     */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider gatewayConnectionProvider(ProxyProperties properties) {
        return ConnectionProvider.builder("stratum-gateway").maxConnections(properties.maximumConnections())
                .pendingAcquireMaxCount(properties.maximumPending()).pendingAcquireTimeout(properties.acquireTimeout())
                .maxIdleTime(Duration.ofSeconds(30)).maxLifeTime(Duration.ofMinutes(5)).build();
    }

    /**
     * 复用官方TLS、代理及响应解码装配，只替换连接提供者。
     * @param properties 官方客户端属性
     * @param serverProperties 服务端属性
     * @param sslConfigurer 官方TLS装配器
     * @param customizers 客户端定制列表
     * @param provider 本应用的有界连接池
     * @return 官方工厂的有界资源实现
     * @apiNote 示例：由Gateway自动配置注入生成的HTTP客户端进行路由。
     */
    @Bean
    public HttpClientFactory gatewayHttpClientFactory(HttpClientProperties properties, ServerProperties serverProperties,
            HttpClientSslConfigurer sslConfigurer, List<HttpClientCustomizer> customizers,
            @Qualifier("gatewayConnectionProvider") ConnectionProvider provider) {
        return new BoundedHttpClientFactory(properties, serverProperties, sslConfigurer, customizers, provider);
    }

    /**
     * 配置各阶段网络超时，并禁止传输层额外重试及跟随重定向。
     * @param properties 已校验超时
     * @return 官方HTTP客户端定制器
     * @apiNote 示例：stratum.gateway.proxy.response-timeout=5s用于上游响应和写入超时。
     */
    @Bean
    public HttpClientCustomizer boundedProxyClientCustomizer(ProxyProperties properties) {
        return client -> client.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.responseTimeout()).disableRetry(true).followRedirect(false)
                .doOnConnected(connection -> connection.addHandlerLast(new WriteTimeoutHandler(properties.responseTimeout().toMillis(), TimeUnit.MILLISECONDS)));
    }

    /** 仅替换资源创建钩子，保留官方客户端装配行为，不访问业务存储。 */
    private static final class BoundedHttpClientFactory extends HttpClientFactory {
        private final ConnectionProvider provider;

        /**
         * 装配官方工厂并保留Spring管理的池；复制列表以供父工厂排序。
         * @param properties 官方客户端配置
         * @param serverProperties 服务端配置
         * @param sslConfigurer TLS装配器
         * @param customizers 官方定制器列表
         * @param provider Spring管理的池
         */
        private BoundedHttpClientFactory(HttpClientProperties properties, ServerProperties serverProperties,
                HttpClientSslConfigurer sslConfigurer, List<HttpClientCustomizer> customizers, ConnectionProvider provider) {
            super(properties, serverProperties, sslConfigurer, new ArrayList<>(customizers)); this.provider = provider;
        }

        /** {@inheritDoc} */
        @Override protected ConnectionProvider buildConnectionProvider(HttpClientProperties properties) { return provider; }
    }
}
