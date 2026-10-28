package com.patrick.stratum.job;

import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.TimeZone;

import com.patrick.stratum.application.event.OutboxRelayControlApplicationService;
import com.patrick.stratum.application.job.JobExecutionApplicationService;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 在Quartz启动前幂等登记固定代码触发器；不覆盖不一致存储或动态创建处理器。
 * <p>示例：stratum.jobs.enabled=true且存在已登记JobHandler时，创建每代码到期轮询及可选UTC Cron。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stratum.jobs", name = "enabled", havingValue = "true")
public class QuartzTriggerConfiguration {
    static final String GROUP = "stratum.fixed.jobs";

    /**
     * 每次触发创建独立Spring代理，避免中断信号混入其他执行。
     * @param jobs Application状态入口
     * @param registry 已核验处理器注册表
     * @param executor 本实例私有执行器
     * @param control Relay共享暂停准入
     * @param ordinary 普通任务调度器
     * @param relay 可选的Relay调度器
     * @return 唯一触发实例，由现有JobFactory查找其代理
     * @throws SchedulerException 无法读取调度器身份
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    QuartzDispatchJob quartzDispatchJob(JobExecutionApplicationService jobs, JobHandlerRegistry registry, JobTaskExecutor executor,
            OutboxRelayControlApplicationService control, Scheduler ordinary,
            @Qualifier("relayJobScheduler") ObjectProvider<Scheduler> relay) throws SchedulerException {
        var relayScheduler = relay.getIfAvailable();
        return new QuartzDispatchJob(jobs, registry, executor, control, ordinary.getSchedulerName(),
                relayScheduler == null ? null : relayScheduler.getSchedulerName());
    }

    /**
     * 创建停止顺序协调器；Scheduler默认最大phase先standby，本组件随后清理实际任务。
     * @param scheduler 所属调度器
     * @param relay 可选的Relay调度器
     * @param executor 两组调度器共享的实际工作执行器
     * @param registry 固定处理器注册表
     * @return 停止于Bean销毁前的有界生命周期
     */
    @Bean
    QuartzRuntimeLifecycle quartzRuntimeLifecycle(Scheduler scheduler,
            @Qualifier("relayJobScheduler") ObjectProvider<Scheduler> relay, JobTaskExecutor executor, JobHandlerRegistry registry) {
        var schedulers = new java.util.ArrayList<Scheduler>();
        schedulers.add(scheduler);
        var relayScheduler = relay.getIfAvailable();
        if (relayScheduler != null) schedulers.add(relayScheduler);
        if (relayScheduler == null && !registry.relayCodes().isEmpty()) throw new IllegalStateException("Relay处理器缺少专用调度器");
        return new QuartzRuntimeLifecycle(schedulers, executor,
                registry.relayCodes().isEmpty() ? List.of() : List.of(relayScheduler));
    }

    /**
     * 按固定代码隔离普通和Relay调度器，在自动启动前核验持久化目标。
     * @param scheduler 普通持久化调度器
     * @param relay 可选的待机Relay调度器
     * @param registry 启动时固定处理器快照
     * @param lifecycle 普通调度器收尾监听
     * @return Spring单例初始化完成回调
     */
    @Bean
    SmartInitializingSingleton fixedQuartzTriggers(Scheduler scheduler,
            @Qualifier("relayJobScheduler") ObjectProvider<Scheduler> relay,
            JobHandlerRegistry registry, QuartzRuntimeLifecycle lifecycle) {
        return () -> {
            try {
                scheduler.getListenerManager().addJobListener(lifecycle);
                registerAll(scheduler, registry, registry.ordinaryCodes());
                var relayScheduler = relay.getIfAvailable();
                if (relayScheduler == null && !registry.relayCodes().isEmpty()) {
                    throw new IllegalStateException("Relay处理器缺少专用调度器");
                }
                if (relayScheduler != null) {
                    relayScheduler.getListenerManager().addJobListener(lifecycle);
                    registerAll(relayScheduler, registry, registry.relayCodes());
                }
            } catch (SchedulerException failure) { throw new IllegalStateException("固定任务触发器登记失败"); }
        };
    }

