package com.patrick.stratum.job;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

import static org.junit.jupiter.api.Assertions.*;

/** 验证双调度器收尾在单组停止失败时仍会停止另一组与共享执行器。 */
class QuartzRuntimeLifecycleTest {
    /**
     * 普通调度器报错后仍停止Relay并关闭共享工作器。
     * @throws Exception 代理构造或调度器调用失败
     */
    @Test
    void continuesCleanupWhenOneSchedulerFails() throws Exception {
        var calls = new ArrayList<String>();
        var primary = scheduler("ordinary", true, calls);
        var relay = scheduler("relay", false, calls);
        var registry = new JobHandlerRegistry(List.of(new Probe()));
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null), registry, 1);
        var lifecycle = new QuartzRuntimeLifecycle(List.of(primary, relay), executor);
        lifecycle.start(); lifecycle.stop();
        assertEquals(List.of("ordinary", "relay"), calls);
        assertFalse(lifecycle.isRunning());
        assertEquals(JobExecutionResult.CLOSED, executor.execute("probe", UUID.randomUUID()));
    }

    /**
     * 构造只实现收尾所需方法的Scheduler代理。
     * @param name 固定测试名称
     * @param fail 是否在standby时报错
     * @param calls 记录各组停止顺序
     * @return 仅用于生命周期测试的Scheduler
     */
    private Scheduler scheduler(String name, boolean fail, List<String> calls) {
        return (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] { Scheduler.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "standby" -> { calls.add(name); if (fail) throw new SchedulerException("controlled"); yield null; }
                    case "getCurrentlyExecutingJobs" -> List.of();
                    default -> throw new UnsupportedOperationException("测试未使用的方法");
                });
    }

    /** 没有实际副作用的固定处理器。 */
    private static final class Probe implements JobHandler {
        /** {@inheritDoc} */
        @Override public String jobCode() { return "probe"; }
        /** {@inheritDoc} */
        @Override public void execute(Submission submission) { }
    }
}
