package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
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
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证订单与有界明细的事务一致性、固定属性及版本保护。 */
class TradeOrderRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final TradeOrderRepository repository;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> masterIds = new ArrayList<>();
    private final List<String> orderIds = new ArrayList<>();

    /**
     * 装配实际仓储和测试数据访问。
     * @param repository 订单仓储
     * @param masters 主数据仓储
     * @param unitOfWork 事务入口
     * @param jdbc 断言及清理客户端
     */
    @Autowired
    TradeOrderRepositoryIntegrationTest(TradeOrderRepository repository, MasterDataRepository masters,
            UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.repository = repository; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 两类订单均保存精确金额、顺序和时间，分批更新只改变履约内容。 */
    @Test
    void persistsBothKindsAndUpdatesPartialRows() {
        for (var kind : TradeOrder.Kind.values()) {
            var order = order(kind); save(order);
            var initial = load(order); assertEquals(new BigDecimal("0.01"), initial.getTotalAmount());
            assertEquals(order.getCreatedAt(), initial.getCreatedAt());
            assertEquals(order.getOrderNumber(), initial.getOrderNumber());
            assertEquals(order.getLines().getFirst().getId(), initial.getLines().getFirst().getId());
            order.fulfill(Map.of(order.getLines().getFirst().getId(), quantity("0.25"))); save(order);
            assertEquals(TradeOrder.Status.PARTIAL, load(order).getStatus());
            order.fulfill(Map.of(order.getLines().getFirst().getId(), quantity("0.75"), order.getLines().getLast().getId(), quantity("1")));
            save(order);
            var completed = load(order);
            assertEquals(TradeOrder.Status.COMPLETED, completed.getStatus()); assertEquals(2L, completed.getVersion());
            assertEquals(new BigDecimal("0.005000"), completed.getLines().getFirst().getUnitPrice().value());
            assertEquals(initial.getTotalAmount(), completed.getTotalAmount());
        }
    }

    /** 交易方与物料类型由外键验证，失败时订单头和部分明细不残留。 */
    @Test
    void rejectsInvalidMasterKindsAndRollsBackHeader() {
        var template = order(TradeOrder.Kind.PURCHASE);
        var wrongParty = TradeOrder.create(TradeOrder.Kind.SALE, template.getPartyId(), template.getWarehouseId(), template.getLines());
        assertConflict(() -> save(wrongParty)); assertTrue(repository.findById(wrongParty.getId()).isEmpty());
        var wrongLine = OrderLine.create(master(MasterData.Kind.CUSTOMER), quantity("1"), new UnitPrice(BigDecimal.ONE));
        var wrongMaterial = TradeOrder.create(TradeOrder.Kind.PURCHASE, template.getPartyId(), template.getWarehouseId(),
                List.of(template.getLines().getFirst(), wrongLine));
        assertConflict(() -> save(wrongMaterial)); assertTrue(repository.findById(wrongMaterial.getId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_order_line WHERE order_id = ?", Integer.class, wrongMaterial.getId().toString()));
    }

    /** 订单引用的交易方、仓库和物料禁止删除。 */
    @Test
    void blocksDeletionOfReferencedMasterData() {
        var order = order(TradeOrder.Kind.SALE); save(order);
        for (UUID id : List.of(order.getPartyId(), order.getWarehouseId(), order.getLines().getFirst().getMaterialId())) {
            var master = masters.findById(id.toString()).orElseThrow();
            assertConflict(() -> unitOfWork.execute(() -> { masters.delete(master); return null; }));
        }
    }

    /** 重建对象也不能替换固定字段、删除明细或回退履约量。 */
    @Test
    void rejectsImmutableChangesAndFulfillmentRegression() {
        var order = order(TradeOrder.Kind.PURCHASE); save(order);
        var changedParty = TradeOrder.restore(order.getId(), order.getKind(), UUID.randomUUID(), order.getWarehouseId(),
                order.getLines(), order.getCreatedAt(), 0);
        assertConflict(() -> save(changedParty));
        var removed = TradeOrder.restore(order.getId(), order.getKind(), order.getPartyId(), order.getWarehouseId(),
                List.of(order.getLines().getFirst()), order.getCreatedAt(), 0);
        assertConflict(() -> save(removed));
        var first = order.getLines().getFirst();
        var changedLine = OrderLine.restore(first.getId(), first.getMaterialId(), quantity("2"), first.getUnitPrice(), Quantity.ZERO);
        var changed = TradeOrder.restore(order.getId(), order.getKind(), order.getPartyId(), order.getWarehouseId(),
                List.of(changedLine, order.getLines().getLast()), order.getCreatedAt(), 0);
        assertConflict(() -> save(changed));
        var initial = order.getLines(); order.fulfill(Map.of(first.getId(), quantity("0.5"))); save(order);
        var regression = TradeOrder.restore(order.getId(), order.getKind(), order.getPartyId(), order.getWarehouseId(), initial, order.getCreatedAt(), 1);
        assertConflict(() -> save(regression));
        assertEquals(quantity("0.5"), load(order).getLines().getFirst().getFulfilled());
    }

    /** 无事务拒写，外层创建或更新失败均撤销头与明细。 */
    @Test
    void requiresTransactionAndRollsBackAllRows() {
        var order = order(TradeOrder.Kind.PURCHASE);
        assertThrows(IllegalStateException.class, () -> repository.save(order));
        orderIds.add(order.getId().toString());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.save(order); throw new IllegalStateException("测试创建回滚");
        }));
        assertTrue(repository.findById(order.getId()).isEmpty());
        var existing = order(TradeOrder.Kind.SALE); save(existing);
        existing.fulfill(Map.of(existing.getLines().getFirst().getId(), quantity("0.5")));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.save(existing); throw new IllegalStateException("测试更新回滚");
        }));
        assertEquals(TradeOrder.Status.PENDING, load(existing).getStatus()); assertEquals(0L, load(existing).getVersion());
    }

    /** 两个相同订单版本竞争时仅一个提交，另一个不能覆盖已履约数量。
     * @throws Exception 并发失败或超时
     */
    @Test
    void concurrentFulfillmentUsesOneOrderVersion() throws Exception {
        var order = order(TradeOrder.Kind.PURCHASE); save(order);
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> compete(order.getId(), ready));
            var second = executor.submit(() -> compete(order.getId(), ready));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1L, load(order).getVersion());
        assertEquals(quantity("0.75"), load(order).getLines().getFirst().getFulfilled());
        assertConflict(() -> save(order));
    }

    /** 数据库约束保证明细序号、正数量和累计上限，查询不存在时返回空。 */
    @Test
    void databaseRejectsInvalidLineState() {
        var order = order(TradeOrder.Kind.PURCHASE); save(order);
        for (String assignment : List.of("line_number = 101", "ordered = 0", "fulfilled = 2", "unit_price = -1")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE erp_order_line SET " + assignment + " WHERE id = ?",
                    order.getLines().getFirst().getId().toString()));
        }
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    /** 仅清理本测试生成的订单、明细及主数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id);
            for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id);
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 创建两行订单，人民币汇总用于验证单次舍入。
     * @param kind 订单类别
     * @return 未保存订单
     */
    private TradeOrder order(TradeOrder.Kind kind) {
        var party = master(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER);
        var warehouse = master(MasterData.Kind.WAREHOUSE); var material = master(MasterData.Kind.MATERIAL);
        return TradeOrder.create(kind, party, warehouse, List.of(
                OrderLine.create(material, quantity("1"), new UnitPrice(new BigDecimal("0.005"))),
                OrderLine.create(material, quantity("1"), new UnitPrice(new BigDecimal("0.005")))));
    }

    /**
     * 创建并登记独立主数据。
     * @param kind 类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "订单测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 保存订单并登记清理标识。
     * @param order 订单聚合
     */
    private void save(TradeOrder order) {
        orderIds.add(order.getId().toString()); unitOfWork.execute(() -> { repository.save(order); return null; });
    }

    /**
     * 读取数据库快照。
     * @param order 订单标识来源
     * @return 完整订单
     */
    private TradeOrder load(TradeOrder order) { return repository.findById(order.getId()).orElseThrow(); }

    /**
     * 等待双方读取同版本，再提交相同履约量。
     * @param id 订单标识
     * @param ready 就绪屏障
     * @return 提交为1，冲突为0
     */
    private int compete(UUID id, CountDownLatch ready) {
        try {
            return unitOfWork.execute(() -> {
                var current = repository.findById(id).orElseThrow(); ready.countDown();
                assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
                current.fulfill(Map.of(current.getLines().getFirst().getId(), quantity("0.75"))); repository.save(current); return 1;
            });
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode()); return 0;
        }
    }

    /**
     * 验证统一冲突码。
     * @param operation 预期冲突操作
     */
    private void assertConflict(Runnable operation) {
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, operation::run).getErrorCode());
    }

    /**
     * 解析精确数量。
     * @param value 十进制文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
