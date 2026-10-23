package com.patrick.stratum.application.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.command.Command;
import com.patrick.stratum.domain.trade.TradeOrder;
import lombok.Builder;
import lombok.Getter;

/** 采购收货或销售出库命令，携带期望版本及本次有界履约量。 */
@Getter
public final class FulfillOrderCommand implements Command<FulfillmentResult> {
    /** 单个订单行的本次正履约量，不接受物料或仓库重定义。 */
    @Getter
    @Builder
    public static final class Line {
        private final UUID orderLineId;
        private final BigDecimal quantity;
    }
    private final String subjectId;
    private final String idempotencyKey;
    private final TradeOrder.Kind kind;
    private final UUID orderId;
    private final long version;
    private final List<Line> lines;

    /**
     * 复制批次输入，拒绝空批次和超出有界处理能力的明细。
     * @param subjectId 服务器主体
     * @param idempotencyKey 客户端幂等键
     * @param kind 采购或销售
     * @param orderId 来源订单
     * @param version 观察到的订单版本
     * @param lines 本次履约明细
     * @apiNote 示例：FulfillOrderCommand.builder().orderId(id).version(version)...build()。
     */
    @Builder
    public FulfillOrderCommand(String subjectId, String idempotencyKey, TradeOrder.Kind kind, UUID orderId,
            long version, List<Line> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > TradeOrder.MAX_LINES) throw new IllegalArgumentException("履约必须包含1至100行");
        if (version < 0) throw new IllegalArgumentException("订单版本不能为负数");
        this.subjectId = subjectId; this.idempotencyKey = idempotencyKey; this.kind = kind;
        this.orderId = orderId; this.version = version; this.lines = List.copyOf(lines);
    }
}
