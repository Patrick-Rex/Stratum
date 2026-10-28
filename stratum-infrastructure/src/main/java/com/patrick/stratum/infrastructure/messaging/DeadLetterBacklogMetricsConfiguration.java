package com.patrick.stratum.infrastructure.messaging;

import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 显式启用共享死信快照读取与低基数Gauge，不启用消息消费或周期采集。
 * <p>示例：设置{@code stratum.observability.deadletter.enabled=true}。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.observability.deadletter", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeadLetterBacklogMetricsConfiguration.Properties.class)
public class DeadLetterBacklogMetricsConfiguration {
    /**
     * 为当前实例建立共享快照端口；各实例必须使用相同可信命名空间。
     * @param redis Redis客户端
     * @param properties 已校验配置
     * @return 共享存储适配器
     */
    @Bean DeadLetterBacklogStore deadLetterBacklogStore(StringRedisTemplate redis, Properties properties) {
        return new RedisDeadLetterBacklogStore(redis, properties.namespace());
    }

    /**
     * 固定注册两个组并由容器持有读取对象。
     * @param registry 应用注册表
     * @param store 共享快照端口
     * @return 指标持有者
     */
    @Bean DeadLetterBacklogMetrics deadLetterBacklogMetrics(MeterRegistry registry, DeadLetterBacklogStore store) {
        return new DeadLetterBacklogMetrics(registry, store);
    }

    /**
     * 多实例共享采样命名空间；不同环境或Broker虚拟主机应隔离。
     * @param namespace 1至48个小写字母、数字或连字符，以字母开头
     */
    @ConfigurationProperties("stratum.observability.deadletter")
    public record Properties(@DefaultValue("stratum") String namespace) {
        /**
         * 在装配前拒绝非法命名空间。
         * @param namespace 固定可信命名空间
         * @throws IllegalArgumentException 命名空间不合法
         * @apiNote 示例：{@code new Properties("stratum")}。
         */
        public Properties {
            if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("指标命名空间不合法");
        }
    }
}
