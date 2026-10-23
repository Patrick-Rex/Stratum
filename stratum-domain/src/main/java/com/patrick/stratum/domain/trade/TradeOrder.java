package com.patrick.stratum.domain.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根：采购或销售订单，固定业务明细，统一维护分批履约及金额。 */
public final class TradeOrder extends AggregateRoot<UUID> {
    /** 值枚举：采购引用供应商，销售引用客户。 */
    public enum Kind { PURCHASE, SALE }
    /** 值枚举：由各行累计履约数量派生的订单进度。 */
    public enum Status { PENDING, PARTIAL, COMPLETED }
    public static final int MAX_LINES = 100;
    private final Kind kind;
    private final UUID partyId;
    private final UUID warehouseId;
    private final Instant createdAt;
    private final BigDecimal totalAmount;
    private List<OrderLine> lines;

    /**
     * 校验订单不变量，并按未舍入行金额汇总人民币总额。
     * @param id 订单标识
     * @param kind 采购或销售
     * @param partyId 供应商或客户标识
     * @param warehouseId 固定仓库
     * @param lines 固定明细快照
     * @param createdAt 创建时间
     * @param version 持久化版本，新增为空
     */
    private TradeOrder(UUID id, Kind kind, UUID partyId, UUID warehouseId, List<OrderLine> lines,
            Instant createdAt, Long version) {
        super(id);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.partyId = Objects.requireNonNull(partyId, "partyId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty() || lines.size() > MAX_LINES) throw new IllegalArgumentException("订单必须包含1至100行");
        this.lines = List.copyOf(lines);
        var ids = new HashSet<UUID>();
        BigDecimal amount = BigDecimal.ZERO;
        for (var line : this.lines) {
            if (!ids.add(line.getId())) throw new IllegalArgumentException("订单行标识重复");
            amount = amount.add(line.getExactAmount());
        }
        totalAmount = amount.setScale(2, RoundingMode.HALF_UP);
        if (totalAmount.precision() - totalAmount.scale() > 36) throw new IllegalArgumentException("订单金额超过存储范围");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (!createdAt.equals(createdAt.truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException("订单时间必须为微秒精度");
        if (version != null && version < 0) throw new IllegalArgumentException("订单版本不能为负数");
        setVersion(version);
    }

    /**
     * 创建未履约订单，标识和单号由领域生成。
     * @param kind 采购或销售
     * @param partyId 供应商或客户标识，类别由应用及外键验证
     * @param warehouseId 仓库标识
     * @param lines 尚未履约的订单行
     * @return 新订单
     * @apiNote 示例：TradeOrder.create(Kind.PURCHASE, supplierId, warehouseId, lines)。
     */
    public static TradeOrder create(Kind kind, UUID partyId, UUID warehouseId, List<OrderLine> lines) {
        var order = new TradeOrder(UUID.randomUUID(), kind, partyId, warehouseId, lines,
                Instant.now().truncatedTo(ChronoUnit.MICROS), null);
        if (order.getStatus() != Status.PENDING) throw new IllegalArgumentException("新订单不能已有履约数量");
        return order;
    }

    /**
     * 重建已保存订单，由明细重新计算状态与金额。
     * @param id 订单标识
     * @param kind 订单类别
     * @param partyId 交易方标识
     * @param warehouseId 仓库标识
     * @param lines 持久化行快照
     * @param createdAt 创建时间
     * @param version 持久化版本
     * @return 订单聚合
     * @apiNote 示例：TradeOrder.restore(id, kind, party, warehouse, lines, time, version)。
     */
    public static TradeOrder restore(UUID id, Kind kind, UUID partyId, UUID warehouseId,
            List<OrderLine> lines, Instant createdAt, long version) {
        return new TradeOrder(id, kind, partyId, warehouseId, lines, createdAt, version);
    }

    /**
     * 原子校验一批订单行的履约数量；任一行失败时不改变任何行快照。
     * @param quantities 按订单行UUID索引的本次正履约数量
     * @throws StratumException 行不属于此订单或数量超过剩余值
     * @throws IllegalArgumentException 空批次、数量非法或超过100行
     * @apiNote 示例：order.fulfill(Map.of(lineId, quantity))。
     */
    public void fulfill(Map<UUID, Quantity> quantities) {
        Objects.requireNonNull(quantities, "quantities");
        if (quantities.isEmpty() || quantities.size() > MAX_LINES) throw new IllegalArgumentException("履约必须包含1至100行");
        var requested = Map.copyOf(quantities);
        var known = new HashSet<UUID>();
        for (var line : lines) known.add(line.getId());
        if (!known.containsAll(requested.keySet())) throw new StratumException(CommonErrorCode.CONFLICT, "履约行不属于该订单");
        var updated = new ArrayList<OrderLine>(lines.size());
        for (var line : lines) {
            var quantity = requested.get(line.getId());
            updated.add(quantity == null ? line : line.fulfill(quantity));
        }
        lines = List.copyOf(updated);
    }

    /**
     * 防止客户端覆盖未观察到的订单状态。
     * @param expectedVersion 调用方观察版本
     * @throws StratumException 版本不一致或尚未保存
     * @apiNote 示例：order.requireVersion(command.getVersion())。
     */
    public void requireVersion(long expectedVersion) {
        if (getVersion() == null || getVersion() != expectedVersion) throw new StratumException(CommonErrorCode.CONFLICT, "订单版本已变更");
    }

    /**
     * 保存成功后由仓储顺序推进版本。
     * @param version 新增为0，其余为当前版本加1
     * @throws IllegalArgumentException 版本不连续
     * @apiNote 示例：order.markPersisted(0)。
     */
    public void markPersisted(long version) {
        long next = getVersion() == null ? 0 : Math.incrementExact(getVersion());
        if (version != next) throw new IllegalArgumentException("订单版本必须按顺序推进");
        setVersion(version);
    }

    /**
     * 获取订单类别。
     * @return 采购或销售
     * @apiNote 示例：order.getKind()。
     */
    public Kind getKind() { return kind; }
    /**
     * 获取供应商或客户标识。
     * @return 交易方UUID
     * @apiNote 示例：order.getPartyId()。
     */
    public UUID getPartyId() { return partyId; }
    /**
     * 获取固定履约仓库。
     * @return 仓库UUID
     * @apiNote 示例：order.getWarehouseId()。
     */
    public UUID getWarehouseId() { return warehouseId; }
    /**
     * 获取不可变行快照列表。
     * @return 当前全部明细
     * @apiNote 示例：order.getLines()。
     */
    public List<OrderLine> getLines() { return lines; }
    /**
     * 获取人民币总金额，精确乘积汇总后四舍五入到分。
     * @return 两位小数金额
     * @apiNote 示例：order.getTotalAmount()。
     */
    public BigDecimal getTotalAmount() { return totalAmount; }
    /**
     * 获取领域自动单号，不表示连续业务序列。
     * @return PO或SO前缀加订单UUID
     * @apiNote 示例：order.getOrderNumber()。
     */
    public String getOrderNumber() { return (kind == Kind.PURCHASE ? "PO-" : "SO-") + id; }
    /**
     * 获取固定创建时间。
     * @return UTC微秒时间
     * @apiNote 示例：order.getCreatedAt()。
     */
    public Instant getCreatedAt() { return createdAt; }
    /**
     * 根据各行累计数量计算履约状态。
     * @return 待履约、部分履约或已完成
     * @apiNote 示例：order.getStatus()。
     */
    public Status getStatus() {
        if (lines.stream().allMatch(line -> line.getRemaining().equals(Quantity.ZERO))) return Status.COMPLETED;
        if (lines.stream().allMatch(line -> line.getFulfilled().equals(Quantity.ZERO))) return Status.PENDING;
        return Status.PARTIAL;
    }
}
