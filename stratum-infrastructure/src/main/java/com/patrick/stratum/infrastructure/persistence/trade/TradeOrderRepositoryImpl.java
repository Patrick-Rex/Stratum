package com.patrick.stratum.infrastructure.persistence.trade;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryConcurrentException;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 订单仓储适配，先校验固定快照再竞争主表版本，仅批量更新发生变化的履约行。 */
@Repository
@RequiredArgsConstructor
public class TradeOrderRepositoryImpl implements TradeOrderRepository {
    private final EasyEntityQuery query;

    /** {@inheritDoc} */
    @Override
    public Optional<TradeOrder> findById(UUID id) {
        var row = query.queryable(TradeOrderRecord.class).where(order -> order.id().eq(id.toString())).singleOrNull();
        if (row == null) return Optional.empty();
        var lines = query.queryable(OrderLineRecord.class).where(line -> line.orderId().eq(id.toString()))
                .orderBy(line -> line.lineNumber().asc()).toList().stream().map(line -> OrderLine.restore(
                        UUID.fromString(line.getId()), UUID.fromString(line.getMaterialId()), new Quantity(line.getOrdered()),
                        new UnitPrice(line.getUnitPrice()), new Quantity(line.getFulfilled()))).toList();
        var order = TradeOrder.restore(id, TradeOrder.Kind.valueOf(row.getKind()), UUID.fromString(row.getPartyId()),
                UUID.fromString(row.getWarehouseId()), lines, row.getCreatedAt().toInstant(ZoneOffset.UTC), row.getVersion());
        if (!order.getOrderNumber().equals(row.getOrderNumber()) || !order.getStatus().name().equals(row.getStatus())
                || order.getTotalAmount().compareTo(row.getTotalAmount()) != 0) {
            throw new IllegalStateException("订单头与明细快照不一致");
        }
        return Optional.of(order);
    }

    /** {@inheritDoc} */
    @Override
    public void save(TradeOrder order) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("订单写入必须在Application工作单元中执行");
        }
        long next = order.getVersion() == null ? 0 : Math.incrementExact(order.getVersion());
        var row = map(order);
        try {
            if (order.getVersion() == null) {
                if (order.getStatus() != TradeOrder.Status.PENDING) throw new StratumException(CommonErrorCode.CONFLICT);
                row.setVersion(0L);
                requireRows(query.insertable(row).executeRows(), 1);
                var lines = mapLines(order);
                requireRows(query.insertable(lines).executeRows(), lines.size());
            } else {
                var current = findById(order.getId()).orElseThrow(() -> new StratumException(CommonErrorCode.CONFLICT));
                current.requireVersion(order.getVersion());
                var changed = changedLines(current, order);
                requireRows(query.updatable(row).executeRows(), 1);
                if (!changed.isEmpty()) requireRows(query.updatable(changed).executeRows(), changed.size());
            }
            order.markPersisted(next);
        } catch (EasyQueryException failure) {
            throw translate(failure);
        }
    }

    /**
     * 核对不可变内容及履约单向推进，返回有变化的有界明细批次。
     * @param current 数据库当前快照
     * @param desired 待保存快照
     * @return 仅履约量变化的行
     */
    private List<OrderLineRecord> changedLines(TradeOrder current, TradeOrder desired) {
        if (current.getKind() != desired.getKind() || !current.getPartyId().equals(desired.getPartyId())
                || !current.getWarehouseId().equals(desired.getWarehouseId())
                || !current.getCreatedAt().equals(desired.getCreatedAt())
                || current.getLines().size() != desired.getLines().size()) {
            throw new StratumException(CommonErrorCode.CONFLICT, "订单固定内容不能修改");
        }
        var changed = new ArrayList<OrderLineRecord>();
        for (int index = 0; index < current.getLines().size(); index++) {
            var before = current.getLines().get(index); var after = desired.getLines().get(index);
            if (!before.getId().equals(after.getId()) || !before.getMaterialId().equals(after.getMaterialId())
                    || !before.getOrdered().equals(after.getOrdered()) || !before.getUnitPrice().equals(after.getUnitPrice())
                    || before.getFulfilled().compareTo(after.getFulfilled()) > 0) {
                throw new StratumException(CommonErrorCode.CONFLICT, "订单固定明细不能修改，履约不能回退");
            }
            if (!before.getFulfilled().equals(after.getFulfilled())) changed.add(mapLine(desired.getId(), index + 1, after));
        }
        return changed;
    }

    /**
     * 显式映射订单头。
     * @param order 订单聚合
     * @return 独立ORM对象
     */
    private TradeOrderRecord map(TradeOrder order) {
        var row = new TradeOrderRecord();
        row.setId(order.getId().toString()); row.setKind(order.getKind().name()); row.setOrderNumber(order.getOrderNumber());
        row.setPartyId(order.getPartyId().toString()); row.setPartyKind(order.getKind() == TradeOrder.Kind.PURCHASE ? "SUPPLIER" : "CUSTOMER");
        row.setWarehouseId(order.getWarehouseId().toString()); row.setWarehouseKind("WAREHOUSE");
        row.setTotalAmount(order.getTotalAmount()); row.setStatus(order.getStatus().name());
        row.setCreatedAt(LocalDateTime.ofInstant(order.getCreatedAt(), ZoneOffset.UTC)); row.setVersion(order.getVersion());
        return row;
    }

    /**
     * 映射不超过100行的固定明细。
     * @param order 订单聚合
     * @return 有界持久化行列表
     */
    private List<OrderLineRecord> mapLines(TradeOrder order) {
        var rows = new ArrayList<OrderLineRecord>(order.getLines().size());
        for (int index = 0; index < order.getLines().size(); index++) rows.add(mapLine(order.getId(), index + 1, order.getLines().get(index)));
        return rows;
    }

    /**
     * 映射单行，序号保持输入顺序。
     * @param orderId 订单标识
     * @param number 从1开始的行序号
     * @param line 领域行快照
     * @return ORM行对象
     */
    private OrderLineRecord mapLine(UUID orderId, int number, OrderLine line) {
        var row = new OrderLineRecord();
        row.setId(line.getId().toString()); row.setOrderId(orderId.toString()); row.setLineNumber(number);
        row.setMaterialId(line.getMaterialId().toString()); row.setMaterialKind("MATERIAL");
        row.setOrdered(line.getOrdered().value()); row.setUnitPrice(line.getUnitPrice().value()); row.setFulfilled(line.getFulfilled().value());
        return row;
    }

    /**
     * 核对实际写入数，防止部分行写入被误报为成功。
     * @param actual 实际行数
     * @param expected 预期行数
     */
    private void requireRows(long actual, int expected) {
        if (actual != expected) throw new StratumException(CommonErrorCode.CONFLICT);
    }

    /**
     * 将数据库引用、唯一及并发问题映射为统一安全冲突。
     * @param failure ORM失败
     * @return 业务冲突或原异常
     */
    private RuntimeException translate(EasyQueryException failure) {
        if (failure instanceof EasyQueryConcurrentException) return new StratumException(CommonErrorCode.CONFLICT, List.of("订单版本已变更"), failure);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1452
                    || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("订单引用、标识或版本发生冲突"), failure);
            }
        }
        return failure;
    }
}
