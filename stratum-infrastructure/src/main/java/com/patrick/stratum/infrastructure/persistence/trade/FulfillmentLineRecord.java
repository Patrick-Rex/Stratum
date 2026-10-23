package com.patrick.stratum.infrastructure.persistence.trade;

import java.math.BigDecimal;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.trade.proxy.FulfillmentLineRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 履约凭据持久化映射，仅由追加仓储保存，保留订单及明细来源。 */
@Getter
@Setter
@EntityProxy
@Table("erp_fulfillment_line")
public class FulfillmentLineRecord implements ProxyEntityAvailable<FulfillmentLineRecord, FulfillmentLineRecordProxy> {
    @Column(primaryKey = true)
    private String postingId;
    @Column(primaryKey = true)
    private String orderLineId;
    private String orderId;
    private BigDecimal quantity;
}
