package com.patrick.stratum.application.event;

import java.time.Duration;
import java.util.EnumMap;
import java.util.OptionalLong;
import java.util.UUID;

import com.patrick.stratum.domain.event.DeadLetterBacklogStore;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 采集编排不启动线程或事务，并保持各组结果独立。 */
class DeadLetterBacklogSamplingServiceTest {
    /** 单组异常登记不可用，另一组零值正常保存。 */
    @Test void recordsGroupsIndependently() throws Exception {
        var store = new Store();
        var service = new DeadLetterBacklogSamplingService(group -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (group == Group.ERP_AUDIT) throw new IllegalStateException("broker不可用");
            return OptionalLong.of(0);
        }, store, Duration.ofSeconds(5));
        assertTrue(service.sample());
        assertTrue(store.values.get(Group.ERP_AUDIT).isEmpty());
        assertEquals(0, store.values.get(Group.CACHE_INVALIDATION).orElseThrow());
    }

    /** 事务内拒绝启动；采样被新代号替代时停止，不覆盖后续组。 */
    @Test void rejectsTransactionsAndStopsWhenSuperseded() throws Exception {
        var store = new Store(); var service = new DeadLetterBacklogSamplingService(group -> OptionalLong.of(1), store, Duration.ofSeconds(5));
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, service::sample); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
        assertNull(store.current); store.accept = false;
        assertFalse(service.sample()); assertTrue(store.values.isEmpty());
    }

    /** 中断在任何外部操作前传播并保持线程标志。 */
    @Test void preservesInterruptionBeforeSampling() {
        var store = new Store(); var service = new DeadLetterBacklogSamplingService(group -> OptionalLong.of(1), store, Duration.ofSeconds(5));
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, service::sample); assertTrue(Thread.currentThread().isInterrupted()); assertNull(store.current); }
        finally { Thread.interrupted(); }
    }

    /** 受控内存替身，仅用于核对编排调用和传递参数。 */
    private static final class Store implements DeadLetterBacklogStore {
        private UUID current; private boolean accept = true; private final EnumMap<Group, OptionalLong> values = new EnumMap<>(Group.class);
        /** {@inheritDoc} */
        @Override public UUID begin() { current = UUID.randomUUID(); return current; }
        /** {@inheritDoc} */
        @Override public boolean record(UUID sample, Group group, OptionalLong ready, Duration ttl) {
            assertEquals(current, sample); assertEquals(Duration.ofSeconds(5), ttl);
            if (!accept) return false; values.put(group, ready); return true;
        }
        /** {@inheritDoc} */
        @Override public OptionalLong read(Group group) { return values.getOrDefault(group, OptionalLong.empty()); }
    }
}
