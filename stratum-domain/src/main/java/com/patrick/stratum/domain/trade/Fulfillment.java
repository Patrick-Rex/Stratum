package com.patrick.stratum.domain.trade;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.model.Entity;

/** 实体：一次采购收货或销售出库的不可变凭据，关联订单保存后的版本及本次数量。 */
public final class Fulfillment extends Entity<UUID> {
    /**
     * 值对象：本批次某订单行的履约数量。
     * @param orderLineId 订单行标识
     * @param quantity 本次正数量
     */
    public record Line(UUID orderLineId, Quantity quantity) {
        /**
         * 校验完整来源与正数量。
         * @param orderLineId 订单行标识
         * @param quantity 正履约数量
         * @apiNote 示例：new Line(orderLineId, quantity)。
         */
        public Line {
            Objects.requireNonNull(orderLineId, "orderLineId");
            Objects.requireNonNull(quantity, "quantity").requirePositive();
        }
    }

    private final UUID orderId;
    private final TradeOrder.Kind kind;
    private final long orderVersion;
    private final List<Line> lines;
    private final Instant createdAt;

    /**
     * 校验履约凭据的版本、明细边界及不可变时间。
     * @param id 履约标识
     * @param orderId 订单标识
     * @param kind 采购或销售
     * @param orderVersion 本次履约保存后的订单版本
     * @param lines 本次履约明细
     * @param createdAt UTC微秒时间
     */
    private Fulfillment(UUID id, UUID orderId, TradeOrder.Kind kind, long orderVersion, List<Line> lines, Instant createdAt) {
        super(id);
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (orderVersion < 1) throw new IllegalArgumentException("履约必须关联更新后的订单版本");
        this.orderVersion = orderVersion;
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty() || lines.size() > TradeOrder.MAX_LINES) throw new IllegalArgumentException("履约必须包含1至100行");
        this.lines = List.copyOf(lines);
        var ids = new HashSet<UUID>();
        for (var line : this.lines) if (!ids.add(line.orderLineId())) throw new IllegalArgumentException("履约行不能重复");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!createdAt.equals(createdAt.truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException("履约时间必须为微秒精度");
    }

    /**
     * 从本次履约后已保存的订单和原始数量生成凭据，不再次修改订单。
     * @param order 本次履约后已保存订单
     * @param quantities 本次履约数量，必须来自同一次订单变更
     * @return 不可变履约凭据
     * @throws IllegalArgumentException 订单未保存、来源不符或数量超过已履约值
     * @apiNote 示例：订单fulfill及保存后调用Fulfillment.create(order, quantities)。
     */
    public static Fulfillment create(TradeOrder order, Map<UUID, Quantity> quantities) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(quantities, "quantities");
        if (order.getVersion() == null || order.getVersion() < 1) throw new IllegalArgumentException("履约需要更新后的订单版本");
        if (quantities.isEmpty() || quantities.size() > TradeOrder.MAX_LINES) throw new IllegalArgumentException("履约必须包含1至100行");
        var requested = Map.copyOf(quantities);
        var lines = new ArrayList<Line>(requested.size());
        for (var line : order.getLines()) {
            var quantity = requested.get(line.getId());
            if (quantity != null) {
                if (quantity.compareTo(line.getFulfilled()) > 0) throw new IllegalArgumentException("履约凭据数量超过已保存累计数量");
                lines.add(new Line(line.getId(), quantity));
            }
        }
        if (lines.size() != requested.size()) throw new IllegalArgumentException("履约行不属于此订单");
        return new Fulfillment(UUID.randomUUID(), order.getId(), order.getKind(), order.getVersion(),
                lines, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * 重建已保存履约凭据，来源关系由数据库外键保护。
     * @param id 履约标识
     * @param orderId 订单标识
     * @param kind 采购或销售
     * @param orderVersion 对应订单版本
     * @param lines 不重复的履约明细
     * @param createdAt UTC微秒时间
     * @return 不可变凭据
     * @apiNote 示例：Fulfillment.restore(id, orderId, kind, version, lines, time)。
     */
    public static Fulfillment restore(UUID id, UUID orderId, TradeOrder.Kind kind, long orderVersion,
            List<Line> lines, Instant createdAt) {
        return new Fulfillment(id, orderId, kind, orderVersion, lines, createdAt);
    }

    /**
     * 获取来源订单。
     * @return 订单UUID
     * @apiNote 示例：fulfillment.getOrderId()。
     */
    public UUID getOrderId() { return orderId; }
    /**
     * 获取订单类别。
     * @return 采购或销售
     * @apiNote 示例：fulfillment.getKind()。
     */
    public TradeOrder.Kind getKind() { return kind; }
    /**
     * 获取本次履约后的订单版本。
     * @return 正版本号
     * @apiNote 示例：fulfillment.getOrderVersion()。
     */
    public long getOrderVersion() { return orderVersion; }
    /**
     * 获取不可变履约明细。
     * @return 有界列表
     * @apiNote 示例：fulfillment.getLines()。
     */
    public List<Line> getLines() { return lines; }
    /**
     * 获取凭据创建时间。
     * @return UTC微秒时间
     * @apiNote 示例：fulfillment.getCreatedAt()。
     */
    public Instant getCreatedAt() { return createdAt; }
}
