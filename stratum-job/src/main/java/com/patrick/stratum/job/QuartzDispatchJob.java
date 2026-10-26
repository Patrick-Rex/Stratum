package com.patrick.stratum.job;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * 固定处理器的Quartz驱动；只登记计划或查询一个到期候选，不保存内存重试队列。
 * <p>示例：由QuartzTriggerConfiguration登记并通过Spring原型代理执行。</p>
 */
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class QuartzDispatchJob implements InterruptableJob {
    private final JobExecutionApplicationService jobs;
    private final JobHandlerRegistry registry;
    private final JobTaskExecutor executor;
    private final ReentrantLock phase = new ReentrantLock();
    private Thread owner;
    private boolean stopped;

    /**
     * 每个原型只执行一次；调度锁与实际处理锁分离，所有执行资格仍由数据库核验。
     * @param context Quartz提供的持久化固定代码与计划时刻
     * @throws JobExecutionException 驱动失败；不携带参数或异常原文，也不立即内存重试
     * @apiNote 示例：只由已登记的Quartz JobDetail触发本方法。
     */
    @Override
    @SchedulerLock(name = "#{'lock:job:trigger:' + #p0.jobDetail.key.name}", lockAtMostFor = "PT1M")
    public void execute(JobExecutionContext context) throws JobExecutionException {
        LockAssert.assertLocked();
        phase.lock();
        try { if (stopped) return; owner = Thread.currentThread(); }
        finally { phase.unlock(); }
        String code = "unknown";
        try {
            var detail = context.getJobDetail();
            code = detail.getJobDataMap().getString("jobCode");
            String mode = detail.getJobDataMap().getString("mode");
            registry.handler(code);
            if (!QuartzTriggerConfiguration.GROUP.equals(detail.getKey().getGroup())
                    || !("run".equals(mode) || "schedule".equals(mode))
                    || !detail.getKey().getName().equals(mode + "." + code)) {
                throw new IllegalArgumentException("持久化调度目标不匹配");
            }
            if (Thread.currentThread().isInterrupted()) return;
            if ("run".equals(mode)) {
                var due = jobs.due(code, 1);
                if (!due.isEmpty() && !Thread.currentThread().isInterrupted()) executor.execute(code, due.getFirst());
            } else {
                var definition = registry.periodicDefinition(code).orElseThrow();
                var time = context.getScheduledFireTime().toInstant();
                jobs.register(new Submission(UUID.randomUUID(), definition, time, code + ":" + time, "{}", CorrelationIdPolicy.resolve(null)));
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("固定调度驱动失败 errorType={}", failure.getClass().getSimpleName());
            throw new JobExecutionException("固定调度驱动失败");
        } finally {
            phase.lock();
            try {
                stopped = true; owner = null;
                // Quartz复用平台线程；封闭信号后清理中断，供代理解锁，不把标志带入下一次调度。
                Thread.interrupted();
            } finally { phase.unlock(); }
        }
    }

    /**
     * 只中断本原型当前借用的调度线程，短临界区避免迟到信号落到下次线程复用。
     * @apiNote 示例：关闭协调器调用scheduler.interrupt(fireInstanceId)。
     */
    @Override
    public void interrupt() {
        phase.lock();
        try { stopped = true; if (owner != null) owner.interrupt(); }
        finally { phase.unlock(); }
    }
}
