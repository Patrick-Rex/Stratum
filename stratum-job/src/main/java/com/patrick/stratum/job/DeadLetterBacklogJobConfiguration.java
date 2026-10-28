package com.patrick.stratum.job;

import java.time.Duration;

import com.patrick.stratum.application.event.DeadLetterBacklogSamplingService;
import com.patrick.stratum.domain.event.DeadLetterBacklogProbe;
import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import org.quartz.CronExpression;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 显式启用死信可见数固定处理器，依赖独立调度器及共享快照配置。
 * <p>示例：同时启用jobs、messaging、deadletter指标和deadletter-sampling。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.jobs.deadletter-sampling", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeadLetterBacklogJobConfiguration.Properties.class)
public class DeadLetterBacklogJobConfiguration {
    /**
     * 校验各项显式开关及实际端口后装配固定处理器，不直接启动调度器。
     * @param environment 实例配置
     * @param probes Broker查询端口
     * @param stores 共享快照端口
     * @param properties 有界周期配置
     * @return 固定采集处理器
     * @throws IllegalStateException 前置开关或端口缺失
     */
    @Bean DeadLetterBacklogJobHandler deadLetterBacklogJobHandler(Environment environment,
            ObjectProvider<DeadLetterBacklogProbe> probes, ObjectProvider<DeadLetterBacklogStore> stores, Properties properties) {
        if (!environment.getProperty("stratum.jobs.enabled", Boolean.class, false)
                || !environment.getProperty("stratum.messaging.enabled", Boolean.class, false)
                || !environment.getProperty("stratum.observability.deadletter.enabled", Boolean.class, false)) {
            throw new IllegalStateException("死信周期采集要求显式启用任务、消息及共享指标");
        }
        var probe = probes.getIfAvailable(); var store = stores.getIfAvailable();
        if (probe == null || store == null) throw new IllegalStateException("死信周期采集缺少查询或共享状态端口");
        return new DeadLetterBacklogJobHandler(new DeadLetterBacklogSamplingService(probe, store, properties.ttl()),
                properties.cron(), properties.timeoutSec());
    }

    /**
     * 固定周期、执行预算和共享快照有效期；UTC解释由Quartz触发器负责。
     * @param cron Quartz Cron表达式
     * @param timeoutSec 任务中断预算秒数
     * @param ttl 共享快照有效期
     */
    @ConfigurationProperties("stratum.jobs.deadletter-sampling")
    public record Properties(@DefaultValue("0 0/5 * * * ?") String cron, @DefaultValue("60") int timeoutSec,
            @DefaultValue("10m") Duration ttl) {
        /**
         * 在创建任务前校验计划和资源边界。
         * @param cron 长度至多120的合法表达式
         * @param timeoutSec 1至300秒
         * @param ttl 1秒至10分钟
         * @throws IllegalArgumentException 配置不合法
         * @apiNote 示例：{@code new Properties("0 0/5 * * * ?", 60, Duration.ofMinutes(10))}。
         */
        public Properties {
            if (cron == null || cron.length() > 120 || cron.chars().anyMatch(Character::isISOControl)
                    || !CronExpression.isValidExpression(cron) || timeoutSec < 1 || timeoutSec > 300
                    || ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("死信采集计划或资源配置不合法");
            }
        }
    }
}
