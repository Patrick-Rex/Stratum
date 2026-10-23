package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.patrick.stratum.domain.inventory.Quantity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证分批履约凭据的来源、不可变快照及存储边界。 */
class FulfillmentTest {
    /** 分批凭据保留当次数量和订单版本，不随后续履约改变。 */
    @Test
    void snapshotsEachPostingIndependently() {
        var order = order(); var lineId = order.getLines().getFirst().getId();
        var first = fulfill(order, quantity("0.25"));
        var second = fulfill(order, quantity("0.75"));
        assertNotEquals(first.getId(), second.getId());
        assertEquals(order.getId(), first.getOrderId());
        assertEquals(order.getKind(), first.getKind());
        assertEquals(1, first.getOrderVersion());
        assertEquals(2, second.getOrderVersion());
        assertEquals(new Fulfillment.Line(lineId, quantity("0.25")), first.getLines().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> first.getLines().clear());
        assertEquals(TradeOrder.Status.COMPLETED, order.getStatus());
    }

    /** 未保存或初始版本订单不能生成履约凭据，未知来源和过大数量也被拒绝。 */
    @Test
    void requiresSavedFulfillmentVersionAndKnownLines() {
        var order = order(); var lineId = order.getLines().getFirst().getId();
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.create(order, Map.of(lineId, quantity("1"))));
        order.markPersisted(0);
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.create(order, Map.of(lineId, quantity("1"))));
        order.fulfill(Map.of(lineId, quantity("0.25"))); order.markPersisted(1);
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.create(order, Map.of(UUID.randomUUID(), quantity("0.25"))));
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.create(order, Map.of(lineId, quantity("0.5"))));
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.create(order, Map.of(lineId, Quantity.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.create(order, Map.of()));
    }

    /** 重建凭据复制明细，拒绝重复行、空列表、超量行及不精确时间。 */
    @Test
    void restoresImmutableBoundedRows() {
        var line = new Fulfillment.Line(UUID.randomUUID(), quantity("1"));
        var rows = new ArrayList<>(List.of(line));
        UUID id = UUID.randomUUID(); UUID orderId = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-24T01:02:03.123456Z");
        var value = Fulfillment.restore(id, orderId, TradeOrder.Kind.PURCHASE, 1, rows, time);
        rows.clear();
        assertEquals(1, value.getLines().size()); assertEquals(time, value.getCreatedAt()); assertEquals(id, value.getId());
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.restore(id, orderId, TradeOrder.Kind.PURCHASE, 1, List.of(line, line), time));
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.restore(id, orderId, TradeOrder.Kind.PURCHASE, 1, List.of(), time));
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.restore(id, orderId, TradeOrder.Kind.PURCHASE, 0, List.of(line), time));
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.restore(id, orderId, TradeOrder.Kind.PURCHASE, 1, List.of(line), time.plusNanos(1)));
        var tooMany = IntStream.range(0, 101).mapToObj(index -> new Fulfillment.Line(UUID.randomUUID(), quantity("1"))).toList();
        assertThrows(IllegalArgumentException.class, () -> Fulfillment.restore(id, orderId, TradeOrder.Kind.PURCHASE, 1, tooMany, time));
    }

    /** 来源标识和正数量不可缺失。 */
    @Test
    void rejectsMissingSourceAndNonPositiveQuantity() {
        assertThrows(NullPointerException.class, () -> new Fulfillment.Line(null, quantity("1")));
        assertThrows(NullPointerException.class, () -> new Fulfillment.Line(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> new Fulfillment.Line(UUID.randomUUID(), Quantity.ZERO));
    }

    /**
     * 对订单执行一批履约并推进保存版本。
     * @param order 测试订单
     * @param quantity 当次数量
     * @return 不可变履约凭据
     */
    private Fulfillment fulfill(TradeOrder order, Quantity quantity) {
        if (order.getVersion() == null) order.markPersisted(0);
        var quantities = Map.of(order.getLines().getFirst().getId(), quantity);
        order.fulfill(quantities); order.markPersisted(order.getVersion() + 1);
        return Fulfillment.create(order, quantities);
    }

    /**
     * 创建一行销售订单。
     * @return 新订单
     */
    private TradeOrder order() {
        return TradeOrder.create(TradeOrder.Kind.SALE, UUID.randomUUID(), UUID.randomUUID(),
                List.of(OrderLine.create(UUID.randomUUID(), quantity("1"), new UnitPrice(BigDecimal.ONE))));
    }

    /**
     * 解析精确数量。
     * @param value 十进制文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
