package com.patrick.stratum.infrastructure.persistence.inventory;

import java.math.BigDecimal;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.UpdateIgnore;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.patrick.stratum.infrastructure.persistence.inventory.proxy.StockBalanceRecordProxy;
import lombok.Getter;
import lombok.Setter;

/** 库存余额持久化映射，物料仓库维度固定，数量更新由乐观锁保护。 */
@Getter
@Setter
@EntityProxy
@Table("erp_stock_balance")
public class StockBalanceRecord implements ProxyEntityAvailable<StockBalanceRecord, StockBalanceRecordProxy> {
    @Column(primaryKey = true)
    private String id;
    @UpdateIgnore
    private String materialId;
    @UpdateIgnore
    private String materialKind;
    @UpdateIgnore
    private String warehouseId;
    @UpdateIgnore
    private String warehouseKind;
    private BigDecimal quantity;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
}
