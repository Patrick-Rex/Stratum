package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.inventory.StockMovementRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证流水快照、履约唯一约束和余额同事务回滚。 */
class StockMovementRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final StockMovementRepository movements;
    private final StockBalanceRepository balances;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final InventorySourceFixture sources;
    private final List<String> masterIds = new ArrayList<>();
    private final List<String> balanceIds = new ArrayList<>();

    /**
     * 装配真实持久化与工作单元。
     * @param movements 流水仓储
     * @param balances 库存仓储
     * @param masters 主数据仓储
     * @param unitOfWork 事务入口
     * @param jdbc 断言及清理客户端
     */
    @Autowired
    StockMovementRepositoryIntegrationTest(StockMovementRepository movements, StockBalanceRepository balances,
            MasterDataRepository masters, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.movements = movements; this.balances = balances; this.masters = masters;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc;
        this.sources = new InventorySourceFixture(jdbc, unitOfWork);
    }

    /** 同采购订单行分批生成不同履约，精确快照均无损落盘。 */
    @Test
    void storesPartialPostingSnapshotsWithoutLoss() {
        var balance = balance();
        var firstSource = source();
        var first = receipt(balance, firstSource);
        var secondSource = new StockMovement.Source(firstSource.orderId(), UUID.randomUUID(), firstSource.orderLineId());
        sources.prepare(secondSource, balance.getMaterialId(), balance.getWarehouseId(), StockMovement.Direction.RECEIPT, quantity("0.125"));
        var second = unitOfWork.execute(() -> {
            balance.receive(quantity("0.125")); balances.save(balance);
            var movement = StockMovement.create(balance, secondSource, StockMovement.Direction.RECEIPT, quantity("0.125"));
            movements.append(movement); return movement;
        });
        for (var movement : List.of(first, second)) {
            var row = jdbc.queryForMap("SELECT * FROM erp_stock_movement WHERE id = ?", movement.getId().toString());
            assertEquals(movement.getSource().orderId().toString(), row.get("order_id"));
            assertEquals(movement.getSource().postingId().toString(), row.get("posting_id"));
            assertEquals(movement.getSource().orderLineId().toString(), row.get("order_line_id"));
            assertEquals(movement.getDirection().name(), row.get("direction"));
            assertEquals(movement.getQuantity().value(), row.get("quantity"));
            assertEquals(movement.getBalanceAfter().value(), row.get("balance_after"));
            assertEquals(movement.getBalanceVersion(), ((Number) row.get("balance_version")).longValue());
            assertEquals(movement.getOccurredAt(), jdbc.queryForObject("SELECT occurred_at FROM erp_stock_movement WHERE id = ?",
                    (result, index) -> result.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), movement.getId().toString()));
        }
        assertEquals(2, count(balance));
    }

    /** 同履约行即使新建流水UUID也被拒绝，先前的余额变化同步回滚。 */
    @Test
    void duplicatePostingRollsBackBalanceChange() {
        var balance = balance(); var source = source(); receipt(balance, source);
        assertConflict(() -> receipt(balance, source));
        var current = balances.find(balance.getMaterialId(), balance.getWarehouseId()).orElseThrow();
        assertEquals(quantity("1.25"), current.getQuantity());
        assertEquals(0L, current.getVersion());
        assertEquals(1, count(balance));
    }

    /** 同一余额版本只能被一条流水占用，无法用另一个来源重复登记。 */
    @Test
    void rejectsDuplicateBalanceVersion() {
        var balance = balance(); receipt(balance, source());
        var duplicate = StockMovement.create(balance, source(), StockMovement.Direction.RECEIPT, quantity("1"));
        sources.prepare(duplicate.getSource(), balance.getMaterialId(), balance.getWarehouseId(), duplicate.getDirection(), duplicate.getQuantity());
        assertConflict(() -> unitOfWork.execute(() -> { movements.append(duplicate); return null; }));
        assertEquals(1, count(balance));
    }

    /** 流水必须关联真实余额，存在流水的余额也不能删除。 */
    @Test
    void enforcesBalanceReference() {
        var balance = balance();
        var missing = StockBalance.restore(UUID.randomUUID(), balance.getMaterialId(), balance.getWarehouseId(), quantity("1"), 0);
        var movement = StockMovement.create(missing, source(), StockMovement.Direction.RECEIPT, quantity("1"));
        sources.prepare(movement.getSource(), missing.getMaterialId(), missing.getWarehouseId(), movement.getDirection(), movement.getQuantity());
        assertConflict(() -> unitOfWork.execute(() -> { movements.append(movement); return null; }));
        receipt(balance, source());
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM erp_stock_balance WHERE id = ?", balance.getId().toString()));
    }

    /** 流水要求已有写事务，外层失败时新余额和流水均不保留。 */
    @Test
    void requiresTransactionAndRollsBackTogether() {
        var balance = balance();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            receipt(balance, source()); throw new IllegalStateException("测试回滚");
        }));
        assertEquals(0, count(balance));
        assertTrue(balances.find(balance.getMaterialId(), balance.getWarehouseId()).isEmpty());
        var movement = StockMovement.create(balance, source(), StockMovement.Direction.RECEIPT, quantity("1"));
        assertThrows(IllegalStateException.class, () -> movements.append(movement));
    }

    /** 数据库兜底拒绝零数量、未知方向及不一致的变更前余额。 */
    @Test
    void databaseRejectsInvalidSnapshots() {
        var balance = balance(); var movement = receipt(balance, source());
        for (String assignment : List.of("quantity = 0", "direction = 'UNKNOWN'", "balance_after = 0", "balance_version = -1")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "UPDATE erp_stock_movement SET " + assignment + " WHERE id = ?", movement.getId().toString()));
        }
    }

    /** 按外键依赖倒序清理，仅删除本测试登记的资源。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (String id : balanceIds) jdbc.update("DELETE FROM erp_stock_movement WHERE balance_id = ?", id);
            for (String id : balanceIds) jdbc.update("DELETE FROM erp_stock_balance WHERE id = ?", id);
            sources.clean();
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 创建库存维度并登记余额待清理标识。
     * @return 未保存余额
     */
    private StockBalance balance() {
        var balance = StockBalance.open(master(MasterData.Kind.MATERIAL), master(MasterData.Kind.WAREHOUSE));
        balanceIds.add(balance.getId().toString()); return balance;
    }

    /**
     * 保存独立测试主数据。
     * @param kind 主数据类别
     * @return 主数据标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "流水测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId());
        unitOfWork.execute(() -> { masters.save(value); return null; });
        return UUID.fromString(value.getId());
    }

    /**
     * 在同事务中增加余额并追加流水。
     * @param balance 余额聚合
     * @param source 履约来源
     * @return 已写入流水
     */
    private StockMovement receipt(StockBalance balance, StockMovement.Source source) {
        sources.prepare(source, balance.getMaterialId(), balance.getWarehouseId(), StockMovement.Direction.RECEIPT, quantity("1.25"));
        return unitOfWork.execute(() -> {
            balance.receive(quantity("1.25")); balances.save(balance);
            var movement = StockMovement.create(balance, source, StockMovement.Direction.RECEIPT, quantity("1.25"));
            movements.append(movement); return movement;
        });
    }

    /**
     * 统计单个余额的流水数。
     * @param balance 库存余额
     * @return 流水数
     */
    private int count(StockBalance balance) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement WHERE balance_id = ?", Integer.class, balance.getId().toString());
    }

    /**
     * 校验统一冲突错误码。
     * @param operation 待拒绝操作
     */
    private void assertConflict(Runnable operation) {
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, operation::run).getErrorCode());
    }

    /**
     * 生成独立来源标识，由来源夹具建立真实关联。
     * @return 履约来源
     */
    private StockMovement.Source source() { return new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); }

    /**
     * 解析精确数量。
     * @param value 十进制文本
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
