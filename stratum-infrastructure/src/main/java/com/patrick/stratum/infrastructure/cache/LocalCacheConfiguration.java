package com.patrick.stratum.infrastructure.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/** 装配有界且有 TTL 的本地缓存客户端，业务缓存一致性在后续节点实现。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalCacheProperties.class)
public class LocalCacheConfiguration {

    /**
     * 缓存序列化值，避免共享可变业务对象；命中统计供后续观测使用。
     * @param properties 容量与失效时间配置
     * @return 本地缓存客户端
     * @apiNote 由 Spring 装配，缓存适配器通过名称 stratumLocalCache 注入。
     */
    @Bean
    public Cache<String, String> stratumLocalCache(LocalCacheProperties properties) {
        return Caffeine.newBuilder().maximumSize(properties.maximumSize()).expireAfterWrite(properties.ttl())
                .recordStats().build();
    }
}
