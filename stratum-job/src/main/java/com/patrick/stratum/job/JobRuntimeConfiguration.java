package com.patrick.stratum.job;

import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 显式启用的任务执行装配，不在此创建Quartz触发或业务处理器。
 * <p>示例：stratum.jobs.enabled=true；workers与已有Quartz容量共用配置。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.jobs", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({JobRuntimeConfiguration.Properties.class, JobMetrics.Settings.class})
public class JobRuntimeConfiguration {
    /**
     * 快照已登记处理器；没有处理器时不创造默认任务。
     * @param handlers Spring已登记处理器
     * @return 有界固定注册表
     */
    @Bean
    JobHandlerRegistry jobHandlerRegistry(ObjectProvider<JobHandler> handlers) {
        return new JobHandlerRegistry(handlers.orderedStream().toList());
    }

    /**
     * 根据固定处理器注册表和有界阈值建立指标组件。
     * @param metrics 应用指标注册表
     * @param registry 已验证任务代码
     * @param settings 慢任务配置
     * @return 只允许固定代码及结果的任务指标
     */
    @Bean
    JobMetrics jobMetrics(MeterRegistry metrics, JobHandlerRegistry registry, JobMetrics.Settings settings) {
        return new JobMetrics(metrics, registry, settings);
    }

    /**
     * 创建可被ShedLock方法代理包装的无执行共享状态工作器。
     * @param jobs Application任务状态入口
     * @param registry 固定处理器注册表
     * @param relayControl Relay共享暂停准入应用入口
     * @param metrics 已配置的任务指标
     * @param settings 慢任务阈值
     * @return 由Spring代理管理的工作器
     */
    @Bean
    ProtectedJobWorker protectedJobWorker(JobExecutionApplicationService jobs, JobHandlerRegistry registry,
            OutboxRelayControlApplicationService relayControl, JobMetrics metrics, JobMetrics.Settings settings) {
        return new ProtectedJobWorker(jobs, registry, relayControl, metrics, settings);
    }

    /**
     * 明确依赖锁提供者，确保关闭顺序为实际执行器、锁续期、数据源。
     * @param worker 已代理工作器
     * @param registry 固定处理器
     * @param properties 本实例容量
     * @return 无内存等待队列、仅实际执行时创建线程的执行器
     */
    @Bean(destroyMethod = "close")
    @DependsOn("jobLockProvider")
    JobTaskExecutor jobTaskExecutor(ProtectedJobWorker worker, JobHandlerRegistry registry, Properties properties) {
        return new JobTaskExecutor(worker, registry, properties.workers());
    }

    /**
     * 值配置：与Quartz共用本实例工作容量，不影响全局应用Executor。
     * @param workers 最大1至8个在途执行
     */
    @ConfigurationProperties("stratum.jobs")
    public record Properties(@DefaultValue("2") int workers) {
        /**
         * 校验资源数量，不允许无限创建虚拟线程。
         * @param workers 1至8
         * @throws IllegalArgumentException 容量越界
         * @apiNote 示例：stratum.jobs.workers=2。
         */
        public Properties {
            if (workers < 1 || workers > 8) throw new IllegalArgumentException("任务执行容量不合法");
        }
    }
}