    /**
     * 只核验和登记当前调度器所属代码，不覆盖多余或不一致目标。
     * @param scheduler 对应的持久化调度器
     * @param registry 固定处理器注册表
     * @param codes 该调度器允许的代码集合
     * @throws SchedulerException Quartz读取或登记失败
     */
    private void registerAll(Scheduler scheduler, JobHandlerRegistry registry, List<String> codes) throws SchedulerException {
        var allowed = new HashSet<JobKey>();
        for (String code : codes) {
            allowed.add(new JobKey("run." + code, GROUP));
            if (registry.periodicDefinition(code).isPresent()) allowed.add(new JobKey("schedule." + code, GROUP));
        }
        if (!allowed.containsAll(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)))
                || scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(GROUP)).stream()
                        .anyMatch(key -> !allowed.contains(new JobKey(key.getName(), key.getGroup())))) {
            throw new IllegalStateException("已存储固定任务包含未登记目标");
        }
        for (String code : codes) {
            var run = detail(code, "run");
            var poll = TriggerBuilder.newTrigger().withIdentity(run.getKey().getName(), GROUP).forJob(run)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).repeatForever()
                            .withMisfireHandlingInstructionNextWithRemainingCount()).build();
            register(scheduler, run, poll);
            var periodic = registry.periodicDefinition(code);
            if (periodic.isPresent()) {
                var schedule = detail(code, "schedule");
                var cron = TriggerBuilder.newTrigger().withIdentity(schedule.getKey().getName(), GROUP).forJob(schedule)
                        .withSchedule(CronScheduleBuilder.cronSchedule(periodic.orElseThrow().cron().orElseThrow())
                                .inTimeZone(TimeZone.getTimeZone("UTC")).withMisfireHandlingInstructionDoNothing()).build();
                register(scheduler, schedule, cron);
            }
        }
    }

    /**
     * 构造持久化固定目标，只保存安全代码和固定驱动模式，不包含参数或处理器类名输入。
     * @param code 已登记代码
     * @param mode run或schedule
     * @return 固定Spring作业描述
     */
    private JobDetail detail(String code, String mode) {
        return JobBuilder.newJob(QuartzDispatchJob.class).withIdentity(mode + "." + code, GROUP)
                .usingJobData("jobCode", code).usingJobData("mode", mode).storeDurably().build();
    }

    /**
     * 原子新增作业和触发器；已存在时核验而不覆盖，支持双实例并发启动与重启。
     * @param scheduler 所属共享存储
     * @param expected 固定目标
     * @param trigger 固定计划
     * @throws SchedulerException Quartz存储失败
     * @throws IllegalStateException 同键持久化定义不一致，需显式修正配置或存储
     */
    private void register(Scheduler scheduler, JobDetail expected, Trigger trigger) throws SchedulerException {
        try { scheduler.scheduleJob(expected, Set.of(trigger), false); }
        catch (ObjectAlreadyExistsException exists) {
            var actual = scheduler.getJobDetail(expected.getKey());
            var stored = scheduler.getTrigger(trigger.getKey());
            if (actual == null || actual.getJobClass() != QuartzDispatchJob.class || !actual.isDurable() || actual.requestsRecovery()
                    || !actual.getJobDataMap().equals(expected.getJobDataMap()) || stored == null
                    || !stored.getJobKey().equals(expected.getKey()) || !stored.getJobDataMap().isEmpty()
                    || scheduler.getTriggersOfJob(expected.getKey()).size() != 1
                    || !sameSchedule(trigger, stored)) throw new IllegalStateException("已存储固定任务定义不一致");
        }
    }

    /**
     * 核验触发器类型、间隔/Cron和UTC及misfire策略，不将首次启动时刻当作配置冲突。
     * @param expected 启动时期望计划
     * @param actual 已持久化计划
     * @return 完全相同的固定计划时为true
     */
    private boolean sameSchedule(Trigger expected, Trigger actual) {
        if (expected.getMisfireInstruction() != actual.getMisfireInstruction()
                || actual.getEndTime() != null || actual.getCalendarName() != null || expected.getPriority() != actual.getPriority()) return false;
        if (expected instanceof SimpleTrigger one && actual instanceof SimpleTrigger two) {
            return one.getRepeatInterval() == two.getRepeatInterval() && one.getRepeatCount() == two.getRepeatCount();
        }
        if (expected instanceof CronTrigger one && actual instanceof CronTrigger two) {
            return one.getCronExpression().equals(two.getCronExpression()) && one.getTimeZone().getID().equals(two.getTimeZone().getID());
        }
        return false;
    }
}
