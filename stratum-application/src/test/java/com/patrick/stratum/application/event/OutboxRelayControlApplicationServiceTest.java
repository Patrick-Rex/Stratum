package com.patrick.stratum.application.event;

import java.util.function.Supplier;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxRelayControlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 以事务替身验证共享控制入口的提交及失败边界。 */
class OutboxRelayControlApplicationServiceTest {
    /** 无事务调用将变更和准入置于独立工作单元，读取不修改状态。 */
    @Test
    void isolatesEachControlOperation() {
        var work = new WorkFake(); var repository = new RepositoryFake();
        var service = new OutboxRelayControlApplicationService(work, repository);
        assertEquals(new OutboxRelayControlRepository.State(true, 0), service.current());
        assertEquals(new OutboxRelayControlRepository.State(false, 1), service.change(0, false));
        assertEquals(new OutboxRelayControlRepository.Admission(true, 1), service.tryAdmit());
        assertEquals(2, work.committed); assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** 有外层事务时三个入口均拒绝，不调用存储。 */
    @Test
    void refusesAmbientTransaction() {
        var work = new WorkFake(); var repository = new RepositoryFake();
        var service = new OutboxRelayControlApplicationService(work, repository);
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, service::current);
            assertThrows(IllegalStateException.class, () -> service.change(0, false));
            assertThrows(IllegalStateException.class, service::tryAdmit);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
        assertEquals(0, repository.calls); assertEquals(0, work.committed);
    }

    /** 提交失败或空结果不返回准入资格，错误按原样传播。 */
    @Test
    void propagatesCommitFailureAndRejectsNullInsideUnit() {
        var work = new WorkFake(); var repository = new RepositoryFake();
        var service = new OutboxRelayControlApplicationService(work, repository);
        var failure = new IllegalStateException("模拟提交失败"); work.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, service::tryAdmit));
        assertEquals(0, work.committed);
        work.failure = null; repository.returnNull = true;
        assertThrows(NullPointerException.class, service::tryAdmit);
        assertEquals(0, work.committed);
    }

    /** 只模拟事务调用与提交，不替代真实数据库并发验收。 */
    private static final class WorkFake implements UnitOfWork {
        private int committed;
        private RuntimeException failure;
        /** {@inheritDoc} */
        @Override public <T> T execute(Supplier<T> action) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                T result = action.get(); if (failure != null) throw failure;
                committed++; return result;
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }

    /** 顺序记录应用入口并检查状态修改和准入必须在工作单元内。 */
    private static final class RepositoryFake implements OutboxRelayControlRepository {
        private int calls;
        private boolean returnNull;
        /** {@inheritDoc} */
        @Override public State current() { calls++; return new State(true, 0); }
        /** {@inheritDoc} */
        @Override public State change(long expectedRevision, boolean paused) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); calls++;
            return new State(paused, expectedRevision + 1);
        }
        /** {@inheritDoc} */
        @Override public Admission tryAdmit() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); calls++;
            return returnNull ? null : new Admission(true, 1);
        }
    }
}
