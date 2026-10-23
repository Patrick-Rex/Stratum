package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证固定订单行、精确计价及分批累计履约约束。 */
class OrderLineTest {
    /** 小数计价不提前舍入，相等单价归一化后保持值对象相等性。 */
    @Test
    void pricesQuantitiesExactlyBeforeTotalRounding() {
        var price = new UnitPrice(new BigDecimal("0.123456"));
        var line = OrderLine.create(UUID.randomUUID(), quantity("0.000000000001"), price);
        assertEquals(0, new BigDecimal("0.000000000000123456").compareTo(line.getExactAmount()));
        assertEquals(new UnitPrice(new BigDecimal("1")), new UnitPrice(new BigDecimal("1.0000000000")));
        assertDoesNotThrow(() -> new UnitPrice(BigDecimal.ZERO));
    }

    /** 无法精确保存的价格和极端指数被拒绝，最大单价可用。 */
    @Test
    void rejectsInvalidPriceBounds() {
        assertDoesNotThrow(() -> new UnitPrice(new BigDecimal("9999999999999.999999")));
        for (String invalid : new String[]{"-1", "0.0000001", "10000000000000", "1E+2147483647"}) {
            assertThrows(IllegalArgumentException.class, () -> new UnitPrice(new BigDecimal(invalid)));
        }
    }

    /** 分批履约生成新的不可变快照，不修改固定属性或旧快照。 */
    @Test
    void fulfillsInPartsWithoutChangingFixedDetails() {
        var original = OrderLine.create(UUID.randomUUID(), quantity("1.25"), new UnitPrice(new BigDecimal("2.5")));
        var partial = original.fulfill(quantity("0.25"));
        var complete = partial.fulfill(quantity("1"));
        assertEquals(Quantity.ZERO, original.getFulfilled());
        assertEquals(quantity("1"), partial.getRemaining());
        assertEquals(Quantity.ZERO, complete.getRemaining());
        assertEquals(original.getId(), complete.getId());
        assertEquals(original.getMaterialId(), complete.getMaterialId());
        assertEquals(original.getOrdered(), complete.getOrdered());
        assertEquals(original.getUnitPrice(), complete.getUnitPrice());
    }

    /** 超量、零数量及非法持久化快照不能绕过履约约束。 */
    @Test
    void rejectsOverFulfillmentAndInvalidSnapshots() {
        UUID material = UUID.randomUUID(); var price = new UnitPrice(BigDecimal.ONE);
        var line = OrderLine.create(material, quantity("1"), price);
        assertEquals(CommonErrorCode.CONFLICT,
                assertThrows(StratumException.class, () -> line.fulfill(quantity("1.000000000001"))).getErrorCode());
        assertEquals(Quantity.ZERO, line.getFulfilled());
        assertThrows(IllegalArgumentException.class, () -> line.fulfill(Quantity.ZERO));
        assertThrows(IllegalArgumentException.class, () -> OrderLine.create(material, Quantity.ZERO, price));
        assertThrows(IllegalArgumentException.class,
                () -> OrderLine.restore(UUID.randomUUID(), material, quantity("1"), price, quantity("2")));
    }

    /**
     * 创建精确测试数量。
     * @param value 十进制文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
