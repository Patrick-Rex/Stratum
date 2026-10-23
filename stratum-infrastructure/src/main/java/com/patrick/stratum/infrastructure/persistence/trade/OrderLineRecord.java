package com.patrick.stratum.infrastructure.persistence.trade;

import java.math.BigDecimal;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.UpdateIgnore;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.trade.proxy.OrderLineRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 订单持久化映射，固定字段不参与UPDATE，履约写入统一受订单主表版本保护。 */
@Getter
@Setter
@EntityProxy
@Table("erp_order_line")
public class OrderLineRecord implements ProxyEntityAvailable<OrderLineRecord, OrderLineRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    @UpdateIgnore
    private String orderId;
    @UpdateIgnore
    private Integer lineNumber;
    @UpdateIgnore
    private String materialId;
    @UpdateIgnore
    private String materialKind;
    @UpdateIgnore
    private BigDecimal ordered;
    @UpdateIgnore
    private BigDecimal unitPrice;
    private BigDecimal fulfilled;
}
