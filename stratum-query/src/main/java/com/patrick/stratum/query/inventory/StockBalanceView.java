package com.patrick.stratum.query.inventory;

import java.math.BigDecimal;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.query.inventory.proxy.StockBalanceViewProxy;
import lombok.Getter;
import lombok.Setter;

/** 库存余额只读ORM投影，不提供库存修改入口。 */
@Getter
@Setter
@EntityProxy
@Table("erp_stock_balance")
public class StockBalanceView implements ProxyEntityAvailable<StockBalanceView, StockBalanceViewProxy> {
    @Column(primaryKey = true)
    private String id;
    private String materialId;
    private String warehouseId;
    private BigDecimal quantity;
    private Long version;
}
