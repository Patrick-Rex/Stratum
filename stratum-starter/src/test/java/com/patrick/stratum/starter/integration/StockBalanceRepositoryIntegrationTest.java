package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证库存精度、维度外键、唯一键和乐观并发。 */
class StockBalanceRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final StockBalanceRepository repository;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> masterIds = new ArrayList<>();

    /**
     * 注入实际数据库适配与事务入口。
     * @param repository 库存仓储
     * @param masters 主数据仓储
     * @param unitOfWork 工作单元
     * @param jdbc 验证及清理客户端
     */
    @Autowired
    StockBalanceRepositoryIntegrationTest(StockBalanceRepository repository, MasterDataRepository masters,
            UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.repository = repository; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 小数无损往返数据库，更新递增版本，不同仓库分别计量。 */
    @Test
    void persistsExactDecimalsAndSeparateWarehouses() {
        UUID material = master(MasterData.Kind.MATERIAL);
        var first = StockBalance.open(material, master(MasterData.Kind.WAREHOUSE));
        var second = StockBalance.open(material, master(MasterData.Kind.WAREHOUSE));
        first.receive(quantity("99999999999999999999999999.999999999999"));
        save(first); save(second);
        assertEquals(first.getQuantity(), load(first).getQuantity());
        first.issue(quantity("0.000000000001")); save(first);
        assertEquals(quantity("99999999999999999999999999.999999999998"), load(first).getQuantity());
        assertEquals(1L, load(first).getVersion());
        assertEquals(Quantity.ZERO, load(second).getQuantity());
    }

    /** 同一物料仓库不能创建第二个余额聚合，缺失维度返回空。 */
    @Test
    void enforcesUniqueDimension() {
        var balance = balance(); save(balance);
        var duplicate = StockBalance.open(balance.getMaterialId(), balance.getWarehouseId());
        assertConflict(() -> save(duplicate));
        assertEquals(balance.getId(), load(balance).getId());
        assertTrue(repository.find(UUID.randomUUID(), balance.getWarehouseId()).isEmpty());
    }

    /** 外键同时验证主数据存在及类别，并阻止删除有库存引用的主数据。 */
    @Test
    void enforcesTypedReferencesAndBlocksDeletion() {
        var balance = balance(); save(balance);
        UUID customer = master(MasterData.Kind.CUSTOMER);
        assertConflict(() -> save(StockBalance.open(customer, balance.getWarehouseId())));
        assertConflict(() -> save(StockBalance.open(balance.getMaterialId(), customer)));
        assertConflict(() -> save(StockBalance.open(UUID.randomUUID(), balance.getWarehouseId())));
        for (UUID id : List.of(balance.getMaterialId(), balance.getWarehouseId())) {
            var value = masters.findById(id.toString()).orElseThrow();
            assertConflict(() -> unitOfWork.execute(() -> { masters.delete(value); return null; }));
        }
    }

    /** 重建快照不能通过UPDATE改变原有物料或仓库。 */
    @Test
    void keepsDimensionsImmutable() {
        var balance = balance(); save(balance);
        var altered = StockBalance.restore(balance.getId(), UUID.randomUUID(), UUID.randomUUID(), quantity("3"), 0);
        save(altered);
        assertEquals(quantity("3"), load(balance).getQuantity());
        assertEquals(balance.getMaterialId(), load(balance).getMaterialId());
        assertEquals(balance.getWarehouseId(), load(balance).getWarehouseId());
    }

    /** 写操作拒绝无事务，失败事务不会保留库存。 */
    @Test
    void rejectsWriteOutsideTransactionAndRollsBack() {
        var balance = balance();
        assertThrows(IllegalStateException.class, () -> repository.save(balance));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.save(balance); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(repository.find(balance.getMaterialId(), balance.getWarehouseId()).isEmpty());
    }

    /** 同版本的真实并发出库只提交一次，不会丢失更新。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentIssuesCommitOnlyOneVersion() throws Exception {
        var balance = balance(); balance.receive(quantity("1")); save(balance);
        var stale = load(balance);
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> compete(balance, ready));
            var second = executor.submit(() -> compete(balance, ready));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(quantity("0.25"), load(balance).getQuantity());
        assertEquals(1L, load(balance).getVersion());
        assertConflict(() -> save(stale));
    }

    /** 清理本测试物料与仓库对应的余额，再移除主数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (String id : masterIds) jdbc.update("DELETE FROM erp_stock_balance WHERE material_id = ? OR warehouse_id = ?", id, id);
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 创建合法库存维度。
     * @return 未保存余额
     */
    private StockBalance balance() { return StockBalance.open(master(MasterData.Kind.MATERIAL), master(MasterData.Kind.WAREHOUSE)); }

    /**
     * 创建测试主数据并登记清理范围。
     * @param kind 主数据类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "库存测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId());
        unitOfWork.execute(() -> { masters.save(value); return null; });
        return UUID.fromString(value.getId());
    }

    /**
     * 在真实事务中保存余额。
     * @param balance 库存聚合
     */
    private void save(StockBalance balance) { unitOfWork.execute(() -> { repository.save(balance); return null; }); }

    /**
     * 按原维度重新读取余额。
     * @param balance 原聚合
     * @return 数据库快照
     */
    private StockBalance load(StockBalance balance) {
        return repository.find(balance.getMaterialId(), balance.getWarehouseId()).orElseThrow();
    }

    /**
     * 检查安全的业务冲突错误码。
     * @param operation 待拒绝的操作
     */
    private void assertConflict(Runnable operation) {
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, operation::run).getErrorCode());
    }

    /**
     * 双方读到相同版本后同时扣减，数据库决定胜出者。
     * @param balance 库存维度
     * @param ready 双方已读取信号
     * @return 提交为1，冲突为0
     */
    private int compete(StockBalance balance, CountDownLatch ready) {
        try {
            return unitOfWork.execute(() -> {
                var current = load(balance); ready.countDown();
                assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
                current.issue(quantity("0.75")); repository.save(current); return 1;
            });
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode()); return 0;
        }
    }

    /**
     * 解析精确十进制数量。
     * @param value 文本数量
     * @return 数量值对象
     */
    private Quantity quantity(String value) { return new Quantity(new BigDecimal(value)); }
}
