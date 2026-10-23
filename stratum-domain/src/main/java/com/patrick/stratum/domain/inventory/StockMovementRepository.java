package com.patrick.stratum.domain.inventory;

/** 库存流水仓储端口，只允许追加；同履约行和同余额版本只能生成一条流水。 */
public interface StockMovementRepository {
    /**
     * 在当前工作单元内追加流水，重复履约行必须使整个事务失败。
     * @param movement 不可变流水
     * @apiNote 示例：repository.append(movement)。
     */
    void append(StockMovement movement);
}
