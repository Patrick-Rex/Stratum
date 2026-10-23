package com.patrick.stratum.application.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.patrick.stratum.domain.trade.TradeOrder;
import lombok.Builder;
import lombok.Getter;

/** 订单用例结果快照，不携带请求traceId，明细显式复制。 */
@Getter
public final class OrderResult {
    private final UUID id;
    private final String orderNumber;
    private final TradeOrder.Kind kind;
    private final UUID partyId;
    private final UUID warehouseId;
    private final TradeOrder.Status status;
    private final BigDecimal totalAmount;
    private final long version;
    private final List<Line> lines;

    /**
     * 构造不可变结果快照。
     * @param id 订单UUID
     * @param orderNumber 自动单号
     * @param kind 订单类别
     * @param partyId 供应商或客户UUID
     * @param warehouseId 仓库UUID
     * @param status 履约状态
     * @param totalAmount 人民币总额
     * @param version 保存版本
     * @param lines 不可变明细快照
     * @apiNote 示例：OrderResult.builder()...build()。
     */
    @Builder
    @JsonCreator
    public OrderResult(@JsonProperty("id") UUID id,
            @JsonProperty("orderNumber") String orderNumber,
            @JsonProperty("kind") TradeOrder.Kind kind,
            @JsonProperty("partyId") UUID partyId,
            @JsonProperty("warehouseId") UUID warehouseId,
            @JsonProperty("status") TradeOrder.Status status,
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("version") long version,
            @JsonProperty("lines") List<Line> lines) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.kind = kind;
        this.partyId = partyId;
        this.warehouseId = warehouseId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.version = version;
        this.lines = List.copyOf(lines);
    }

    /** 订单行结果快照，支持幂等反序列化。 */
    @Getter
    public static final class Line {
        private final UUID id;
        private final UUID materialId;
        private final BigDecimal quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal fulfilled;

        /**
         * 构造不可变结果快照。
         * @param id 订单行UUID
         * @param materialId 物料UUID
         * @param quantity 固定订购数量
         * @param unitPrice 固定人民币单价
         * @param fulfilled 累计履约数量
         * @apiNote 示例：Line.builder()...build()。
         */
        @Builder
        @JsonCreator
        public Line(@JsonProperty("id") UUID id,
                @JsonProperty("materialId") UUID materialId,
                @JsonProperty("quantity") BigDecimal quantity,
                @JsonProperty("unitPrice") BigDecimal unitPrice,
                @JsonProperty("fulfilled") BigDecimal fulfilled) {
            this.id = id;
            this.materialId = materialId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.fulfilled = fulfilled;
        }
    }

    /**
     * 从已保存订单复制结果，领域数量对象不会穿透接口层。
     * @param order 已保存订单
     * @return 固定结果快照
     * @apiNote 示例：OrderResult.from(order)。
     */
    public static OrderResult from(TradeOrder order) {
        return new OrderResult(order.getId(), order.getOrderNumber(), order.getKind(), order.getPartyId(),
                order.getWarehouseId(), order.getStatus(), order.getTotalAmount(), order.getVersion(),
                order.getLines().stream().map(line -> new Line(line.getId(), line.getMaterialId(),
                        line.getOrdered().value(), line.getUnitPrice().value(), line.getFulfilled().value())).toList());
    }
}
