package com.patrick.stratum.domain.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 值对象：不舍入的非负数量，对齐DECIMAL(38,12)，统一小数位使数值相等性稳定。
 * @param value 数量值
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {
    public static final Quantity ZERO = new Quantity(BigDecimal.ZERO);
    private static final int SCALE = 12;
    private static final int INTEGER_DIGITS = 26;

    /**
     * 拒绝负值、超大整数与无法无损保存的小数，额外末尾零允许归一化。
     * @param value 非负精确数量
     * @throws IllegalArgumentException 超出DECIMAL(38,12)边界
     * @throws NullPointerException 数量为空
     * @apiNote 示例：new Quantity(new BigDecimal("1.250"))。
     */
    public Quantity {
        Objects.requireNonNull(value, "value");
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.scale() > SCALE
                || (long) normalized.precision() - normalized.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("数量必须为非负数，最多26位整数和12位小数");
        }
        value = normalized.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * 校验入库、出库或订单行数量严格大于零。
     * @return 当前值对象
     * @throws IllegalArgumentException 数量为零
     * @apiNote 示例：quantity.requirePositive()。
     */
    public Quantity requirePositive() {
        if (value.signum() == 0) throw new IllegalArgumentException("操作数量必须大于零");
        return this;
    }

    /**
     * 精确相加并检查结果存储边界。
     * @param other 加数
     * @return 新数量
     * @throws IllegalArgumentException 结果超过存储边界
     * @apiNote 示例：balance.add(received)。
     */
    public Quantity add(Quantity other) { return new Quantity(value.add(other.value)); }

    /**
     * 精确相减并拒绝负结果。
     * @param other 减数
     * @return 新数量
     * @throws IllegalArgumentException 结果为负
     * @apiNote 示例：balance.subtract(issued)。
     */
    public Quantity subtract(Quantity other) { return new Quantity(value.subtract(other.value)); }

    /** {@inheritDoc} */
    @Override
    public int compareTo(Quantity other) { return value.compareTo(other.value); }
}
