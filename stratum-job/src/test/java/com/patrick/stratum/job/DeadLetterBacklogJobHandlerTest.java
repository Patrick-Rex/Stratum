package com.patrick.stratum.job;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.event.DeadLetterBacklogSamplingService;
import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 固定周期处理器验证代码、输入、事务及中断边界。 */
class DeadLetterBacklogJobHandlerTest {
    /** 合法输入顺序采集两组，仅尝试一次，不额外创建线程或事务。 */
    @Test void samplesFixedGroupsWithoutTransaction() throws Exception {
        var store = new Store(); var calls = new AtomicInteger();
        var handler = handler(store, calls);
        assertEquals("deadletter.backlog", handler.jobCode());
        var definition = handler.periodicDefinition().orElseThrow();
        assertEquals(JobDefinition.Type.CRON, definition.jobType());
        assertEquals(JobDefinition.IdempotencyRule.SCHEDULE_TIME, definition.idempotencyKeyRule());
        assertEquals(1, definition.retryPolicy().maxAttempts());
        handler.execute(submission(definition, "{}")); assertEquals(2, calls.get()); assertEquals(2, store.writes.get());
    }

    /** 不接受其他代码或参数，拒绝时不触发采集。 */
    @Test void rejectsWrongCodeAndArguments() {
        var store = new Store(); var calls = new AtomicInteger(); var handler = handler(store, calls);
        var definition = handler.periodicDefinition().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> handler.execute(submission(definition, "{\"x\":1}")));
        var other = new JobDefinition("other", definition.jobType(), definition.cron(), definition.triggerAt(),
                definition.timeoutSec(), definition.retryPolicy(), definition.idempotencyKeyRule());
        assertThrows(IllegalArgumentException.class, () -> handler.execute(submission(other, "{}")));
        assertEquals(0, calls.get()); assertEquals(0, store.begins.get());
    }

    /** 代号被替代不重启采集；事务内调用在任何存储操作前拒绝。 */
    @Test void stopsSupersededSampleAndRejectsTransaction() throws Exception {
        var store = new Store(); store.accept = false; var calls = new AtomicInteger(); var handler = handler(store, calls);
        var submission = submission(handler.periodicDefinition().orElseThrow(), "{}");
        handler.execute(submission); assertEquals(1, calls.get()); assertEquals(1, store.begins.get());
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> handler.execute(submission)); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
        assertEquals(1, store.begins.get());
    }

    /** 预先中断不触发采集，保持线程标记。 */
    @Test void preservesInterruption() {
        var store = new Store(); var handler = handler(store, new AtomicInteger());
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> handler.execute(submission(handler.periodicDefinition().orElseThrow(), "{}")));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, store.begins.get()); }
        finally { Thread.interrupted(); }
    }

    /** 非法Cron和超出任务预算的配置在创建时拒绝。 */
    @Test void validatesScheduleAndBudget() {
        var service = service(new Store(), new AtomicInteger());
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterBacklogJobHandler(service, "invalid", 60));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterBacklogJobHandler(service, "0 0/5 * * * ?", 301));
        assertThrows(NullPointerException.class, () -> new DeadLetterBacklogJobHandler(null, "0 0/5 * * * ?", 60));
    }

    /**
     * 创建不访问外部资源的测试处理器。
     * @param store 受控快照替身
     * @param calls Broker查询次数
     * @return 测试处理器
     */
    private DeadLetterBacklogJobHandler handler(Store store, AtomicInteger calls) {
        return new DeadLetterBacklogJobHandler(service(store, calls), "0 0/5 * * * ?", 60);
    }

    /**
     * 连接无外部副作用的采集入口。
     * @param store 受控快照替身
     * @param calls Broker查询次数
     * @return 实际采集用例
     */
    private DeadLetterBacklogSamplingService service(Store store, AtomicInteger calls) {
        return new DeadLetterBacklogSamplingService(group -> { calls.incrementAndGet();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return OptionalLong.of(2); }, store, Duration.ofMinutes(2));
    }

    /**
     * 生成按计划时间幂等的任务输入。
     * @param definition 固定任务定义
     * @param arguments JSON参数
     * @return 周期任务输入
     */
    private Submission submission(JobDefinition definition, String arguments) {
        Instant time = Instant.parse("2026-10-28T00:00:00Z");
        return new Submission(UUID.randomUUID(), definition, time, definition.jobCode() + ":" + time, arguments, "trace-test");
    }

    /** 测试采集执行次数及代号替代，不替代真实Redis验收。 */
    private static final class Store implements DeadLetterBacklogStore {
        private final AtomicInteger begins = new AtomicInteger(); private final AtomicInteger writes = new AtomicInteger();
        private boolean accept = true;
        /** {@inheritDoc} */
        @Override public UUID begin() { begins.incrementAndGet(); return UUID.randomUUID(); }
        /** {@inheritDoc} */
        @Override public boolean record(UUID sample, Group group, OptionalLong ready, Duration ttl) { writes.incrementAndGet(); return accept; }
        /** {@inheritDoc} */
        @Override public OptionalLong read(Group group) { return OptionalLong.empty(); }
    }
}
