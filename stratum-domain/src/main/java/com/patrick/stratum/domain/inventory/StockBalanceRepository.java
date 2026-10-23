package com.patrick.stratum.domain.inventory;

import java.util.Optional;
import java.util.UUID;

/** 库存余额仓储端口：按物料仓库组合唯一保存，写入必须校验乐观锁版本。 */
public interface StockBalanceRepository {
    /**
     * 在写用例中读取指定物料仓库余额。
     * @param materialId 物料标识
     * @param warehouseId 仓库标识
     * @return 余额聚合，不存在时为空
     * @apiNote 示例：repository.find(materialId, warehouseId)。
     */
    Optional<StockBalance> find(UUID materialId, UUID warehouseId);

    /**
     * 在已有工作单元中保存余额；重复维度及版本冲突必须整体回滚。
     * @param balance 库存聚合
     * @throws com.patrick.stratum.common.error.StratumException 重复维度或版本冲突
     * @apiNote 示例：工作单元中调用repository.save(balance)。
     */
    void save(StockBalance balance);
}
