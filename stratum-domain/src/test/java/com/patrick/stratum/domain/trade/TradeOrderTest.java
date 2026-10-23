package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证订单金额、分批状态和多行变更原子性。 */
class TradeOrderTest {
    /** 两类订单均生成独立单号，金额先汇总再四舍五入到分。 */
    @Test
    void createsKindsAndRoundsOnlyAfterSumming() {
        for (var kind : TradeOrder.Kind.values()) {
            var order = TradeOrder.create(kind, UUID.randomUUID(), UUID.randomUUID(), List.of(line("1", "0.005"), line("1", "0.005")));
            assertEquals(new BigDecimal("0.01"), order.getTotalAmount());
            assertEquals((kind == TradeOrder.Kind.PURCHASE ? "PO-" : "SO-") + order.getId(), order.getOrderNumber());
            assertEquals(TradeOrder.Status.PENDING, order.getStatus());
            assertNull(order.getVersion());
        }
        assertEquals(new BigDecimal("0.01"), order(List.of(line("1", "0.005"))).getTotalAmount());
    }

    /** 不同行分批履约推进状态，金额和过去明细快照保持不变。 */
    @Test
    void progressesPartialFulfillmentAcrossLines() {
        var first = line("1.25", "2"); var second = line("2", "3");
        var order = order(List.of(first, second)); var initial = order.getLines();
        order.fulfill(Map.of(first.getId(), quantity("0.25")));
        assertEquals(TradeOrder.Status.PARTIAL, order.getStatus());
        order.fulfill(Map.of(first.getId(), quantity("1"), second.getId(), quantity("2")));
        assertEquals(TradeOrder.Status.COMPLETED, order.getStatus());
        assertEquals(new BigDecimal("8.50"), order.getTotalAmount());
        assertEquals(Quantity.ZERO, initial.getFirst().getFulfilled());
        assertThrows(StratumException.class, () -> order.fulfill(Map.of(first.getId(), quantity("0.01"))));
    }

    /** 批次中后续行超量或引用其他订单行时，之前行也不改变。 */
    @Test
    void validatesWholeBatchBeforeReplacingAnyLine() {
        var first = line("1", "1"); var second = line("2", "1");
        var order = order(List.of(first, second));
        assertThrows(StratumException.class, () -> order.fulfill(Map.of(first.getId(), quantity("1"), second.getId(), quantity("3"))));
        assertEquals(TradeOrder.Status.PENDING, order.getStatus());
        assertEquals(Quantity.ZERO, order.getLines().getFirst().getFulfilled());
        assertThrows(StratumException.class, () -> order.fulfill(Map.of(UUID.randomUUID(), quantity("1"))));
        assertThrows(IllegalArgumentException.class, () -> order.fulfill(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> order.fulfill(Map.of(first.getId(), Quantity.ZERO)));
    }

    /** 明细有界且不可变，重复行标识与已有履约的新订单均被拒绝。 */
    @Test
    void boundsAndCopiesOrderLines() {
        var line = line("1", "1"); var lines = new ArrayList<>(List.of(line));
        var order = order(lines); lines.clear();
        assertEquals(1, order.getLines().size());
        assertThrows(UnsupportedOperationException.class, () -> order.getLines().clear());
        assertThrows(IllegalArgumentException.class, () -> order(List.of()));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(line, line)));
        assertThrows(IllegalArgumentException.class, () -> order(IntStream.range(0, 101).mapToObj(index -> line("1", "1")).toList()));
        assertThrows(IllegalArgumentException.class, () -> order(List.of(line.fulfill(quantity("0.5")))));
    }

    /** 极大数量乘单价导致总金额超界时必须拒绝。 */
    @Test
    void rejectsTotalAmountOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> order(List.of(line("99999999999999999999999999", "9999999999999"))));
    }

    /** 版本及事件快照不会被后续履约覆盖，重建保留单号和状态。 */
    @Test
    void preservesVersionedEventsAndRestoredIdentity() {
        var line = line("2", "3"); var order = order(List.of(line));
        assertThrows(IllegalArgumentException.class, () -> new OrderChanged(order, OrderChanged.Change.CREATED));
        assertThrows(StratumException.class, () -> order.requireVersion(0));
        order.markPersisted(0); order.requireVersion(0);
        var event = new OrderChanged(order, OrderChanged.Change.CREATED);
        order.fulfill(Map.of(line.getId(), quantity("1"))); order.markPersisted(1);
        assertEquals(TradeOrder.Status.PENDING, event.getStatus());
        assertEquals(0, event.getVersion());
        assertThrows(StratumException.class, () -> order.requireVersion(0));
        assertThrows(IllegalArgumentException.class, () -> order.markPersisted(3));
        var restored = TradeOrder.restore(order.getId(), order.getKind(), order.getPartyId(), order.getWarehouseId(),
                order.getLines(), order.getCreatedAt(), order.getVersion());
        assertEquals(order.getOrderNumber(), restored.getOrderNumber());
        assertEquals(TradeOrder.Status.PARTIAL, restored.getStatus());
        assertEquals(order.getTotalAmount(), restored.getTotalAmount());
    }

    /**
     * 创建独立采购订单。
     * @param lines 明细
     * @return 新订单
     */
    private TradeOrder order(List<OrderLine> lines) {
        return TradeOrder.create(TradeOrder.Kind.PURCHASE, UUID.randomUUID(), UUID.randomUUID(), lines);
    }

    /**
     * 创建精确计价的订单行。
     * @param quantity 订购数量文本
     * @param price 单价文本
     * @return 新行
     */
    private OrderLine line(String quantity, String price) {
        return OrderLine.create(UUID.randomUUID(), quantity(quantity), new UnitPrice(new BigDecimal(price)));
    }

    /**
     * 解析数量文本。
     * @param value 十进制文本
     * @return 精确数量
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
