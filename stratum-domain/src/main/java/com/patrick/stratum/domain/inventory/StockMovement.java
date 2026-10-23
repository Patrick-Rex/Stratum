package com.patrick.stratum.domain.inventory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.domain.model.Entity;

/** 实体：单次履约行产生的不可变库存流水，保存过账后的余额及其版本。 */
public final class StockMovement extends Entity<UUID> {
    /** 值枚举：采购收货入库或销售履约出库。 */
    public enum Direction { RECEIPT, ISSUE }

    /**
     * 值对象：关联订单、一次履约以及订单行；同一订单行可分批履约。
     * @param orderId 订单标识
     * @param postingId 本次履约标识
     * @param orderLineId 订单行标识
     */
    public record Source(UUID orderId, UUID postingId, UUID orderLineId) {
        /**
         * 拒绝缺失的业务来源，关系有效性由订单用例及数据库保证。
         * @param orderId 订单标识
         * @param postingId 履约标识
         * @param orderLineId 订单行标识
         * @apiNote 示例：new Source(orderId, postingId, lineId)。
         */
        public Source {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(postingId, "postingId");
            Objects.requireNonNull(orderLineId, "orderLineId");
        }
    }

    private final UUID balanceId;
    private final Source source;
    private final Direction direction;
    private final Quantity quantity;
    private final Quantity balanceAfter;
    private final long balanceVersion;
    private final Instant occurredAt;

    /**
     * 校验流水快照及能够还原的变更前余额。
     * @param id 流水标识
     * @param balanceId 余额标识
     * @param source 履约来源
     * @param direction 入库或出库
     * @param quantity 正变动数量
     * @param balanceAfter 变动后余额
     * @param balanceVersion 余额版本
     * @param occurredAt 微秒精度发生时间
     */
    private StockMovement(UUID id, UUID balanceId, Source source, Direction direction,
            Quantity quantity, Quantity balanceAfter, long balanceVersion, Instant occurredAt) {
        super(id);
        this.balanceId = Objects.requireNonNull(balanceId, "balanceId");
        this.source = Objects.requireNonNull(source, "source");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.quantity = Objects.requireNonNull(quantity, "quantity").requirePositive();
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
        if (balanceVersion < 0) throw new IllegalArgumentException("库存版本不能为负数");
        if (direction == Direction.RECEIPT) balanceAfter.subtract(quantity);
        else balanceAfter.add(quantity);
        this.balanceVersion = balanceVersion;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (!occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("库存流水时间必须为微秒精度");
        }
    }

    /**
     * 从已保存余额创建本次流水，随后余额变化不会改变快照。
     * @param balance 本次过账后已保存的余额
     * @param source 履约来源
     * @param direction 变动方向
     * @param quantity 正变动数量
     * @return 新流水
     * @throws IllegalArgumentException 尚未保存余额或快照不合法
     * @apiNote 示例：StockMovement.create(balance, source, Direction.RECEIPT, quantity)。
     */
    public static StockMovement create(StockBalance balance, Source source, Direction direction, Quantity quantity) {
        Objects.requireNonNull(balance, "balance");
        if (balance.getVersion() == null) throw new IllegalArgumentException("库存流水需要持久化余额版本");
        return new StockMovement(UUID.randomUUID(), balance.getId(), source, direction, quantity,
                balance.getQuantity(), balance.getVersion(), Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * 重建数据库中的不可变流水。
     * @param id 流水标识
     * @param balanceId 余额标识
     * @param source 履约来源
     * @param direction 变动方向
     * @param quantity 正变动数量
     * @param balanceAfter 变动后余额
     * @param balanceVersion 余额版本
     * @param occurredAt 微秒精度发生时间
     * @return 流水快照
     * @apiNote 示例：StockMovement.restore(id, balanceId, source, direction, quantity, after, version, time)。
     */
    public static StockMovement restore(UUID id, UUID balanceId, Source source, Direction direction,
            Quantity quantity, Quantity balanceAfter, long balanceVersion, Instant occurredAt) {
        return new StockMovement(id, balanceId, source, direction, quantity, balanceAfter, balanceVersion, occurredAt);
    }

    /**
     * 获取关联余额。
     * @return 余额标识
     * @apiNote 示例：movement.getBalanceId()。
     */
    public UUID getBalanceId() { return balanceId; }
    /**
     * 获取业务来源。
     * @return 不可变来源
     * @apiNote 示例：movement.getSource()。
     */
    public Source getSource() { return source; }
    /**
     * 获取变动方向。
     * @return 入库或出库
     * @apiNote 示例：movement.getDirection()。
     */
    public Direction getDirection() { return direction; }
    /**
     * 获取正变动数量。
     * @return 数量
     * @apiNote 示例：movement.getQuantity()。
     */
    public Quantity getQuantity() { return quantity; }
    /**
     * 获取变动后余额。
     * @return 不可变余额数量
     * @apiNote 示例：movement.getBalanceAfter()。
     */
    public Quantity getBalanceAfter() { return balanceAfter; }
    /**
     * 获取变动对应版本。
     * @return 非负余额版本
     * @apiNote 示例：movement.getBalanceVersion()。
     */
    public long getBalanceVersion() { return balanceVersion; }
    /**
     * 获取流水发生时间。
     * @return UTC时间点
     * @apiNote 示例：movement.getOccurredAt()。
     */
    public Instant getOccurredAt() { return occurredAt; }
}
