package com.patrick.stratum.application.inventory;

import java.util.UUID;

import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockMovement;
import lombok.Builder;
import lombok.Getter;

/** 内部库存过账输入，由采购销售用例验证履约后构造，不作为公共写命令或HTTP请求。 */
@Getter
@Builder
public final class StockPosting {
    private final UUID materialId;
    private final UUID warehouseId;
    private final StockMovement.Source source;
    private final StockMovement.Direction direction;
    private final Quantity quantity;
}
