package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.Fulfillment;
import com.patrick.stratum.domain.trade.FulfillmentRepository;
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证分批履约凭据的完整追加、来源外键和事务回滚。 */
class FulfillmentRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final FulfillmentRepository repository;
    private final TradeOrderRepository orders;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> orderIds = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /**
     * 注入真实持久化与事务能力。
     * @param repository 履约仓储
     * @param orders 订单仓储
     * @param masters 主数据仓储
     * @param unitOfWork 工作单元
     * @param jdbc 验证及清理客户端
     */
    @Autowired
    FulfillmentRepositoryIntegrationTest(FulfillmentRepository repository, TradeOrderRepository orders,
            MasterDataRepository masters, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.repository = repository; this.orders = orders; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 两类订单分批保存两行履约，来源、数量与时间完整保留。 */
    @Test
    void persistsPartialReceiptsAndIssues() {
        for (var kind : TradeOrder.Kind.values()) {
            var order = order(kind);
            var first = posting(order, "0.25"); var second = posting(order, "0.75");
            assertEquals(2, count(order));
            for (var value : List.of(first, second)) {
                assertEquals(value.getOrderVersion(), jdbc.queryForObject("SELECT order_version FROM erp_fulfillment WHERE id = ?", Long.class, value.getId().toString()));
                assertEquals(value.getCreatedAt(), jdbc.queryForObject("SELECT created_at FROM erp_fulfillment WHERE id = ?",
                        (result, index) -> result.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), value.getId().toString()));
                for (var line : value.getLines()) {
                    assertEquals(line.quantity().value(), jdbc.queryForObject("SELECT quantity FROM erp_fulfillment_line WHERE posting_id = ? AND order_line_id = ?",
                            BigDecimal.class, value.getId().toString(), line.orderLineId().toString()));
                }
            }
            assertEquals(TradeOrder.Status.COMPLETED, orders.findById(order.getId()).orElseThrow().getStatus());
        }
    }

    /** 同一订单版本即使使用另一个凭据UUID也不能重复保存。 */
    @Test
    void rejectsDuplicatePostingAndOrderVersion() {
        var order = order(TradeOrder.Kind.PURCHASE); var first = posting(order, "0.25");
        assertConflict(() -> append(first));
        var duplicate = Fulfillment.restore(UUID.randomUUID(), first.getOrderId(), first.getKind(), first.getOrderVersion(), first.getLines(), first.getCreatedAt());
        assertConflict(() -> append(duplicate)); assertEquals(1, count(order));
    }

    /** 复合外键阻止使用其他订单的明细，失败后头与所有行回滚。 */
    @Test
    void rejectsCrossOrderLineAndWrongKind() {
        var order = order(TradeOrder.Kind.PURCHASE); var other = order(TradeOrder.Kind.PURCHASE);
        var template = prepare(order, "0.25");
        var mixed = Fulfillment.restore(UUID.randomUUID(), order.getId(), order.getKind(), order.getVersion(),
                List.of(template.getLines().getFirst(), new Fulfillment.Line(other.getLines().getFirst().getId(), quantity("0.25"))), template.getCreatedAt());
        assertConflict(() -> append(mixed)); assertEquals(0, count(order));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment_line WHERE posting_id = ?", Integer.class, mixed.getId().toString()));
        var wrongKind = Fulfillment.restore(UUID.randomUUID(), order.getId(), TradeOrder.Kind.SALE, order.getVersion(), template.getLines(), template.getCreatedAt());
        assertConflict(() -> append(wrongKind)); assertEquals(0, count(order));
    }

    /** 凭据必须使用真实已保存的订单版本，事务外追加也被拒绝。 */
    @Test
    void requiresSavedOrderVersionAndTransaction() {
        var order = order(TradeOrder.Kind.SALE); var template = prepare(order, "0.25");
        assertThrows(IllegalStateException.class, () -> repository.append(template));
        var future = Fulfillment.restore(UUID.randomUUID(), order.getId(), order.getKind(), 2, template.getLines(), template.getCreatedAt());
        assertConflict(() -> append(future));
        var missing = Fulfillment.restore(UUID.randomUUID(), UUID.randomUUID(), order.getKind(), 1, template.getLines(), template.getCreatedAt());
        assertConflict(() -> append(missing)); assertEquals(0, count(order));
    }

    /** 外层失败时订单状态与履约凭据同步撤销，重新加载后可正常重试。 */
    @Test
    void rollsBackOrderAndPostingTogether() {
        var order = order(TradeOrder.Kind.PURCHASE);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            posting(order, "0.25"); throw new IllegalStateException("测试回滚");
        }));
        var current = orders.findById(order.getId()).orElseThrow();
        assertEquals(0L, current.getVersion()); assertEquals(TradeOrder.Status.PENDING, current.getStatus());
        assertEquals(0, count(order)); posting(current, "0.25"); assertEquals(1, count(order));
    }

    /** 两个并发追加者只提交一份完整凭据。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentAppendCommitsOneCompletePosting() throws Exception {
        var order = order(TradeOrder.Kind.SALE); var template = prepare(order, "0.25");
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> compete(template, ready));
            var second = executor.submit(() -> compete(template, ready));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, count(order));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment_line WHERE order_id = ?", Integer.class, order.getId().toString()));
    }

    /** 按引用顺序只清理本测试生成的凭据、订单与主数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id);
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 创建持久化的两行订单。
     * @param kind 订单类别
     * @return 已保存订单
     */
    private TradeOrder order(TradeOrder.Kind kind) {
        UUID material = master(MasterData.Kind.MATERIAL);
        var order = TradeOrder.create(kind, master(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER),
                master(MasterData.Kind.WAREHOUSE), List.of(OrderLine.create(material, quantity("1"), new UnitPrice(BigDecimal.ONE)),
                        OrderLine.create(material, quantity("1"), new UnitPrice(BigDecimal.ONE))));
        orderIds.add(order.getId().toString()); unitOfWork.execute(() -> { orders.save(order); return null; }); return order;
    }

    /**
     * 保存独立主数据。
     * @param kind 主数据类别
     * @return 主数据标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "履约测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 更新两行累计数量并创建尚未追加的凭据。
     * @param order 订单
     * @param value 每行当次数量
     * @return 凭据
     */
    private Fulfillment prepare(TradeOrder order, String value) {
        var quantities = Map.of(order.getLines().getFirst().getId(), quantity(value), order.getLines().getLast().getId(), quantity(value));
        return unitOfWork.execute(() -> { order.fulfill(quantities); orders.save(order); return Fulfillment.create(order, quantities); });
    }

    /**
     * 同事务更新订单并追加凭据。
     * @param order 订单
     * @param value 每行当次数量
     * @return 已保存凭据
     */
    private Fulfillment posting(TradeOrder order, String value) {
        return unitOfWork.execute(() -> { var posting = prepare(order, value); repository.append(posting); return posting; });
    }

    /**
     * 在写事务中追加已有凭据。
     * @param value 凭据
     */
    private void append(Fulfillment value) { unitOfWork.execute(() -> { repository.append(value); return null; }); }

    /**
     * 统计当前订单履约批次。
     * @param order 订单
     * @return 批次数
     */
    private int count(TradeOrder order) { return jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id = ?", Integer.class, order.getId().toString()); }

    /**
     * 双方就绪后并发追加同一凭据。
     * @param value 履约凭据
     * @param ready 就绪屏障
     * @return 提交为1，冲突为0
     */
    private int compete(Fulfillment value, CountDownLatch ready) {
        ready.countDown(); assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
        try { append(value); return 1; }
        catch (StratumException conflict) { assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode()); return 0; }
    }

    /**
     * 验证统一冲突码。
     * @param operation 预期冲突操作
     */
    private void assertConflict(Runnable operation) { assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, operation::run).getErrorCode()); }

    /**
     * 解析精确数量。
     * @param value 十进制文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
