package com.patrick.stratum.job;

import com.patrick.stratum.application.event.OutboxReconciliationApplicationService;
import com.patrick.stratum.application.event.OutboxRelayApplicationService;
import com.patrick.stratum.domain.event.OutboxPublisher;
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
 * 显式启用的Relay处理器配置，不直接启动调度或改变共享暂停状态。
 * <p>示例：同时启用{@code stratum.jobs.enabled}、{@code stratum.messaging.enabled}和{@code stratum.jobs.relay.enabled}。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.jobs.relay", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxRelayJobConfiguration.Properties.class)
public class OutboxRelayJobConfiguration {
    /**
     * 只有显式启用任务和消息且实际发布端口存在时才登记固定处理器。
     * @param environment 启动配置
     * @param publishers 实际消息发布端口
     * @param relay 有界发布应用入口
     * @param reconciliation 有界消费凭据对账入口
     * @param properties 已校验的资源配置
     * @return 唯一固定Relay处理器
     * @throws IllegalStateException 任务、消息或实际发布端口未启用
     */
    @Bean
    OutboxRelayJobHandler outboxRelayJobHandler(Environment environment, ObjectProvider<OutboxPublisher> publishers,
            OutboxRelayApplicationService relay, OutboxReconciliationApplicationService reconciliation, Properties properties) {
        if (!environment.getProperty("stratum.jobs.enabled", Boolean.class, false)
                || !environment.getProperty("stratum.messaging.enabled", Boolean.class, false)
                || publishers.getIfAvailable() == null) {
            throw new IllegalStateException("周期Relay要求显式启用任务和实际消息发布端口");
        }
        return new OutboxRelayJobHandler(relay, reconciliation, properties);
    }

    /**
     * 固定周期及每批资源边界；不含会覆盖共享状态的本地暂停开关。
     * @param cron UTC Cron表达式，默认每分钟
     * @param batchSize 每批最多发布条数
     * @param reconciliationLimit 每批最多扫描消费凭据数
     * @param timeoutSec 实际任务处理时限秒数
     */
    @ConfigurationProperties("stratum.jobs.relay")
    public record Properties(@DefaultValue("0 0/1 * * * ?") String cron, @DefaultValue("16") int batchSize,
            @DefaultValue("16") int reconciliationLimit, @DefaultValue("30") int timeoutSec) {
        /**
         * 在创建处理器前校验计划与资源上限。
         * @param cron 最长120字符的合法Quartz表达式
         * @param batchSize 1至128条
         * @param reconciliationLimit 1至128条
         * @param timeoutSec 1至300秒
         * @throws IllegalArgumentException 计划或资源越界
         * @apiNote 示例：{@code new Properties("0 0/1 * * * ?", 16, 16, 30)}。
         */
        public Properties {
            if (cron == null || cron.length() > 120 || cron.chars().anyMatch(Character::isISOControl)
                    || !CronExpression.isValidExpression(cron) || batchSize < 1 || batchSize > 128
                    || reconciliationLimit < 1 || reconciliationLimit > 128 || timeoutSec < 1 || timeoutSec > 300) {
                throw new IllegalArgumentException("周期Relay计划或资源配置不合法");
            }
        }
    }
}
