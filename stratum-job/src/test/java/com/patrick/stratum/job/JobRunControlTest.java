package com.patrick.stratum.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实虚拟线程与中断验证局部截止协议，不将历史领取夹具视为数据库或ShedLock证据。 */
class JobRunControlTest {
    /** 截止信号实际中断运行线程，收尾阶段清理中断，代理清理后恢复责任仍被保留。 */
    @Test
    void deliversTimeoutAndDefersInterruptRestorationUntilCleanup() throws Exception {
        var control = control();
        var entered = new CountDownLatch(1);
        var finishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restored = new AtomicBoolean();
        try (var probe = probe(control, () -> {
            assertTrue(control.begin(lease())); entered.countDown();
            try {
                assertFalse(new CountDownLatch(1).await(3, TimeUnit.SECONDS), "本用例应由实际中断唤醒");
                fail("必须收到中断");
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
            assertEquals(Outcome.TIMED_OUT, control.finishing(Outcome.FAILED));
            assertFalse(Thread.currentThread().isInterrupted()); finishing.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            control.record(JobExecutionResult.TIMED_OUT);
            control.restoreThreadInterrupt(); restored.set(Thread.currentThread().isInterrupted());
        })) {
            probe.thread().start();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(control.requestTimeout());
                assertTrue(finishing.await(2, TimeUnit.SECONDS));
                assertFalse(control.requestTimeout());
                assertFalse(control.requestInterrupt());
            } finally { release.countDown(); }
            probe.result().get(3, TimeUnit.SECONDS);
            assertEquals(JobExecutionResult.TIMED_OUT, control.completed.get(1, TimeUnit.SECONDS));
            assertTrue(restored.get());
        }
    }

