package com.patrick.stratum.application.inventory;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxEventWriter;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.inventory.StockChanged;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.inventory.StockMovementRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 库存内部编排：余额、履约流水及Outbox共用调用方事务，订单用例负责履约规则与请求重放。 */
@Component
@RequiredArgsConstructor
public class StockPostingApplicationService {
    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;
    private final MasterDataRepository masters;
    private final OutboxEventWriter events;

    /**
     * 对已由订单用例确认的单行履约执行库存过账，重复行冲突使整个工作单元回滚。
     * @param posting 内部输入，包含合法物料、仓库、来源、方向及正数量
     * @return 已追加的不可变流水
     * @throws StratumException 主数据不存在、类别不符、库存不足或并发冲突
     * @throws IllegalArgumentException 数量不合法
     * @apiNote 示例：订单工作单元中调用inventory.post(StockPosting.builder()...build())。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public StockMovement post(StockPosting posting) {
        Objects.requireNonNull(posting, "posting");
        Objects.requireNonNull(posting.getSource(), "source");
        Objects.requireNonNull(posting.getDirection(), "direction");
        Objects.requireNonNull(posting.getQuantity(), "quantity").requirePositive();
        requireMaster(posting.getMaterialId(), MasterData.Kind.MATERIAL);
        requireMaster(posting.getWarehouseId(), MasterData.Kind.WAREHOUSE);
        var balance = balances.find(posting.getMaterialId(), posting.getWarehouseId())
                .orElseGet(() -> StockBalance.open(posting.getMaterialId(), posting.getWarehouseId()));
        switch (posting.getDirection()) {
            case RECEIPT -> balance.receive(posting.getQuantity());
            case ISSUE -> balance.issue(posting.getQuantity());
        }
        balances.save(balance);
        var movement = StockMovement.create(balance, posting.getSource(), posting.getDirection(), posting.getQuantity());
        movements.append(movement);
        events.append(new StockChanged(balance), posting.getDirection() == StockMovement.Direction.RECEIPT
                ? "erp.stock.received" : "erp.stock.issued");
        return movement;
    }

    /**
     * 在同事务内核对主数据存在及固定类别，外键保护其后的删除竞争。
     * @param id 主数据标识
     * @param kind 期望类别
     */
    private void requireMaster(UUID id, MasterData.Kind kind) {
        Objects.requireNonNull(id, "masterId");
        masters.findById(id.toString()).orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND)).requireKind(kind);
    }
}
