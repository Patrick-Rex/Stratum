package com.patrick.stratum.job;

import com.patrick.stratum.application.job.DeadLetterReplayExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 显式启用的死信重放处理器装配，不自行启动Quartz或发送消息。
 * <p>示例：同时设置{@code stratum.jobs.enabled}和{@code stratum.jobs.replay.enabled}。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.jobs.replay", name = "enabled", havingValue = "true")
public class DeadLetterReplayJobConfiguration {
    /**
     * 仅在任务执行显式启用时连接已有授权及租约核验入口。
     * @param environment 当前配置
     * @param execution 已有执行应用服务
     * @return 固定一次性处理器
     * @throws IllegalStateException 尚未启用任务执行
     */
    @Bean
    DeadLetterReplayJobHandler deadLetterReplayJobHandler(Environment environment, DeadLetterReplayExecutionService execution) {
        if (!environment.getProperty("stratum.jobs.enabled", Boolean.class, false)) {
            throw new IllegalStateException("重放任务处理器要求显式启用任务执行");
        }
        return new DeadLetterReplayJobHandler(lease -> execution.execute(lease));
    }
}
