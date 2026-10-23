package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.inventory.StockPosting;
import com.patrick.stratum.application.inventory.StockPostingApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import static org.junit.jupiter.api.Assertions.*;

/** 完整应用真实数据库验证库存内部过账、并发和三项写入的事务原子性。 */
class StockPostingIntegrationTest extends MariaDbIntegrationTest {
    private final StockPostingApplicationService service;
    private final StockBalanceRepository balances;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> masterIds = new ArrayList<>();
    private final String trace = UUID.randomUUID().toString();

    /**
     * 装配内部应用入口及断言依赖。
     * @param service 库存过账入口
     * @param balances 余额仓储
     * @param masters 主数据仓储
     * @param unitOfWork 外层事务
     * @param jdbc 数据库断言与清理客户端
     */
    @Autowired
    StockPostingIntegrationTest(StockPostingApplicationService service, StockBalanceRepository balances,
            MasterDataRepository masters, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.service = service; this.balances = balances; this.masters = masters;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 将所有测试事件关联到独立trace，便于验证及清理。 */
    @BeforeEach
    void bindTrace() { TraceIdContext.setTraceId(trace); }

    /** 连续收货和出库精确改变库存，同时保存每次流水与事件。 */
    @Test
    void postsReceiptsAndIssuesWithEvents() {
        var input = input(StockMovement.Direction.RECEIPT, "1.25");
        var received = post(input);
        var issued = post(with(input, source(), StockMovement.Direction.ISSUE, "0.125"));
        assertEquals(quantity("1.125"), issued.getBalanceAfter());
        assertEquals(1, issued.getBalanceVersion());
        assertEquals(received.getBalanceId(), issued.getBalanceId());
        assertEquals(quantity("1.125"), balances.find(input.getMaterialId(), input.getWarehouseId()).orElseThrow().getQuantity());
        assertCounts(input, 2, 2);
        assertEquals(List.of("erp.stock.issued", "erp.stock.received"), jdbc.queryForList(
                "SELECT event_type FROM outbox_event WHERE trace_id = ? ORDER BY event_type", String.class, trace));
    }

    /** 无余额或余额不足时，不保留新余额、流水或事件。 */
    @Test
    void rejectsInsufficientStockWithoutSideEffects() {
        var input = input(StockMovement.Direction.ISSUE, "1");
        assertConflict(() -> post(input));
        assertTrue(balances.find(input.getMaterialId(), input.getWarehouseId()).isEmpty());
        assertCounts(input, 0, 0);
        post(with(input, source(), StockMovement.Direction.RECEIPT, "0.5"));
        assertConflict(() -> post(input));
        assertEquals(quantity("0.5"), balances.find(input.getMaterialId(), input.getWarehouseId()).orElseThrow().getQuantity());
        assertCounts(input, 1, 1);
    }

    /** 重复履约行由数据库阻止，第二次余额增量和事件一起回滚。 */
    @Test
    void duplicatePostingDoesNotChangeBalanceOrPublishAgain() {
        var input = input(StockMovement.Direction.RECEIPT, "1"); post(input);
        assertConflict(() -> post(input));
        assertEquals(quantity("1"), balances.find(input.getMaterialId(), input.getWarehouseId()).orElseThrow().getQuantity());
        assertCounts(input, 1, 1);
    }

    /** 调用方异常撤销所有写入，原输入随后可以重新执行。 */
    @Test
    void outerRollbackRemovesBalanceMovementAndEvent() {
        var input = input(StockMovement.Direction.RECEIPT, "2");
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            service.post(input); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(balances.find(input.getMaterialId(), input.getWarehouseId()).isEmpty());
        assertCounts(input, 0, 0);
        post(input); assertCounts(input, 1, 1);
    }

    /** 输入缺少有效主数据或正数量，以及未加入事务时，均不能过账。 */
    @Test
    void validatesReferencesQuantityAndTransactionBoundary() {
        var input = input(StockMovement.Direction.RECEIPT, "1");
        assertThrows(IllegalTransactionStateException.class, () -> service.post(input));
        assertThrows(IllegalArgumentException.class, () -> post(with(input, source(), StockMovement.Direction.RECEIPT, "0")));
        for (UUID material : List.of(UUID.randomUUID(), master(MasterData.Kind.CUSTOMER))) {
            var invalid = StockPosting.builder().materialId(material).warehouseId(input.getWarehouseId())
                    .source(source()).direction(StockMovement.Direction.RECEIPT).quantity(quantity("1")).build();
            assertEquals(CommonErrorCode.NOT_FOUND, assertThrows(StratumException.class, () -> post(invalid)).getErrorCode());
        }
        assertCounts(input, 0, 0);
    }

    /** 两个同时出库请求不能把同一份库存扣成负数。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentIssuesNeverOversell() throws Exception {
        var input = input(StockMovement.Direction.RECEIPT, "1"); post(input);
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> competingPost(with(input, source(), StockMovement.Direction.ISSUE, "0.75"), ready));
            var second = executor.submit(() -> competingPost(with(input, source(), StockMovement.Direction.ISSUE, "0.75"), ready));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(quantity("0.25"), balances.find(input.getMaterialId(), input.getWarehouseId()).orElseThrow().getQuantity());
        assertCounts(input, 2, 2);
    }

    /** 事件信封校验失败发生在余额与流水写入后，必须撤销前两项写入。 */
    @Test
    void eventFailureRollsBackAlreadyWrittenBalanceAndMovement() {
        var input = input(StockMovement.Direction.RECEIPT, "1");
        TraceIdContext.setTraceId("x".repeat(65));
        try {
            assertThrows(IllegalArgumentException.class, () -> post(input));
        } finally { TraceIdContext.setTraceId(trace); }
        assertTrue(balances.find(input.getMaterialId(), input.getWarehouseId()).isEmpty());
        assertCounts(input, 0, 0);
        post(input); assertCounts(input, 1, 1);
    }

    /** 一个工作单元内后续行库存不足时，撤销此前行的库存、流水和事件。 */
    @Test
    void laterLineFailureRollsBackEarlierPosting() {
        var receipt = input(StockMovement.Direction.RECEIPT, "2");
        var issue = input(StockMovement.Direction.ISSUE, "1");
        assertConflict(() -> unitOfWork.execute(() -> {
            service.post(receipt); service.post(issue); return null;
        }));
        assertTrue(balances.find(receipt.getMaterialId(), receipt.getWarehouseId()).isEmpty());
        assertTrue(balances.find(issue.getMaterialId(), issue.getWarehouseId()).isEmpty());
        assertCounts(receipt, 0, 0); assertCounts(issue, 0, 0);
    }

    /** 首次收货并发重试只建立一个余额并提交一份流水与事件。
     * @throws Exception 并发执行失败或超时
     */
    @Test
    void concurrentFirstReceiptCommitsOnlyOnce() throws Exception {
        var input = input(StockMovement.Direction.RECEIPT, "1.25");
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> competingPost(input, ready));
            var second = executor.submit(() -> competingPost(input, ready));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(quantity("1.25"), balances.find(input.getMaterialId(), input.getWarehouseId()).orElseThrow().getQuantity());
        assertCounts(input, 1, 1);
    }

