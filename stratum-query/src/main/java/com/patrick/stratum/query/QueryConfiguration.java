package com.patrick.stratum.query;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/** 在启动阶段构造不可变查询路由，重复注册直接阻止错误应用启动。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryConfiguration.Settings.class)
public class QueryConfiguration {
    /**
     * 收集 Spring 管理的具体查询处理器并装配统一入口。
     * @param handlers 处理器提供者，允许空集合
     * @param metrics 指标注册表
     * @param settings 慢查询配置
     * @return 查询入口
     * @apiNote 由 Spring 装配，HTTP 读接口注入 QueryGateway。
     */
    @Bean
    public QueryGateway queryGateway(ObjectProvider<QueryHandler<?, ?>> handlers, MeterRegistry metrics, Settings settings) {
        return new QueryGateway(handlers.orderedStream().toList(), metrics, Duration.ofMillis(settings.slowThresholdMs()));
    }

    /**
     * 查询观测配置，默认慢查询阈值 300 毫秒。
     * @param slowThresholdMs 慢查询阈值，1 到 60000 毫秒
     */
    @ConfigurationProperties("observability.query")
    public record Settings(@DefaultValue("300") long slowThresholdMs) {
        /**
         * 拒绝无效阈值，动态刷新在 Nacos 节点接入。
         * @param slowThresholdMs 慢查询毫秒阈值
         * @apiNote 示例：new Settings(300)。
         */
        public Settings {
            if (slowThresholdMs < 1 || slowThresholdMs > 60_000) { throw new IllegalArgumentException("慢查询阈值越界"); }
        }
    }
}
