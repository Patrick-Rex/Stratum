package com.patrick.stratum.infrastructure.job;

import java.util.UUID;
import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.quartz.Job;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * 显式启用Quartz JDBC集群与ShedLock数据库锁，业务执行仍由Job和Application负责。
 * <p>示例：设置stratum.jobs.enabled=true；表结构由Flyway先行创建。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.jobs", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SchedulerConfiguration.Properties.class)
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M", proxyTargetClass = true)
public class SchedulerConfiguration {
    /**
     * 创建数据库UTC时间锁与有界自动续期，内部执行器不参与应用Executor自动装配。
     * @param dataSource 应用数据源，业务工作单元不会传入锁事务
     * @param properties 本实例资源容量与命名空间
     * @return 随上下文关闭的数据库锁提供者
     */
    @Bean(destroyMethod = "close")
    LockProvider jobLockProvider(DataSource dataSource, Properties properties) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        var database = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName("stratum_shedlock").usingDbTime().build());
        return new BoundedJobLockProvider(database, properties.workers(), properties.name());
    }

    /**
     * 装配共享JDBC JobStore；固定资源和配置边界，不在此注册任何任务。
     * @param dataSource Flyway管理的数据源
     * @param properties 调度器名称与线程上限
     * @param beanFactory 只查找已登记Spring Job及其注解代理
     * @return 随上下文启动和关闭的独立调度器工厂
     */
    @Bean
    @Primary
    @DependsOnDatabaseInitialization
    SchedulerFactoryBean jobScheduler(DataSource dataSource, Properties properties, BeanFactory beanFactory) {
        return scheduler(dataSource, properties.name(), properties.workers(), beanFactory, true);
    }

    /**
     * 显式启用时装配专用数据库集群调度器；保持待机直到Relay目标与资格核验完成。
     * @param dataSource 与普通任务共享的Flyway数据源
     * @param properties 普通任务命名空间，用于派生不重叠的Relay名称
     * @param beanFactory 仅查找已登记的Spring作业
     * @return 一个固定工作线程的独立调度器工厂
     */
    @Bean
    @ConditionalOnProperty(prefix = "stratum.jobs.relay", name = "enabled", havingValue = "true")
    @DependsOnDatabaseInitialization
    SchedulerFactoryBean relayJobScheduler(DataSource dataSource, Properties properties, BeanFactory beanFactory) {
        return scheduler(dataSource, "relay:" + properties.name(), 1, beanFactory, false);
    }

    /**
     * 显式启用时创建隔离的死信采集调度器，保持待机直至后续节点核验并启动。
     * @param dataSource 共享Flyway数据源
     * @param properties 普通调度器命名空间
     * @param beanFactory 已登记作业的组件工厂
     * @return 一个固定线程、独立名称且默认待机的调度器工厂
     */
    @Bean
    @ConditionalOnProperty(prefix = "stratum.jobs.deadletter-sampling", name = "enabled", havingValue = "true")
    @DependsOnDatabaseInitialization
    SchedulerFactoryBean deadLetterSamplingScheduler(DataSource dataSource, Properties properties, BeanFactory beanFactory) {
        return scheduler(dataSource, "backlog:" + properties.name(), 1, beanFactory, false);
    }

    /**
     * 创建命名空间独立的集群调度器；业务目标登记和启动由后续配置协调。
     * @param dataSource Flyway管理的数据源
     * @param name 数据库中的调度器命名空间
     * @param workers 固定Quartz工作线程数量
     * @param beanFactory 只查找已登记Spring作业
     * @param autoStartup 是否随上下文自动启动
     * @return 本上下文持有的调度器工厂
     */
    private SchedulerFactoryBean scheduler(DataSource dataSource, String name, int workers, BeanFactory beanFactory, boolean autoStartup) {
        String instance = UUID.randomUUID().toString();
        var quartz = new java.util.Properties();
        quartz.setProperty("org.quartz.scheduler.instanceId", instance);
        quartz.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        quartz.setProperty("org.quartz.scheduler.interruptJobsOnShutdown", "true");
        quartz.setProperty("org.quartz.scheduler.interruptJobsOnShutdownWithWait", "true");
        quartz.setProperty("org.quartz.threadPool.threadCount", Integer.toString(workers));
        quartz.setProperty("org.quartz.threadPool.threadNamePrefix", "stratum-quartz-" + instance);
        quartz.setProperty("org.quartz.threadPool.threadsInheritContextClassLoaderOfInitializingThread", "true");
        quartz.setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        quartz.setProperty("org.quartz.jobStore.tablePrefix", "STRATUM_QRTZ_");
        quartz.setProperty("org.quartz.jobStore.useProperties", "true");
        quartz.setProperty("org.quartz.jobStore.isClustered", "true");
        quartz.setProperty("org.quartz.jobStore.clusterCheckinInterval", "5000");
        quartz.setProperty("org.quartz.jobStore.misfireThreshold", "60000");
        quartz.setProperty("org.quartz.scheduler.dbFailureRetryInterval", "5000");
        quartz.setProperty("org.quartz.scheduler.batchTriggerAcquisitionMaxCount", "1");
        var factory = new SchedulerFactoryBean();
        factory.setSchedulerName(name);
        factory.setDataSource(dataSource);
        factory.setQuartzProperties(quartz);
        factory.setJobFactory(new RegisteredJobFactory(beanFactory));
        factory.setExposeSchedulerInRepository(false);
        factory.setWaitForJobsToCompleteOnShutdown(false);
        factory.setAutoStartup(autoStartup);
        return factory;
    }

    /**
     * 不允许从持久化类名任意实例化Job，只能调用已登记并可受ShedLock代理的组件。
     * @param beans 当前Spring组件工厂
     */
    private record RegisteredJobFactory(BeanFactory beans) implements JobFactory {
        /** {@inheritDoc} */
        @Override
        public Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) throws SchedulerException {
            try {
                return beans.getBean(bundle.getJobDetail().getJobClass());
            } catch (BeansException failure) {
                throw new SchedulerException("调度作业必须登记为唯一Spring组件");
            }
        }
    }

    /**
     * 有界调度器配置，名称用于共享数据库命名空间，实例标识每次独立生成。
     * <p>示例：stratum.jobs.name=stratum；workers=2。</p>
     * @param name 同一集群使用的固定名称
     * @param workers 本实例Quartz固定工作线程数
     */
    @ConfigurationProperties("stratum.jobs")
    public record Properties(@DefaultValue("stratum") String name, @DefaultValue("2") int workers) {
        /**
         * 在创建线程和访问数据库之前校验名称和容量。
         * @param name 调度器命名空间
         * @param workers 固定工作线程数，允许1至8
         * @throws IllegalArgumentException 名称或线程数超出边界
         */
        public Properties {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || workers < 1 || workers > 8) {
                throw new IllegalArgumentException("调度器名称或工作线程数不合法");
            }
        }
    }
}
