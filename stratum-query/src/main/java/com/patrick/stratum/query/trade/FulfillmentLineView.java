package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.trade.proxy.FulfillmentLineViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 履约明细只读投影，用于按当前页凭据ID一次性批量加载。 */
@Getter
@Setter
@EntityProxy
@Table("erp_fulfillment_line")
public class FulfillmentLineView implements ProxyEntityAvailable<FulfillmentLineView, FulfillmentLineViewProxy> {
    @Column(primaryKey = true)
    private String postingId;
    @Column(primaryKey = true)
    private String orderLineId;
    private String orderId;
    private BigDecimal quantity;
}
