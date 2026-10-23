package com.patrick.stratum.starter.integration;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockMovement;
import org.springframework.jdbc.core.JdbcTemplate;

/** 库存底层测试的关系夹具；仅准备来源，不执行库存或事件写入，订单行为由命令集成测试验证。 */
final class InventorySourceFixture {
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final Set<String> orderIds = ConcurrentHashMap.newKeySet();
    private final Set<String> partyIds = ConcurrentHashMap.newKeySet();

    /**
     * 创建每个测试独立的夹具登记器。
     * @param jdbc 数据准备及清理客户端
     * @param unitOfWork 测试写入事务
     */
    InventorySourceFixture(JdbcTemplate jdbc, UnitOfWork unitOfWork) {
        this.jdbc = jdbc; this.unitOfWork = unitOfWork;
    }

    /**
     * 创建真实订单及履约来源；同一来源重复准备不改变数据，同订单的分批准备应串行调用。
     * @param source 本次来源
     * @param material 真实物料
     * @param warehouse 真实仓库
     * @param direction 库存方向
     * @param quantity 正数量
     */
    void prepare(StockMovement.Source source, UUID material, UUID warehouse,
            StockMovement.Direction direction, Quantity quantity) {
        quantity.requirePositive();
        unitOfWork.execute(() -> {
            String order = source.orderId().toString();
            String posting = source.postingId().toString();
            String line = source.orderLineId().toString();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE id = ?", Integer.class, posting) > 0) return null;
            boolean receipt = direction == StockMovement.Direction.RECEIPT;
            String kind = receipt ? "PURCHASE" : "SALE";
            if (jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE id = ?", Integer.class, order) == 0) {
                String party = UUID.randomUUID().toString();
                String partyKind = receipt ? "SUPPLIER" : "CUSTOMER";
                partyIds.add(party); orderIds.add(order);
                jdbc.update("INSERT INTO erp_master_data (id, kind, code, name) VALUES (?, ?, ?, '库存来源夹具')", party, partyKind, party);
                jdbc.update("""
                        INSERT INTO erp_trade_order
                        (id, kind, order_number, party_id, party_kind, warehouse_id, total_amount, status, created_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, 0, 'COMPLETED', UTC_TIMESTAMP(6), 1)
                        """, order, kind, (receipt ? "PO-" : "SO-") + order, party, partyKind, warehouse.toString());
                jdbc.update("""
                        INSERT INTO erp_order_line (id, order_id, line_number, material_id, ordered, unit_price, fulfilled)
                        VALUES (?, ?, 1, ?, ?, 0, ?)
                        """, line, order, material.toString(), quantity.value(), quantity.value());
            } else {
                // 历史夹具扩充下一批来源；不作为生产订单修改路径。
                jdbc.update("UPDATE erp_trade_order SET version = version + 1 WHERE id = ?", order);
                jdbc.update("UPDATE erp_order_line SET ordered = ordered + ?, fulfilled = fulfilled + ? WHERE id = ? AND order_id = ?",
                        quantity.value(), quantity.value(), line, order);
            }
            Long version = jdbc.queryForObject("SELECT version FROM erp_trade_order WHERE id = ?", Long.class, order);
            jdbc.update("INSERT INTO erp_fulfillment (id, order_id, kind, order_version, created_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))",
                    posting, order, kind, version);
            jdbc.update("INSERT INTO erp_fulfillment_line (posting_id, order_line_id, order_id, quantity) VALUES (?, ?, ?, ?)",
                    posting, line, order, quantity.value());
            return null;
        });
    }

    /** 删除自身登记的来源及交易方；调用方应先删除库存流水，并在测试清理事务中调用。 */
    void clean() {
        for (String order : orderIds) {
            jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id = ?", order);
            jdbc.update("DELETE FROM erp_fulfillment WHERE order_id = ?", order);
            jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", order);
            jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", order);
        }
        for (String party : partyIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", party);
    }
}
