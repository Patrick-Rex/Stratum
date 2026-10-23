package com.patrick.stratum.domain.trade;

import java.util.Optional;
import java.util.UUID;

/** 采购销售订单仓储端口，主表和明细必须在同一工作单元内保存。 */
public interface TradeOrderRepository {
    /**
     * 加载写用例所需订单及全部有界明细。
     * @param id 订单标识
     * @return 订单快照，不存在为空
     * @apiNote 示例：repository.findById(id)。
     */
    Optional<TradeOrder> findById(UUID id);

    /**
     * 保存订单，更新仅推进履约状态与版本，不改动固定明细。
     * @param order 订单聚合
     * @throws com.patrick.stratum.common.error.StratumException 引用、唯一或版本冲突
     * @apiNote 示例：repository.save(order)。
     */
    void save(TradeOrder order);
}
