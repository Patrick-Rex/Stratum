package com.patrick.stratum.application.event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.patrick.stratum.domain.event.OutboxPublisher.PublishResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** 以明确的事务标志替身检验编排边界；真实数据库/Broker组合另行执行。 */
class OutboxRelayApplicationServiceTest {
    private final OutboxRelayRepository.RetryPolicy policy =
            new OutboxRelayRepository.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4));

    /** 只领取即将发送的单条，事务外发布后才记录结果，批次上限不多领取。 */
    @Test
    void publishesOutsideUnitsAndHonorsAttemptLimit() {
        var repository = new RepositoryFake(3);
        var originals = repository.queue.stream().map(c -> c.delivery().message().eventId()).toList();
        var sent = new ArrayList<String>();
        var service = service(repository, delivery -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            sent.add(delivery.message().eventId());
            return CONFIRMED;
        });
        assertEquals(2, service.runBatch(request(2), policy));
        assertEquals(originals.subList(0, 2), sent);
        assertEquals(List.of(CONFIRMED, CONFIRMED), repository.outcomes);
        assertEquals(1, repository.queue.size());
    }

    /** 本地非法信封、Broker 否定确认和暂时不可用分别分类；未更新的租约不计入已记录条数。 */
    @Test
    void classifiesFailuresAndCountsOnlyPersistedOutcomes() {
        var repository = new RepositoryFake(4);
        repository.finishReplies.addAll(List.of(true, false, true, true));
        var attempts = new AtomicInteger();
        var service = service(repository, delivery -> switch (attempts.getAndIncrement()) {
            case 0 -> throw new IllegalArgumentException("private-payload");
            case 1 -> throw new IllegalStateException("private-password");
            case 2 -> REJECTED;
            default -> TIMED_OUT;
        });
        assertEquals(3, service.runBatch(request(4), policy));
        assertEquals(List.of(INVALID_ENVELOPE, UNAVAILABLE, REJECTED, TIMED_OUT), repository.outcomes);
    }

    /** 未启用消息或外部仍在事务中时，不能开始领取。 */
    @Test
    void rejectsDisabledOrTransactionalEntryBeforeClaim() {
        var repository = new RepositoryFake(1);
        var disabled = new OutboxRelayApplicationService(new UnitFake(), repository, provider(null));
        assertThrows(IllegalStateException.class, () -> disabled.runBatch(request(1), policy));
        var service = service(repository, delivery -> CONFIRMED);
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> service.runBatch(request(1), policy)); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
        assertEquals(1, repository.queue.size());
        assertTrue(repository.outcomes.isEmpty());
    }

    /**
     * 同实例仅一批执行，中断不会再领取，容量在结束后恢复。
     * @throws Exception 并发检查超时或失败
     */
    @Test
    void limitsLocalBatchesAndPreservesInterruption() throws Exception {
        var repository = new RepositoryFake(2);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = service(repository, delivery -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            started.countDown();
            await(release);
            return CONFIRMED;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.runBatch(request(1), policy));
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> service.runBatch(request(1), policy));
            } finally { release.countDown(); }
            assertEquals(1, first.get(2, TimeUnit.SECONDS));
        }
        Thread.currentThread().interrupt();
        try {
            assertEquals(0, service.runBatch(request(1), policy));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, repository.queue.size());
        } finally { Thread.interrupted(); }
        assertEquals(1, service.runBatch(request(1), policy));
    }

    /** 状态持久化失败必须传播，后续没有新领取时不能再次发送旧事件，实例容量仍释放。 */
    @Test
    void propagatesStorageFailureAndReleasesLocalCapacity() {
        var repository = new RepositoryFake(1);
        repository.finishFailure = new IllegalStateException("database-unavailable");
        var publications = new AtomicInteger();
        var service = service(repository, delivery -> { publications.incrementAndGet(); return CONFIRMED; });
        assertThrows(IllegalStateException.class, () -> service.runBatch(request(1), policy));
        repository.finishFailure = null;
        assertEquals(0, service.runBatch(request(1), policy));
        assertEquals(1, publications.get());
    }

    /** 发布端口报告中断后不再写新事务或领取下一条，结束后实例容量可恢复。 */
    @Test
    void stopsBeforeFinalizationWhenPublisherInterrupts() {
        var repository = new RepositoryFake(2);
        var interruptOnce = new AtomicBoolean(true);
        var service = service(repository, delivery -> {
            if (interruptOnce.getAndSet(false)) Thread.currentThread().interrupt();
            return UNAVAILABLE;
        });
        try {
            assertEquals(0, service.runBatch(request(2), policy));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(repository.outcomes.isEmpty());
            assertEquals(1, repository.queue.size());
        } finally { Thread.interrupted(); }
        assertEquals(1, service.runBatch(request(1), policy));
    }

    /**
     * 创建使用实际编排类的受控用例。
     * @param repository 存储替身
     * @param publisher 发布替身
     * @return 应用服务
     */
    private static OutboxRelayApplicationService service(RepositoryFake repository, OutboxPublisher publisher) {
        return new OutboxRelayApplicationService(new UnitFake(), repository, provider(publisher));
    }

    /**
     * 复用Spring可选依赖提供器，不引入Mock框架或Boot依赖。
     * @param publisher 可空发布端口
     * @return 可选提供器
     */
    private static ObjectProvider<OutboxPublisher> provider(OutboxPublisher publisher) {
        var beans = new StaticListableBeanFactory();
        if (publisher != null) beans.addBean("publisher", publisher);
        return beans.getBeanProvider(OutboxPublisher.class);
    }

    /**
     * 使用稳定租期构造本次最大处理量。
     * @param size 上限
     * @return 有界请求
     */
    private static OutboxRelayRepository.ClaimRequest request(int size) {
        return new OutboxRelayRepository.ClaimRequest(size, Duration.ofSeconds(30));
    }

    /**
     * 有界协调发送替身。
     * @param latch 闩锁
     * @throws IllegalStateException 等待超时或被中断
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("测试发布等待超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试发布等待中断", failure);
        }
    }

    /** 仅模拟事务活动标志供编排单元验证，不模拟数据库提交成功。 */
    private static final class UnitFake implements UnitOfWork {
        /** {@inheritDoc} */
        @Override
        public <T> T execute(Supplier<T> action) {
            boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { return action.get(); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
        }
    }

    /** 预置有限领取证据，验证领取/完成在工作单元内且不会把MQ放入其中。 */
    private static final class RepositoryFake implements OutboxRelayRepository {
        private final ArrayDeque<Claim> queue = new ArrayDeque<>();
        private final ArrayDeque<Boolean> finishReplies = new ArrayDeque<>();
        private final List<OutboxPublisher.PublishResult> outcomes = new ArrayList<>();
        private RuntimeException finishFailure;

        /**
         * 构造有限数据，不生成后台任务或外部状态。
         * @param size 证据数
         */
        private RepositoryFake(int size) {
            for (int i = 0; i < size; i++) {
                var delivery = new OutboxPublisher.Delivery(new OutboxMessage(UUID.randomUUID().toString(),
                        "erp.probe.created", UUID.randomUUID().toString(), Instant.EPOCH, "{}", "app-relay-trace"), 1);
                queue.add(new Claim(delivery, UUID.randomUUID(), Instant.EPOCH, 0));
            }
        }

        /** {@inheritDoc} */
        @Override
        public List<Claim> claim(ClaimRequest request) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, request.batchSize());
            var next = queue.poll();
            return next == null ? List.of() : List.of(next);
        }

        /** {@inheritDoc} */
        @Override
        public boolean finish(Claim claim, OutboxPublisher.PublishResult result, RetryPolicy retryPolicy) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (finishFailure != null) throw finishFailure;
            outcomes.add(result);
            return finishReplies.isEmpty() || finishReplies.removeFirst();
        }
    }
}
