package com.patrick.stratum.application.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.command.Command;
import com.patrick.stratum.domain.trade.TradeOrder;
import lombok.Builder;
import lombok.Getter;

/** 采购销售创建命令，主体由服务器构造，明细顺序参与请求幂等指纹。 */
@Getter
public final class CreateOrderCommand implements Command<OrderResult> {
    /** 新订单行输入，数量和单价由领域值对象校验。 */
    @Getter
    @Builder
    public static final class Line {
        private final UUID materialId;
        private final BigDecimal quantity;
        private final BigDecimal unitPrice;
    }

    private final String subjectId;
    private final String idempotencyKey;
    private final TradeOrder.Kind kind;
    private final UUID partyId;
    private final UUID warehouseId;
    private final List<Line> lines;

    /**
     * 保存命令输入并复制明细集合，避免调用期间被外部修改。
     * @param subjectId 服务器主体标识
     * @param idempotencyKey 客户端幂等键
     * @param kind 采购或销售
     * @param partyId 供应商或客户
     * @param warehouseId 固定仓库
     * @param lines 有界固定明细
     * @apiNote 示例：CreateOrderCommand.builder().subjectId(subject).idempotencyKey(key)...build()。
     */
    @Builder
    public CreateOrderCommand(String subjectId, String idempotencyKey, TradeOrder.Kind kind, UUID partyId,
            UUID warehouseId, List<Line> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > TradeOrder.MAX_LINES) throw new IllegalArgumentException("订单必须包含1至100行");
        this.subjectId = subjectId; this.idempotencyKey = idempotencyKey; this.kind = kind;
        this.partyId = partyId; this.warehouseId = warehouseId; this.lines = List.copyOf(lines);
    }
}
