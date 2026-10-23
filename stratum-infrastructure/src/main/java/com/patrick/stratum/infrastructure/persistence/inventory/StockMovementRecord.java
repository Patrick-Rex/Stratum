package com.patrick.stratum.infrastructure.persistence.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.inventory.proxy.StockMovementRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 库存流水映射，仅由追加仓储插入，时间统一采用UTC微秒。 */
@Getter
@Setter
@EntityProxy
@Table("erp_stock_movement")
public class StockMovementRecord implements ProxyEntityAvailable<StockMovementRecord, StockMovementRecordProxy> {
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
