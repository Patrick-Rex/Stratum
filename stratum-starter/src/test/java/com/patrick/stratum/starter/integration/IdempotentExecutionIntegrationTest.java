package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.service.IdempotentApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.domain.idempotency.IdempotencyReceipt;
import com.patrick.stratum.domain.idempotency.IdempotencyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证业务、Outbox和幂等回执的原子执行及并发重放。 */
class IdempotentExecutionIntegrationTest extends MariaDbIntegrationTest {
    private final String scope = "test:" + UUID.randomUUID();
    private final IdempotentApplicationService service;
    private final IdempotencyRepository receipts;
    private final UnitOfWork unitOfWork;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;

    /**
     * 注入实际Spring事务代理与持久化组件。
     * @param service 幂等执行入口
     * @param receipts 回执查询
     * @param unitOfWork 写事务入口
     * @param outbox 同事务事件端口
     * @param jdbc 测试写入与结果断言
     */
    @Autowired
    IdempotentExecutionIntegrationTest(IdempotentApplicationService service, IdempotencyRepository receipts,
            UnitOfWork unitOfWork, OutboxRepository outbox, JdbcTemplate jdbc) {
        this.service = service;
        this.receipts = receipts;
        this.unitOfWork = unitOfWork;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /** 重复请求读取回执，不再次执行业务或写入Outbox。 */
    @Test
    void replaysCommittedResultWithoutRepeatingWrites() {
        var key = new IdempotencyReceipt.Key(scope, "repeat");
        String first = unitOfWork.execute(() -> service.execute(key, Map.of("name", "物料"), String.class, this::write));
        String repeated = unitOfWork.execute(() -> service.execute(key, Map.of("name", "物料"), String.class,
                () -> fail("已提交请求不能再次执行")));
        assertEquals(first, repeated);
        assertEquals(1, businessCount());
        assertEquals(1, eventCount());
    }

    /** 对象结果可以按固定DTO类型重放，保留小数数量及业务标识。 */
    @Test
    void replaysTypedResultsWithDecimalValues() {
        var key = new IdempotencyReceipt.Key(scope, "typed");
        var first = unitOfWork.execute(() -> service.execute(key, "request", ProbeResult.class,
                () -> new ProbeResult(write(), new BigDecimal("1.250"))));
        var replayed = unitOfWork.execute(() -> service.execute(key, "request", ProbeResult.class,
                () -> fail("对象结果应从回执恢复")));
        assertEquals(first, replayed);
        assertEquals(1, businessCount());
    }

    /** 同键不同业务请求返回冲突，不能静默返回其他请求的结果。 */
    @Test
    void rejectsDifferentPayloadForTheSameKey() {
        var key = new IdempotencyReceipt.Key(scope, "conflict");
        unitOfWork.execute(() -> service.execute(key, Map.of("quantity", 1), String.class, this::write));
        var conflict = assertThrows(StratumException.class, () -> unitOfWork.execute(() ->
                service.execute(key, Map.of("quantity", 2), String.class, () -> fail("冲突请求不能执行"))));
        assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
        assertEquals(1, businessCount());
    }

    /** 业务失败时两类记录及回执都回滚，原键可以再次执行。 */
    @Test
    void failureRollsBackBusinessEventAndReceipt() {
        var key = new IdempotencyReceipt.Key(scope, "failure");
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> service.execute(key, "request", String.class, () -> {
            write(); throw new IllegalStateException("业务失败");
        })));
        assertEquals(0, businessCount());
        assertEquals(0, eventCount());
        assertTrue(receipts.find(key).isEmpty());
        unitOfWork.execute(() -> service.execute(key, "request", String.class, this::write));
        assertEquals(1, businessCount());
        assertEquals(1, eventCount());
    }

    /** 两个并发请求均进入回调时，唯一约束仍保证只提交一份业务及事件。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentRequestsCommitOnceAndRetryReplays() throws Exception {
        var key = new IdempotencyReceipt.Key(scope, "concurrent");
        var entered = new CountDownLatch(2);
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> competingWrite(key, entered, calls));
            var second = executor.submit(() -> competingWrite(key, entered, calls));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2, calls.get(), "必须确实覆盖并发回调，不能仅测试顺序重放");
        assertEquals(1, businessCount());
        assertEquals(1, eventCount());
        String persistedId = jdbc.queryForObject("SELECT id FROM stratum_it_transaction_probe WHERE payload = ?", String.class, scope);
        String replayed = unitOfWork.execute(() -> service.execute(key, "request", String.class,
                () -> fail("竞争结束后应重放已提交结果")));
        assertEquals(persistedId, replayed);
    }

    /** 回执过大时回滚已经发生的业务和事件写入，不能返回未受幂等保护的成功。 */
    @Test
    void oversizedResultRollsBackTheBusiness() {
        var key = new IdempotencyReceipt.Key(scope, "large-result");
        assertThrows(IllegalArgumentException.class, () -> unitOfWork.execute(() -> service.execute(key, "request", String.class, () -> {
            write(); return "中".repeat(90_000);
        })));
        assertEquals(0, businessCount());
        assertEquals(0, eventCount());
        assertTrue(receipts.find(key).isEmpty());
    }

    /** 空结果同样可以重放；事务外调用与超大请求均在业务执行前失败。 */
    @Test
    void handlesNullResultsAndRejectsInvalidBoundaries() {
        var key = new IdempotencyReceipt.Key(scope, "null");
        assertNull(unitOfWork.execute(() -> service.execute(key, "request", String.class, () -> null)));
        assertNull(unitOfWork.execute(() -> service.execute(key, "request", String.class, () -> fail("空结果也应重放"))));
        assertThrows(IllegalTransactionStateException.class, () -> service.execute(key, "request", String.class, this::write));
        assertThrows(IllegalArgumentException.class, () -> unitOfWork.execute(() ->
                service.execute(key, "中".repeat(90_000), String.class, () -> fail("超大请求不能执行"))));
    }

    /** 清理当前测试作用域拥有的数据，不清空共享表。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", scope);
            jdbc.update("DELETE FROM stratum_it_transaction_probe WHERE payload = ?", scope);
            jdbc.update("DELETE FROM idempotency_receipt WHERE scope = ?", scope);
            return null;
        });
    }

    /**
     * 模拟同事务业务与事件写入，结果标识来自UUID。
     * @return 新记录标识
     */
    private String write() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO stratum_it_transaction_probe(id, payload) VALUES (?, ?)", id, scope);
        outbox.append(new OutboxMessage(UUID.randomUUID().toString(), "test.idempotency.created", scope,
                Instant.now(), "{}", "idempotency-test"));
        return id;
    }

    /**
     * 通过屏障确保两个事务均看不到已有回执，再竞争数据库唯一键。
     * @param key 同一幂等键
     * @param entered 并发进入信号
     * @param calls 回调计数
     * @return 成功提交为1，预期冲突为0
     */
    private int competingWrite(IdempotencyReceipt.Key key, CountDownLatch entered, AtomicInteger calls) {
        try {
            unitOfWork.execute(() -> service.execute(key, "request", String.class, () -> {
                calls.incrementAndGet();
                entered.countDown();
                assertDoesNotThrow(() -> assertTrue(entered.await(5, TimeUnit.SECONDS)));
                return write();
            }));
            return 1;
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
            return 0;
        }
    }

    /**
     * 查询当前作用域业务记录数。
     * @return 已提交记录数
     */
    private int businessCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stratum_it_transaction_probe WHERE payload = ?", Integer.class, scope);
    }

    /**
     * 查询当前作用域Outbox事件数。
     * @return 已提交事件数
     */
    private int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, scope);
    }

    /**
     * 用于验证JSON重放的固定结果DTO。
     * @param id 业务标识
     * @param quantity 小数数量
     */
    public record ProbeResult(String id, BigDecimal quantity) { }
}
