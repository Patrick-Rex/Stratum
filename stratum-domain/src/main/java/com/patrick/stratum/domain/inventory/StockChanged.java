package com.patrick.stratum.domain.inventory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.domain.event.DomainEvent;

/** 领域事件：已持久化库存余额的不可变快照，通过余额版本标识先后关系。 */
public final class StockChanged extends DomainEvent {
    private final UUID materialId;
    private final UUID warehouseId;
    private final BigDecimal quantity;
    private final long version;

    /**
     * 保存库存变更完成时的维度、数量与版本。
     * @param balance 已持久化的库存聚合
     * @throws IllegalArgumentException 余额尚未持久化
     * @apiNote 示例：new StockChanged(balance)。
     */
    public StockChanged(StockBalance balance) {
        Objects.requireNonNull(balance, "balance");
        if (balance.getVersion() == null) throw new IllegalArgumentException("库存事件需要持久化版本");
        super(balance.getId().toString());
        materialId = balance.getMaterialId();
        warehouseId = balance.getWarehouseId();
        quantity = balance.getQuantity().value();
        version = balance.getVersion();
    }

    /**
     * 获取物料标识。
     * @return 物料UUID
     * @apiNote 示例：event.getMaterialId()。
     */
    public UUID getMaterialId() { return materialId; }
    /**
     * 获取仓库标识。
     * @return 仓库UUID
     * @apiNote 示例：event.getWarehouseId()。
     */
    public UUID getWarehouseId() { return warehouseId; }
    /**
     * 获取变更后数量。
     * @return 精确数量快照
     * @apiNote 示例：event.getQuantity()。
     */
    public BigDecimal getQuantity() { return quantity; }
    /**
     * 获取余额版本。
     * @return 非负版本
     * @apiNote 示例：event.getVersion()。
     */
    public long getVersion() { return version; }
}
