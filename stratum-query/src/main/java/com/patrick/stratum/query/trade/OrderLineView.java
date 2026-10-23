package com.patrick.stratum.query.trade;

import java.math.BigDecimal;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.trade.proxy.OrderLineViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 订单行只读投影，用于单条订单详情的有界关联查询。 */
@Getter
@Setter
@EntityProxy
@Table("erp_order_line")
public class OrderLineView implements ProxyEntityAvailable<OrderLineView, OrderLineViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String orderId;
    private Integer lineNumber;
    private String materialId;
    private BigDecimal ordered;
    private BigDecimal unitPrice;
    private BigDecimal fulfilled;
}
