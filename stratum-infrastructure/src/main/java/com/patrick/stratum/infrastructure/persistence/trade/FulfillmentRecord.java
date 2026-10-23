package com.patrick.stratum.infrastructure.persistence.trade;

import java.time.LocalDateTime;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.trade.proxy.FulfillmentRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 履约凭据持久化映射，仅由追加仓储保存，保留订单及明细来源。 */
@Getter
@Setter
@EntityProxy
@Table("erp_fulfillment")
public class FulfillmentRecord implements ProxyEntityAvailable<FulfillmentRecord, FulfillmentRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    private String orderId;
    private String kind;
    private Long orderVersion;
    private LocalDateTime createdAt;
}
