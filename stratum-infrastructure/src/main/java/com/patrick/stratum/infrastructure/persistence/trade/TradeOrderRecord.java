package com.patrick.stratum.infrastructure.persistence.trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.UpdateIgnore;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.trade.proxy.TradeOrderRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 订单持久化映射，固定字段不参与UPDATE，履约写入统一受订单主表版本保护。 */
@Getter
@Setter
@EntityProxy
@Table("erp_trade_order")
public class TradeOrderRecord implements ProxyEntityAvailable<TradeOrderRecord, TradeOrderRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    @UpdateIgnore
    private String kind;
    @UpdateIgnore
    private String orderNumber;
    @UpdateIgnore
    private String partyId;
    @UpdateIgnore
    private String partyKind;
    @UpdateIgnore
    private String warehouseId;
    @UpdateIgnore
    private String warehouseKind;
    @UpdateIgnore
    private BigDecimal totalAmount;
    private String status;
    @UpdateIgnore
    private LocalDateTime createdAt;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
