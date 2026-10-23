package com.patrick.stratum.domain.inventory;

import java.math.BigDecimal;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证精确数量、库存下界及仓库维度，不依赖数据库或其他框架。 */
class StockBalanceTest {
    /** 十进制加减无二进制浮点误差，多余小数零不改变值对象相等性。 */
    @Test
    void preservesExactDecimalArithmetic() {
        var balance = StockBalance.open(UUID.randomUUID(), UUID.randomUUID());
        balance.receive(quantity("0.1"));
        balance.receive(quantity("0.2"));
        assertEquals(quantity("0.3000"), balance.getQuantity());
        balance.issue(quantity("0.3"));
        assertEquals(Quantity.ZERO, balance.getQuantity());
        assertEquals(quantity("1.0"), quantity("1.00000000000000000"));
    }

    /** 库存不足与零操作不会修改原余额。 */
    @Test
    void rejectsNegativeStockWithoutMutation() {
        var balance = StockBalance.open(UUID.randomUUID(), UUID.randomUUID());
        balance.receive(quantity("1.25"));
        var failure = assertThrows(StratumException.class, () -> balance.issue(quantity("1.250000000001")));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertEquals(quantity("1.25"), balance.getQuantity());
        assertThrows(IllegalArgumentException.class, () -> balance.issue(Quantity.ZERO));
        assertThrows(IllegalArgumentException.class, () -> balance.receive(Quantity.ZERO));
    }

    /** 只接受可以无损写入DECIMAL(38,12)的数值，拒绝极端指数与溢出。 */
    @Test
    void boundsStoragePrecisionWithoutRounding() {
        assertDoesNotThrow(() -> quantity("99999999999999999999999999.999999999999"));
        for (String invalid : new String[]{"-1", "0.0000000000001", "100000000000000000000000000", "1E+2147483647"}) {
            assertThrows(IllegalArgumentException.class, () -> quantity(invalid));
        }
        var balance = StockBalance.open(UUID.randomUUID(), UUID.randomUUID());
        balance.receive(quantity("99999999999999999999999999.999999999999"));
        var before = balance.getQuantity();
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class,
                () -> balance.receive(quantity("0.000000000001"))).getErrorCode());
        assertEquals(before, balance.getQuantity());
    }

    /** 同物料的不同仓库拥有独立聚合与余额。 */
    @Test
    void separatesWarehouseDimensions() {
        UUID material = UUID.randomUUID();
        var first = StockBalance.open(material, UUID.randomUUID());
        var second = StockBalance.open(material, UUID.randomUUID());
        first.receive(quantity("5"));
        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(first.getWarehouseId(), second.getWarehouseId());
        assertEquals(material, second.getMaterialId());
        assertEquals(Quantity.ZERO, second.getQuantity());
    }

    /** 持久化版本顺序推进，事件保存变更当时快照。 */
    @Test
    void preservesVersionedEventSnapshot() {
        var balance = StockBalance.open(UUID.randomUUID(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new StockChanged(balance));
        balance.receive(quantity("2"));
        balance.markPersisted(0);
        var event = new StockChanged(balance);
        balance.issue(quantity("1"));
        balance.markPersisted(1);
        assertEquals(quantity("2").value(), event.getQuantity());
        assertEquals(0, event.getVersion());
        assertEquals(balance.getId().toString(), event.getAggregateId());
        assertThrows(IllegalArgumentException.class, () -> balance.markPersisted(3));
    }

    /**
     * 从十进制文本创建测试值，避免double转换。
     * @param value 精确小数文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
