package com.patrick.stratum.gateway.route;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 固定两条ERP路由，精确Header灰度优先，稳定路由兜底，不实现上游节点选择或写入重试。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRoutingProperties.class)
public class GatewayRouteConfiguration {
    /**
     * 按固定域名及有界路径建立上游，关闭灰度后只保留稳定路由。
     * @param builder 官方路由构建器
     * @param properties 已校验的运行配置
     * @return 最多两条固定路由，未启用时为空
     * @apiNote 示例：{@code routes = configuration.erpRoutes(builder, properties)}；规则变更通过配置并重启生效。
     */
    @Bean
    public RouteLocator erpRoutes(RouteLocatorBuilder builder, GatewayRoutingProperties properties) {
        var routes = builder.routes();
        if (!properties.enabled()) return routes.build();
        String[] paths = properties.paths().toArray(String[]::new);
        if (properties.grayEnabled()) routes.route("erp-gray", route -> {
            var predicate = route.order(-1).path(paths);
            if (!properties.host().isEmpty()) predicate = predicate.and().host(properties.host());
            return predicate.and().header(properties.grayHeader(), Pattern.quote(properties.grayValue())).uri(properties.grayUpstream());
        });
        routes.route("erp-stable", route -> {
            var predicate = route.order(0).path(paths);
            if (!properties.host().isEmpty()) predicate = predicate.and().host(properties.host());
            return predicate.uri(properties.upstream());
        });
        return routes.build();
    }
}
