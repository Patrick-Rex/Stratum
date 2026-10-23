package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.util.Objects;

import com.patrick.stratum.domain.event.DomainEvent;

/** 领域事件：订单保存成功后的类别、状态、金额和版本快照。 */
public final class OrderChanged extends DomainEvent {
    /** 值枚举：订单创建或分批履约已完成。 */
    public enum Change { CREATED, FULFILLED }
    private final Change change;
    private final TradeOrder.Kind kind;
    private final TradeOrder.Status status;
    private final BigDecimal totalAmount;
    private final long version;

    /**
     * 复制已保存的订单状态，后续履约不修改旧事件。
     * @param order 已保存订单
     * @param change 已完成变更
     * @throws IllegalArgumentException 订单尚未保存
     * @apiNote 示例：new OrderChanged(order, Change.CREATED)。
     */
    public OrderChanged(TradeOrder order, Change change) {
        Objects.requireNonNull(order, "order");
        if (order.getVersion() == null) throw new IllegalArgumentException("订单事件需要持久化版本");
        super(order.getId().toString());
        this.change = Objects.requireNonNull(change, "change");
        kind = order.getKind(); status = order.getStatus(); totalAmount = order.getTotalAmount(); version = order.getVersion();
    }

    /**
     * 获取变更动作。
     * @return 创建或履约
     * @apiNote 示例：event.getChange()。
     */
    public Change getChange() { return change; }
    /**
     * 获取订单类别。
     * @return 采购或销售
     * @apiNote 示例：event.getKind()。
     */
    public TradeOrder.Kind getKind() { return kind; }
    /**
     * 获取保存时状态。
     * @return 履约状态
     * @apiNote 示例：event.getStatus()。
     */
    public TradeOrder.Status getStatus() { return status; }
    /**
     * 获取人民币总额。
     * @return 两位小数金额
     * @apiNote 示例：event.getTotalAmount()。
     */
    public BigDecimal getTotalAmount() { return totalAmount; }
    /**
     * 获取订单版本。
     * @return 保存版本
     * @apiNote 示例：event.getVersion()。
     */
    public long getVersion() { return version; }
}
