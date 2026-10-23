package com.patrick.stratum.domain.inventory;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根：单个物料在一个仓库中的库存余额，任何出库均不得产生负库存。 */
public final class StockBalance extends AggregateRoot<UUID> {
    private final UUID materialId;
    private final UUID warehouseId;
    private Quantity quantity;

    /**
     * 校验库存维度和余额快照。
     * @param id 聚合标识
     * @param materialId 物料标识
     * @param warehouseId 仓库标识
     * @param quantity 非负余额
     * @param version 已持久化版本，新增为空
     */
    private StockBalance(UUID id, UUID materialId, UUID warehouseId, Quantity quantity, Long version) {
        super(id);
        this.materialId = Objects.requireNonNull(materialId, "materialId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        if (version != null && version < 0) throw new IllegalArgumentException("库存版本不能为负数");
        setVersion(version);
    }

    /**
     * 为首次发生业务的物料仓库组合创建零余额，由领域生成UUID。
     * @param materialId 物料标识
     * @param warehouseId 仓库标识
     * @return 尚未持久化的零余额
     * @apiNote 示例：StockBalance.open(materialId, warehouseId)。
     */
    public static StockBalance open(UUID materialId, UUID warehouseId) {
        return new StockBalance(UUID.randomUUID(), materialId, warehouseId, Quantity.ZERO, null);
    }

    /**
     * 重建已持久化的库存快照。
     * @param id 聚合标识
     * @param materialId 物料标识
     * @param warehouseId 仓库标识
     * @param quantity 非负余额
     * @param version 持久化版本
     * @return 库存聚合
     * @apiNote 示例：StockBalance.restore(id, material, warehouse, quantity, version)。
     */
    public static StockBalance restore(UUID id, UUID materialId, UUID warehouseId, Quantity quantity, long version) {
        return new StockBalance(id, materialId, warehouseId, quantity, version);
    }

    /**
     * 增加库存，拒绝零数量或超出存储范围的结果。
     * @param received 正入库数量
     * @throws IllegalArgumentException 数量非法或溢出
     * @apiNote 示例：balance.receive(quantity)。
     */
    public void receive(Quantity received) { quantity = quantity.add(received.requirePositive()); }

    /**
     * 扣减库存，检查失败时保留原余额。
     * @param issued 正出库数量
     * @throws StratumException 库存不足时返回冲突
     * @throws IllegalArgumentException 数量为零
     * @apiNote 示例：balance.issue(quantity)。
     */
    public void issue(Quantity issued) {
        issued.requirePositive();
        if (quantity.compareTo(issued) < 0) throw new StratumException(CommonErrorCode.CONFLICT, "库存不足");
        quantity = quantity.subtract(issued);
    }

    /**
     * 由仓储在成功写入后顺序推进版本。
     * @param version 新增为0，更新为当前版本加1
     * @throws IllegalArgumentException 版本不连续
     * @apiNote 示例：balance.markPersisted(0)。
     */
    public void markPersisted(long version) {
        long next = getVersion() == null ? 0 : Math.incrementExact(getVersion());
        if (next != version) throw new IllegalArgumentException("库存版本必须按顺序推进");
        setVersion(version);
    }

    /**
     * 获取固定物料维度。
     * @return 物料UUID
     * @apiNote 示例：balance.getMaterialId()。
     */
    public UUID getMaterialId() { return materialId; }
    /**
     * 获取固定仓库维度。
     * @return 仓库UUID
     * @apiNote 示例：balance.getWarehouseId()。
     */
    public UUID getWarehouseId() { return warehouseId; }
    /**
     * 获取不可变数量快照。
     * @return 当前非负余额
     * @apiNote 示例：balance.getQuantity()。
     */
    public Quantity getQuantity() { return quantity; }
}
