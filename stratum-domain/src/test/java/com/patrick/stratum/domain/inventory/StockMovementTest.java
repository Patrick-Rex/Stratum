package com.patrick.stratum.domain.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证履约来源、流水方向和不可变数量快照。 */
class StockMovementTest {
    /** 入库流水保留保存时的余额，后续出库不会修改旧流水。 */
    @Test
    void retainsImmutableReceiptAndIssueSnapshots() {
        var balance = StockBalance.open(UUID.randomUUID(), UUID.randomUUID());
        balance.receive(quantity("1.25"));
        balance.markPersisted(0);
        var receipt = StockMovement.create(balance, source(), StockMovement.Direction.RECEIPT, quantity("1.25"));
        balance.issue(quantity("0.25"));
        balance.markPersisted(1);
        var issue = StockMovement.create(balance, source(), StockMovement.Direction.ISSUE, quantity("0.25"));
        assertEquals(quantity("1.25"), receipt.getBalanceAfter());
        assertEquals(0, receipt.getBalanceVersion());
        assertEquals(quantity("1"), issue.getBalanceAfter());
        assertEquals(1, issue.getBalanceVersion());
        assertEquals(balance.getId(), issue.getBalanceId());
        assertEquals(StockMovement.Direction.ISSUE, issue.getDirection());
        assertEquals(quantity("0.25"), issue.getQuantity());
        assertNotEquals(receipt.getId(), issue.getId());
        assertEquals(issue.getOccurredAt().truncatedTo(ChronoUnit.MICROS), issue.getOccurredAt());
    }

    /** 同一订单行可记录不同批次，任一来源标识缺失均被拒绝。 */
    @Test
    void distinguishesPartialPostingsAndRequiresEverySourceId() {
        var first = source();
        var second = new StockMovement.Source(first.orderId(), UUID.randomUUID(), first.orderLineId());
        assertNotEquals(first, second);
        assertEquals(first.orderLineId(), second.orderLineId());
        assertThrows(NullPointerException.class, () -> new StockMovement.Source(null, first.postingId(), first.orderLineId()));
        assertThrows(NullPointerException.class, () -> new StockMovement.Source(first.orderId(), null, first.orderLineId()));
        assertThrows(NullPointerException.class, () -> new StockMovement.Source(first.orderId(), first.postingId(), null));
    }

    /** 未保存余额、零变动、负的历史余额或溢出的历史余额不能形成流水。 */
    @Test
    void rejectsInconsistentMovementSnapshots() {
        var balance = StockBalance.open(UUID.randomUUID(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> StockMovement.create(balance, source(), StockMovement.Direction.ISSUE, quantity("1")));
        balance.markPersisted(0);
        assertThrows(IllegalArgumentException.class,
                () -> StockMovement.create(balance, source(), StockMovement.Direction.RECEIPT, Quantity.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> StockMovement.create(balance, source(), StockMovement.Direction.RECEIPT, quantity("1")));
        balance.receive(quantity("99999999999999999999999999.999999999999"));
        assertThrows(IllegalArgumentException.class,
                () -> StockMovement.create(balance, source(), StockMovement.Direction.ISSUE, quantity("1")));
    }

    /** 重建保持所有持久化字段，拒绝负版本或会被数据库截断的时间。 */
    @Test
    void restoresExactSnapshotAndRejectsLossyTime() {
        UUID id = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        var source = source();
        Instant time = Instant.parse("2026-10-24T01:02:03.123456Z");
        var movement = StockMovement.restore(id, balanceId, source, StockMovement.Direction.ISSUE,
                quantity("1.5"), Quantity.ZERO, 2, time);
        assertEquals(id, movement.getId());
        assertEquals(source, movement.getSource());
        assertEquals(time, movement.getOccurredAt());
        assertThrows(IllegalArgumentException.class, () -> StockMovement.restore(id, balanceId, source,
                StockMovement.Direction.ISSUE, quantity("1"), Quantity.ZERO, -1, time));
        assertThrows(IllegalArgumentException.class, () -> StockMovement.restore(id, balanceId, source,
                StockMovement.Direction.ISSUE, quantity("1"), Quantity.ZERO, 1, time.plusNanos(1)));
    }

    /**
     * 创建彼此独立的履约来源。
     * @return 随机来源值对象
     */
    private StockMovement.Source source() {
        return new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    /**
     * 转换精确十进制测试数量。
     * @param value 数量文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
