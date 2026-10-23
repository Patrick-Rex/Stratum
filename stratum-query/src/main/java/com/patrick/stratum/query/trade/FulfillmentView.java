package com.patrick.stratum.query.trade;

import java.time.LocalDateTime;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.trade.proxy.FulfillmentViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 不可修改的履约凭据只读投影。 */
@Getter
@Setter
@EntityProxy
@Table("erp_fulfillment")
public class FulfillmentView implements ProxyEntityAvailable<FulfillmentView, FulfillmentViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String orderId;
    private String kind;
    private Long orderVersion;
    private LocalDateTime createdAt;
}
