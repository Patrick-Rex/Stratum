package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.inventory.proxy.StockMovementViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 库存流水只读投影，保留来源、历史余额及UTC微秒时间。 */
@Getter
@Setter
@EntityProxy
@Table("erp_stock_movement")
public class StockMovementView implements ProxyEntityAvailable<StockMovementView, StockMovementViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String balanceId;
    private String orderId;
    private String postingId;
    private String orderLineId;
    private String direction;
    private BigDecimal quantity;
    private BigDecimal balanceAfter;
    private Long balanceVersion;
    private LocalDateTime occurredAt;
}