    /** 正常处理已进入收尾时，迟到超时和关闭都不能中断结果提交。 */
    @Test
    void rejectsLateSignalsAfterFinishingHasWon() throws Exception {
        var control = control();
        var finishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var probe = probe(control, () -> {
            control.begin(lease());
            assertEquals(Outcome.SUCCEEDED, control.finishing(Outcome.SUCCEEDED));
            finishing.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            assertFalse(Thread.currentThread().isInterrupted());
            control.record(JobExecutionResult.SUCCEEDED);
        })) {
            probe.thread().start();
            try {
                assertTrue(finishing.await(2, TimeUnit.SECONDS));
                assertFalse(control.requestTimeout());
                assertFalse(control.requestInterrupt());
            } finally { release.countDown(); }
            probe.result().get(3, TimeUnit.SECONDS);
            assertEquals(JobExecutionResult.SUCCEEDED, control.completed.get(1, TimeUnit.SECONDS));
        }
    }

    /** 启动前的停止请求不会丢失，即使随后取得领取也必须跳过处理并记录中断。 */
    @Test
    void preservesStopRequestedBeforeWorkerStarts() throws Exception {
        var control = control();
        try (var probe = probe(control, () -> {
            assertTrue(control.stopping());
            assertFalse(control.begin(lease()));
            assertEquals(Outcome.INTERRUPTED, control.finishing(Outcome.SUCCEEDED));
            assertFalse(Thread.currentThread().isInterrupted());
            control.record(JobExecutionResult.INTERRUPTED);
        })) {
            assertTrue(control.requestInterrupt());
            probe.thread().start();
            probe.result().get(3, TimeUnit.SECONDS);
            assertEquals(JobExecutionResult.INTERRUPTED, control.completed.get(1, TimeUnit.SECONDS));
        }
    }

    /** 启动交接保留原领取，只用单调时钟计算实际处理预算，不拿本机时间重判数据库租约。 */
    @Test
    void handsOffOriginalLeaseAndMonotonicDeadline() throws Exception {
        var control = control();
        var lease = lease();
        var release = new CountDownLatch(1);
        try (var probe = probe(control, () -> {
            control.begin(lease);
            assertTrue(release.await(3, TimeUnit.SECONDS));
            control.finishing(Outcome.SUCCEEDED);
            control.record(JobExecutionResult.SUCCEEDED);
        })) {
            probe.thread().start();
            try {
                var start = control.started.get(2, TimeUnit.SECONDS).orElseThrow();
                assertSame(lease, start.lease());
                long remaining = start.deadlineNanos() - System.nanoTime();
                assertTrue(remaining > 0 && remaining <= Duration.ofSeconds(30).toNanos());
            } finally { release.countDown(); }
            probe.result().get(3, TimeUnit.SECONDS);
        }
    }

    /** 未取得领取的路径也完成启动/结束信号，不能伪装为处理成功。 */
    @Test
    void terminatesSkippedWorkWithoutInventingALease() throws Exception {
        var control = control();
        try (var probe = probe(control, control::cleanupWithoutLease)) {
            probe.thread().start();
            probe.result().get(3, TimeUnit.SECONDS);
            assertTrue(control.started.get(1, TimeUnit.SECONDS).isEmpty());
            assertEquals(JobExecutionResult.SKIPPED, control.completed.get(1, TimeUnit.SECONDS));
            assertFalse(control.requestTimeout()); assertFalse(control.requestInterrupt());
        }
    }

    /** 启动阶段被中断且没有领取时，同样先清理信号再允许代理解锁。 */
    @Test
    void clearsPreClaimInterruptBeforeProxyCleanup() throws Exception {
        var control = control();
        try (var probe = probe(control, () -> {
            control.cleanupWithoutLease();
            assertFalse(Thread.currentThread().isInterrupted());
            assertFalse(control.requestInterrupt());
            assertFalse(control.requestTimeout());
            control.restoreThreadInterrupt();
            assertTrue(Thread.currentThread().isInterrupted());
        })) {
            assertTrue(control.requestInterrupt());
            probe.thread().start(); probe.result().get(3, TimeUnit.SECONDS);
            assertEquals(JobExecutionResult.SKIPPED, control.completed.get(1, TimeUnit.SECONDS));
        }
    }

    /** 阶段与结果只能由唯一虚拟工作线程推进，重复绑定/领取与外部完成拒绝。 */
    @Test
    void rejectsWrongOwnerAndRepeatedHandoff() throws Exception {
        var control = control();
        assertThrows(IllegalArgumentException.class, () -> control.bind(Thread.ofPlatform().unstarted(() -> { })));
        try (var probe = probe(control, () -> {
            control.begin(lease());
            assertThrows(IllegalStateException.class, () -> control.begin(lease()));
            control.finishing(Outcome.SUCCEEDED);
            control.record(JobExecutionResult.SUCCEEDED);
        })) {
            assertThrows(IllegalStateException.class, () -> control.bind(Thread.ofVirtual().unstarted(() -> { })));
            assertThrows(IllegalStateException.class, () -> control.begin(lease()));
            assertThrows(IllegalStateException.class, () -> control.finishing(Outcome.SUCCEEDED));
            assertThrows(IllegalStateException.class, () -> control.record(JobExecutionResult.SUCCEEDED));
            assertThrows(IllegalStateException.class, control::terminated);
            probe.thread().start(); probe.result().get(3, TimeUnit.SECONDS);
        }
    }

    /**
     * 创建本次局部控制器，不启动任何任务设施。
     * @return 随机执行控制器
     */
    private JobRunControl control() { return new JobRunControl("probe", UUID.randomUUID()); }

    /**
     * 构造历史时间的领取值，仅用于局部协议，不作为实际数据库持有证据。
     * @return 不可变领取夹具
     */
    private Lease lease() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        var definition = new JobDefinition("probe", JobDefinition.Type.ONCE, Optional.empty(), Optional.of(time), 30,
                new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        var submission = new Submission(UUID.randomUUID(), definition, time, "key", "{}", "trace");
        return new Lease(submission, UUID.randomUUID(), time, time.plusSeconds(60), 1, 0, 0);
    }

    /**
     * 为协调测试创建一个真正的虚拟线程并捕获工作线程断言失败。
     * @param control 本次控制器
     * @param action 要在工作线程运行的断言
     * @return 必须关闭的线程探针
     */
    private Probe probe(JobRunControl control, Action action) {
        var result = new CompletableFuture<Void>();
        var thread = Thread.ofVirtual().inheritInheritableThreadLocals(false).unstarted(() -> {
            try {
                try { action.run(); }
                finally { control.restoreThreadInterrupt(); control.terminated(); }
                result.complete(null);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        control.bind(thread);
        return new Probe(thread, result);
    }

    /** 可抛出受检异常的测试操作，只用于工作线程断言。 */
    @FunctionalInterface
    private interface Action {
        /**
         * 执行本线程协议断言。
         * @throws Exception 有界等待或断言前置操作失败
         */
        void run() throws Exception;
    }

    /**
     * 测试线程资源，失败路径同样必须退出。
     * @param thread 本用例唯一虚拟线程
     * @param result 包含工作线程断言结果的信号
     */
    private record Probe(Thread thread, CompletableFuture<Void> result) implements AutoCloseable {
        /**
         * 中断并有界等待自己创建的线程，保证异常用例没有残留。
         * @throws InterruptedException 关闭调用者被中断
         */
        @Override
        public void close() throws InterruptedException {
            if (thread.isAlive()) thread.interrupt();
            assertTrue(thread.join(Duration.ofSeconds(3)), "测试虚拟线程必须退出");
        }
    }
}
