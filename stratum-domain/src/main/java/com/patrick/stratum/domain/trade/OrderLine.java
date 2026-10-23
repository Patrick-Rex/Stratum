package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.model.Entity;

/** 实体：订单内不可变行快照，物料、订购数量和单价固定，履约时生成相同标识的新快照。 */
public final class OrderLine extends Entity<UUID> {
    private final UUID materialId;
    private final Quantity ordered;
    private final UnitPrice unitPrice;
    private final Quantity fulfilled;

    /**
     * 校验固定明细及累计履约数量。
     * @param id 行标识
     * @param materialId 物料标识
     * @param ordered 正订购数量
     * @param unitPrice 非负单价
     * @param fulfilled 累计履约数量
     */
    private OrderLine(UUID id, UUID materialId, Quantity ordered, UnitPrice unitPrice, Quantity fulfilled) {
        super(id);
        this.materialId = Objects.requireNonNull(materialId, "materialId");
        this.ordered = Objects.requireNonNull(ordered, "ordered").requirePositive();
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        this.fulfilled = Objects.requireNonNull(fulfilled, "fulfilled");
        if (fulfilled.compareTo(ordered) > 0) throw new IllegalArgumentException("累计履约不能超过订单数量");
    }

    /**
     * 创建尚未履约的固定订单行，由领域生成UUID。
     * @param materialId 物料标识
     * @param ordered 正订购数量
     * @param unitPrice 人民币单价
     * @return 新订单行
     * @apiNote 示例：OrderLine.create(materialId, quantity, price)。
     */
    public static OrderLine create(UUID materialId, Quantity ordered, UnitPrice unitPrice) {
        return new OrderLine(UUID.randomUUID(), materialId, ordered, unitPrice, Quantity.ZERO);
    }

    /**
     * 从持久化结果重建订单行快照。
     * @param id 行标识
     * @param materialId 物料标识
     * @param ordered 订购数量
     * @param unitPrice 人民币单价
     * @param fulfilled 累计履约数量
     * @return 不可变订单行
     * @apiNote 示例：OrderLine.restore(id, material, ordered, price, fulfilled)。
     */
    public static OrderLine restore(UUID id, UUID materialId, Quantity ordered, UnitPrice unitPrice, Quantity fulfilled) {
        return new OrderLine(id, materialId, ordered, unitPrice, fulfilled);
    }

    /**
     * 为聚合生成新的履约快照，失败不改变原行。
     * @param quantity 本次正履约数量
     * @return 新快照，保留标识和固定明细
     * @throws StratumException 超过剩余数量
     */
    OrderLine fulfill(Quantity quantity) {
        quantity.requirePositive();
        if (quantity.compareTo(getRemaining()) > 0) throw new StratumException(CommonErrorCode.CONFLICT, "履约数量超过订单剩余数量");
        return new OrderLine(id, materialId, ordered, unitPrice, fulfilled.add(quantity));
    }

    /**
     * 获取物料标识。
     * @return 物料UUID
     * @apiNote 示例：line.getMaterialId()。
     */
    public UUID getMaterialId() { return materialId; }
    /**
     * 获取固定订购数量。
     * @return 正数量
     * @apiNote 示例：line.getOrdered()。
     */
    public Quantity getOrdered() { return ordered; }
    /**
     * 获取固定单价。
     * @return 人民币单价
     * @apiNote 示例：line.getUnitPrice()。
     */
    public UnitPrice getUnitPrice() { return unitPrice; }
    /**
     * 获取累计履约数量。
     * @return 已收货或已出库数量
     * @apiNote 示例：line.getFulfilled()。
     */
    public Quantity getFulfilled() { return fulfilled; }
    /**
     * 获取可继续履约的数量。
     * @return 剩余数量
     * @apiNote 示例：line.getRemaining()。
     */
    public Quantity getRemaining() { return ordered.subtract(fulfilled); }
    /**
     * 获取未舍入的行金额，供订单统一汇总。
     * @return 精确乘积
     * @apiNote 示例：line.getExactAmount()。
     */
    public BigDecimal getExactAmount() { return unitPrice.multiply(ordered); }
}
