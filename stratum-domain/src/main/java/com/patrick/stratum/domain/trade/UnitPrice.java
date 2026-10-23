package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.patrick.stratum.domain.inventory.Quantity;

/**
 * 值对象：人民币非负单价，精确对应DECIMAL(19,6)，金额舍入由订单汇总时统一执行。
 * @param value 单价
 */
public record UnitPrice(BigDecimal value) {
    /**
     * 拒绝无法精确保存的单价，不静默截断小数。
     * @param value 非负人民币单价
     * @throws IllegalArgumentException 超过13位整数或6位小数
     * @apiNote 示例：new UnitPrice(new BigDecimal("12.3456"))。
     */
    public UnitPrice {
        Objects.requireNonNull(value, "value");
        var normalized = value.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.scale() > 6
                || (long) normalized.precision() - normalized.scale() > 13) {
            throw new IllegalArgumentException("单价必须为非负数，最多13位整数和6位小数");
        }
        value = normalized.setScale(6, RoundingMode.UNNECESSARY);
    }

    /**
     * 精确计算数量乘单价，不在订单汇总前舍入。
     * @param quantity 计价数量
     * @return 未舍入的人民币金额
     * @apiNote 示例：price.multiply(quantity)。
     */
    public BigDecimal multiply(Quantity quantity) { return value.multiply(quantity.value()); }
}
