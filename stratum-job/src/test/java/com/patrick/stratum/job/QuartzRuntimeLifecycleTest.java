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
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null, null, null), registry, 1);
        var lifecycle = new QuartzRuntimeLifecycle(List.of(primary, relay), executor, List.of());
        lifecycle.start(); lifecycle.stop();
        assertEquals(List.of("ordinary", "relay"), calls);
        assertFalse(lifecycle.isRunning());
        assertEquals(JobExecutionResult.CLOSED, executor.execute("probe", UUID.randomUUID()));
    }

    /** 只在存在已核验Relay目标且专用调度器齐备时启动该调度器。 */
    @Test
    void startsOnlyConfiguredRelayScheduler() {
        var calls = new ArrayList<String>();
        var primary = scheduler("ordinary", false, calls); var relay = scheduler("relay", false, calls);
        var registry = new JobHandlerRegistry(List.of(new Probe()));
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null, null, null), registry, 1);
        assertThrows(IllegalArgumentException.class, () -> new QuartzRuntimeLifecycle(List.of(primary), executor, List.of(relay)));
        var lifecycle = new QuartzRuntimeLifecycle(List.of(primary, relay), executor, List.of(relay));
        lifecycle.start(); assertEquals(List.of("start-relay"), calls);
        lifecycle.stop(); assertEquals(List.of("start-relay", "ordinary", "relay"), calls);
        assertThrows(IllegalStateException.class, lifecycle::start);
        assertThrows(IllegalArgumentException.class, () -> new QuartzRuntimeLifecycle(List.of(primary), executor, true));
    }

    /** 第二专用组启动失败时回滚全部组，关闭共享执行器且不保留运行状态。 */
    @Test
    void rollsBackAllSchedulersWhenSecondStartFails() {
        var calls = new ArrayList<String>();
        var primary = scheduler("ordinary", false, calls); var relay = scheduler("relay", false, calls);
        var backlog = scheduler("backlog", false, true, calls);
        var registry = new JobHandlerRegistry(List.of(new Probe()));
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null, null, null), registry, 1);
        var lifecycle = new QuartzRuntimeLifecycle(List.of(primary, relay, backlog), executor, List.of(relay, backlog));
        assertThrows(IllegalStateException.class, lifecycle::start);
        assertEquals(List.of("start-relay", "start-backlog", "ordinary", "relay", "backlog"), calls);
        assertFalse(lifecycle.isRunning()); assertEquals(JobExecutionResult.CLOSED, executor.execute("probe", UUID.randomUUID()));
    }

    /** 运行时停止异常不妨碍其余组和共享执行器清理。 */
    @Test
    void continuesCleanupAfterRuntimeException() {
        var calls = new ArrayList<String>(); var primary = scheduler("ordinary", false, calls);
        var broken = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] { Scheduler.class }, (proxy, method, args) -> switch (method.getName()) {
            case "standby" -> { calls.add("broken"); throw new IllegalStateException("controlled"); }
            case "getCurrentlyExecutingJobs" -> List.of();
            default -> throw new UnsupportedOperationException("测试未使用的方法");
        });
        var relay = scheduler("relay", false, calls); var registry = new JobHandlerRegistry(List.of(new Probe()));
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null, null, null), registry, 1);
        var lifecycle = new QuartzRuntimeLifecycle(List.of(primary, broken, relay), executor, List.of());
        lifecycle.start(); lifecycle.stop(); assertEquals(List.of("ordinary", "broken", "relay"), calls);
        assertEquals(JobExecutionResult.CLOSED, executor.execute("probe", UUID.randomUUID()));
    }

    /** 一组状态暂时不可读时继续检查其他组，并在同一预算内重试确认。 */
    @Test
    void keepsCheckingAllGroupsWhenOneStatusIsUnavailable() {
        var faultReads = new java.util.concurrent.atomic.AtomicInteger(); var otherReads = new java.util.concurrent.atomic.AtomicInteger();
        var fault = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] { Scheduler.class }, (proxy, method, args) -> switch (method.getName()) {
            case "standby" -> null;
            case "getCurrentlyExecutingJobs" -> { if (faultReads.incrementAndGet() == 2) throw new SchedulerException("controlled"); yield List.of(); }
            default -> throw new UnsupportedOperationException("测试未使用的方法");
        });
        var other = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] { Scheduler.class }, (proxy, method, args) -> switch (method.getName()) {
            case "standby" -> null;
            case "getCurrentlyExecutingJobs" -> { otherReads.incrementAndGet(); yield List.of(); }
            default -> throw new UnsupportedOperationException("测试未使用的方法");
        });
        var registry = new JobHandlerRegistry(List.of(new Probe()));
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null, null, null), registry, 1);
        var lifecycle = new QuartzRuntimeLifecycle(List.of(fault, other), executor, List.of());
        lifecycle.start(); lifecycle.stop();
        assertTrue(faultReads.get() >= 3); assertTrue(otherReads.get() >= 3);
        assertEquals(JobExecutionResult.CLOSED, executor.execute("probe", UUID.randomUUID()));
    }

    /**
     * 停止与专用组启动交错时，停止等待启动收尾后执行，不能在停止后继续启动。
     * @throws Exception 线程等待失败
     */
    @Test
    void serializesConcurrentStartAndStop() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var calls = java.util.Collections.synchronizedList(new ArrayList<String>());
        var primary = scheduler("ordinary", false, calls);
        var blocking = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] { Scheduler.class }, (proxy, method, args) -> switch (method.getName()) {
            case "start" -> { calls.add("start-blocking"); entered.countDown(); if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new SchedulerException("controlled timeout"); yield null; }
            case "standby" -> { calls.add("blocking"); yield null; }
            case "getCurrentlyExecutingJobs" -> List.of();
            default -> throw new UnsupportedOperationException("测试未使用的方法");
        });
        var later = scheduler("later", false, calls); var registry = new JobHandlerRegistry(List.of(new Probe()));
        var executor = new JobTaskExecutor(new ProtectedJobWorker(null, registry, null, null, null), registry, 1);
        var lifecycle = new QuartzRuntimeLifecycle(List.of(primary, blocking, later), executor, List.of(blocking, later));
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = threads.submit(lifecycle::start); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var stop = threads.submit(() -> lifecycle.stop()); release.countDown();
            start.get(5, java.util.concurrent.TimeUnit.SECONDS); stop.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("start-blocking", "start-later", "ordinary", "blocking", "later"), calls);
            assertFalse(lifecycle.isRunning());
        } finally { release.countDown(); lifecycle.stop(); }
    }

    /**
     * 构造只实现收尾所需方法的Scheduler代理。
     * @param name 固定测试名称
     * @param fail 是否在standby时报错
     * @param calls 记录各组停止顺序
     * @return 仅用于生命周期测试的Scheduler
     */
    private Scheduler scheduler(String name, boolean fail, List<String> calls) {
        return scheduler(name, fail, false, calls);
    }

    /**
     * 构造可分别控制启动与停止失败的调度器代理。
     * @param name 测试名称
     * @param fail 停止是否失败
     * @param failStart 启动是否失败
     * @param calls 启停调用记录
     * @return 生命周期测试代理
     */
    private Scheduler scheduler(String name, boolean fail, boolean failStart, List<String> calls) {
        return (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(), new Class<?>[] { Scheduler.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "start" -> { calls.add("start-" + name); if (failStart) throw new SchedulerException("controlled"); yield null; }
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