    /** 按外键顺序清除本测试数据及trace关联事件，并释放线程上下文。 */
    @AfterEach
    void cleanOwnRows() {
        try {
            unitOfWork.execute(() -> {
                jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", trace);
                for (String id : masterIds) {
                    jdbc.update("DELETE m FROM erp_stock_movement m JOIN erp_stock_balance b ON b.id = m.balance_id WHERE b.material_id = ? OR b.warehouse_id = ?", id, id);
                    jdbc.update("DELETE FROM erp_stock_balance WHERE material_id = ? OR warehouse_id = ?", id, id);
                }
                for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
                return null;
            });
        } finally { TraceIdContext.clear(); }
    }

    /**
     * 创建带真实主数据的输入。
     * @param direction 库存方向
     * @param value 数量文本
     * @return 内部输入
     */
    private StockPosting input(StockMovement.Direction direction, String value) {
        return StockPosting.builder().materialId(master(MasterData.Kind.MATERIAL)).warehouseId(master(MasterData.Kind.WAREHOUSE))
                .source(source()).direction(direction).quantity(quantity(value)).build();
    }

    /**
     * 保留维度，替换本次履约信息。
     * @param input 原始输入
     * @param source 新来源
     * @param direction 方向
     * @param value 数量文本
     * @return 新输入
     */
    private StockPosting with(StockPosting input, StockMovement.Source source, StockMovement.Direction direction, String value) {
        return StockPosting.builder().materialId(input.getMaterialId()).warehouseId(input.getWarehouseId())
                .source(source).direction(direction).quantity(quantity(value)).build();
    }

    /**
     * 在真实工作单元中执行内部用例。
     * @param input 过账输入
     * @return 已保存流水
     */
    private StockMovement post(StockPosting input) { return unitOfWork.execute(() -> service.post(input)); }

    /**
     * 创建已登记清理的测试主数据。
     * @param kind 主数据类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "过账测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; });
        return UUID.fromString(value.getId());
    }

    /**
     * 核对当前维度的流水及本测试事件数量。
     * @param input 库存维度
     * @param movements 预期流水数
     * @param events 预期事件数
     */
    private void assertCounts(StockPosting input, int movements, int events) {
        assertEquals(movements, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement m JOIN erp_stock_balance b ON b.id = m.balance_id WHERE b.material_id = ? AND b.warehouse_id = ?",
                Integer.class, input.getMaterialId().toString(), input.getWarehouseId().toString()));
        assertEquals(events, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, trace));
    }

    /**
     * 断言统一冲突码。
     * @param operation 冲突操作
     */
    private void assertConflict(Runnable operation) {
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, operation::run).getErrorCode());
    }

    /**
     * 在两个工作线程就绪后竞争库存，显式传递并清理trace。
     * @param input 独立履约输入
     * @param ready 就绪屏障
     * @return 提交为1，冲突为0
     */
    private int competingPost(StockPosting input, CountDownLatch ready) {
        TraceIdContext.setTraceId(trace);
        try {
            ready.countDown(); assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
            post(input); return 1;
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode()); return 0;
        } finally { TraceIdContext.clear(); }
    }

    /**
     * 创建独立履约来源。
     * @return 来源值对象
     */
    private StockMovement.Source source() { return new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); }

    /**
     * 解析精确数量。
     * @param value 十进制文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
