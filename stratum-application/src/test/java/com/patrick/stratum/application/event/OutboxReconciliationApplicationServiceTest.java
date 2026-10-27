package com.patrick.stratum.application.event;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxReconciliationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.patrick.stratum.application.event.OutboxReconciliationApplicationService.Termination.INTERRUPTED;
import static com.patrick.stratum.application.event.OutboxReconciliationApplicationService.Termination.LIMIT;
import static com.patrick.stratum.domain.event.OutboxReconciliationRepository.ScanOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证对账批次编排；事务替身只检验调用顺序，真实数据库提交由集成测试验证。 */
class OutboxReconciliationApplicationServiceTest {

    /** 上限必须在 1 至 128 内，端点允许且非法请求不启动工作单元。 */
    @Test
    void validatesInclusiveScanLimit() {
        var work = new WorkFake();
        var repository = new RepositoryFake();
        var service = new OutboxReconciliationApplicationService(work, repository);
        assertThrows(IllegalArgumentException.class, () -> service.runBatch(0));
        assertThrows(IllegalArgumentException.class, () -> service.runBatch(129));
        assertEquals(0, work.attempted);
        assertEquals(new OutboxReconciliationApplicationService.Result(1, 0, LIMIT), service.runBatch(1));
        assertEquals(new OutboxReconciliationApplicationService.Result(128, 0, LIMIT), service.runBatch(128));
        assertEquals(129, work.committed);
    }

    /** 不允许在已有写事务或只读事务中执行批次。 */
    @Test
    void rejectsExistingTransactionsBeforeScanning() {
        var work = new WorkFake();
        var repository = new RepositoryFake();
        var service = new OutboxReconciliationApplicationService(work, repository);
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertThrows(IllegalStateException.class, () -> service.runBatch(1));
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThrows(IllegalStateException.class, () -> service.runBatch(1));
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
            TransactionSynchronizationManager.setActualTransactionActive(active);
        }
        assertEquals(0, work.attempted);
        assertEquals(0, repository.attempted);
    }

    /** 未修复的扫描也计入额度，每项各自经过工作单元，达到额度后不再扫描。 */
    @Test
    void countsAllScannedReceiptsAndUsesSeparateUnits() {
        var work = new WorkFake();
        var repository = new RepositoryFake(RECONCILED, SCANNED, RECONCILED);
        var service = new OutboxReconciliationApplicationService(work, repository);
        assertEquals(new OutboxReconciliationApplicationService.Result(2, 1, LIMIT), service.runBatch(2));
        assertEquals(2, work.attempted);
        assertEquals(2, work.committed);
        assertEquals(2, repository.attempted);
        assertEquals(List.of(RECONCILED), List.copyOf(repository.outcomes));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** 轮次结束和游标忙立即停止，均不计作扫描，也不再次进入新轮次。 */
    @Test
    void stopsOnCycleCompletionOrBusyCursor() {
        for (var stop : List.of(CYCLE_COMPLETE, BUSY)) {
            var work = new WorkFake();
            var repository = new RepositoryFake(SCANNED, stop, RECONCILED);
            var service = new OutboxReconciliationApplicationService(work, repository);
            var expected = stop == CYCLE_COMPLETE
                    ? OutboxReconciliationApplicationService.Termination.CYCLE_COMPLETE
                    : OutboxReconciliationApplicationService.Termination.BUSY;
            assertEquals(new OutboxReconciliationApplicationService.Result(1, 0, expected), service.runBatch(3));
            assertEquals(2, work.committed);
            assertEquals(2, repository.attempted);
            assertEquals(List.of(RECONCILED), List.copyOf(repository.outcomes));
        }
    }

    /** 后续工作单元提交失败时传播原异常，先前已提交项不被撤销。 */
    @Test
    void propagatesCommitFailureAfterEarlierCommittedItem() {
        var work = new WorkFake();
        var failure = new IllegalStateException("模拟提交失败");
        work.failOnCommit = 2;
        work.failure = failure;
        var repository = new RepositoryFake(RECONCILED, SCANNED, RECONCILED);
        var service = new OutboxReconciliationApplicationService(work, repository);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.runBatch(3)));
        assertEquals(2, work.attempted);
        assertEquals(1, work.committed);
        assertEquals(2, repository.attempted);
        assertEquals(List.of(RECONCILED), List.copyOf(repository.outcomes));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** 仓储异常中止当前工作单元并原样传播，先前的单项提交仍保留。 */
    @Test
    void propagatesRepositoryFailureAfterEarlierCommittedItem() {
        var work = new WorkFake();
        var repository = new RepositoryFake(RECONCILED, SCANNED);
        var failure = new IllegalStateException("模拟存储失败");
        repository.failAt = 2;
        repository.failure = failure;
        var service = new OutboxReconciliationApplicationService(work, repository);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.runBatch(3)));
        assertEquals(2, work.attempted);
        assertEquals(1, work.committed);
        assertEquals(2, repository.attempted);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** 空仓储结果在工作单元内拒绝，不能将错误结果提交。 */
    @Test
    void rejectsNullResultBeforeCommit() {
        var work = new WorkFake();
        var service = new OutboxReconciliationApplicationService(work, () -> null);
        assertThrows(NullPointerException.class, () -> service.runBatch(1));
        assertEquals(1, work.attempted);
        assertEquals(0, work.committed);
    }

    /** 中断后保留标志和已提交计数，不再开始新的工作单元。 */
    @Test
    void preservesInterruptionAndStopsAfterCommittedItem() {
        var work = new WorkFake();
        work.interruptAfterCommit = 1;
        var repository = new RepositoryFake(RECONCILED, SCANNED);
        var service = new OutboxReconciliationApplicationService(work, repository);
        boolean previous = Thread.interrupted();
        try {
            assertEquals(new OutboxReconciliationApplicationService.Result(1, 1, INTERRUPTED), service.runBatch(2));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, work.committed);
            assertEquals(1, repository.attempted);
        } finally {
            Thread.interrupted();
            if (previous) Thread.currentThread().interrupt();
        }
    }

    /** 仅记录工作单元边界和模拟提交，不模拟真实数据库事务。 */
    private static final class WorkFake implements UnitOfWork {
        private int attempted;
        private int committed;
        private int failOnCommit;
        private int interruptAfterCommit;
        private RuntimeException failure;

        /** {@inheritDoc} */
        @Override
        public <T> T execute(Supplier<T> action) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            attempted++;
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                T result = action.get();
                if (attempted == failOnCommit) throw failure;
                committed++;
                if (committed == interruptAfterCommit) Thread.currentThread().interrupt();
                return result;
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }

    /** 顺序返回单项扫描结果并确认仓储只在工作单元内调用。 */
    private static final class RepositoryFake implements OutboxReconciliationRepository {
        private final ArrayDeque<ScanOutcome> outcomes = new ArrayDeque<>();
        private int attempted;
        private int failAt;
        private RuntimeException failure;

        /**
         * 创建预置扫描结果；耗尽后返回已扫描结果。
         * @param values 依次返回的单项结果
         */
        private RepositoryFake(ScanOutcome... values) {
            outcomes.addAll(List.of(values));
        }

        /** {@inheritDoc} */
        @Override
        public ScanOutcome scanOne() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            attempted++;
            if (attempted == failAt) throw failure;
            return outcomes.isEmpty() ? SCANNED : outcomes.removeFirst();
        }
    }
}
