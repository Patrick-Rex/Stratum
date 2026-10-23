package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.trade.proxy.TradeOrderViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 采购销售订单摘要投影，不关联加载订单明细或写聚合。 */
@Getter
@Setter
@EntityProxy
@Table("erp_trade_order")
public class TradeOrderView implements ProxyEntityAvailable<TradeOrderView, TradeOrderViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String kind;
    private String orderNumber;
    private String partyId;
    private String warehouseId;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime createdAt;
    private Long version;
}
