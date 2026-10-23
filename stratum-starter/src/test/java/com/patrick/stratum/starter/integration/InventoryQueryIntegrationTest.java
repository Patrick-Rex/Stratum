package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalance;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.inventory.StockBalanceQuery;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证库存读取的维度隔离、精确范围、排序及事务边界。 */
class InventoryQueryIntegrationTest extends MariaDbIntegrationTest {
    private final QueryGateway gateway;
    private final StockBalanceRepository balances;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> masterIds = new ArrayList<>();
    private final List<UUID> balanceIds = new ArrayList<>();
    private final QueryContext context = QueryContext.anonymous(UUID.randomUUID().toString());

    /**
     * 装配只读入口及测试夹具依赖。
     * @param gateway 查询入口
     * @param balances 余额夹具仓储
     * @param masters 主数据夹具仓储
     * @param unitOfWork 夹具事务
     * @param jdbc 清理客户端
     */
    @Autowired
    InventoryQueryIntegrationTest(QueryGateway gateway, StockBalanceRepository balances, MasterDataRepository masters,
            UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.gateway = gateway; this.balances = balances; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 同物料不同仓库隔离，12位小数完整返回，ID详情和缺失维度都经统一入口。 */
    @Test
    void isolatesDimensionsAndPreservesDecimals() {
        UUID material = master(MasterData.Kind.MATERIAL); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        var first = balance(material, warehouse, "12345678901234567890.123456789012");
        balance(material, master(MasterData.Kind.WAREHOUSE), "2");
        var result = read(List.of(eq("materialId", material), eq("warehouseId", warehouse)));
        assertEquals(1, result.getTotal()); var row = result.getItems().getFirst();
        assertEquals(first.getQuantity().value(), row.quantity()); assertEquals(first.getId(), row.id()); assertEquals(0, row.version());
        assertEquals(row, read(List.of(eq("id", first.getId()))).getItems().getFirst());
        assertTrue(read(List.of(eq("warehouseId", UUID.randomUUID()))).getItems().isEmpty());
    }

    /** 数量闭区间包含两个端点，精确条件不做舍入，分页按数量和ID稳定排序。 */
    @Test
    void filtersInclusiveRangeAndPaginatesTies() {
        UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        for (String quantity : List.of("0.125", "0.125", "0.25", "0.5")) balance(master(MasterData.Kind.MATERIAL), warehouse, quantity);
        var range = new QuerySpec.FilterInput("quantity", QuerySpec.Operator.BETWEEN, new BigDecimal("0.125"), new BigDecimal("0.25"));
        var filters = List.of(eq("warehouseId", warehouse), range);
        var first = gateway.execute(StockBalanceQuery.create(1, 2, List.of("quantity,asc"), filters), context);
        var second = gateway.execute(StockBalanceQuery.create(2, 2, List.of("quantity,asc"), filters), context);
        assertEquals(3, first.getTotal()); assertEquals(3, second.getTotal());
        assertEquals(balanceIds.subList(0, 2).stream().map(UUID::toString).sorted().toList(), first.getItems().stream().map(row -> row.id().toString()).toList());
        assertEquals(0, new BigDecimal("0.25").compareTo(second.getItems().getFirst().quantity()));
        assertEquals(2, read(List.of(eq("warehouseId", warehouse), eq("quantity", new BigDecimal("0.125")))).getTotal());
        assertTrue(read(List.of(eq("warehouseId", warehouse), eq("quantity", new BigDecimal("0.126")))).getItems().isEmpty());
    }

    /** 非法字段、字符串数量、未知操作和反向区间被白名单阻止。 */
    @Test
    void rejectsInvalidBalanceFilters() {
        for (var input : List.of(eq("quantity", "0"), eq("id", "' OR 1=1"), eq("unknown", UUID.randomUUID()),
                new QuerySpec.FilterInput("quantity", QuerySpec.Operator.BETWEEN, BigDecimal.ONE, BigDecimal.ZERO),
                new QuerySpec.FilterInput("quantity", QuerySpec.Operator.CONTAINS, BigDecimal.ONE, null))) {
            assertThrows(IllegalArgumentException.class, () -> read(List.of(input)));
        }
        assertThrows(IllegalArgumentException.class, () -> StockBalanceQuery.create(1, 20, List.of("quantity;drop,asc"), List.of()));
    }

    /** 写事务内拒绝查询，回滚余额不出现在之后的列表中。 */
    @Test
    void rejectsWriteTransactionsAndHidesRolledBackBalance() {
        UUID material = master(MasterData.Kind.MATERIAL); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> read(List.of(eq("warehouseId", warehouse)))));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            balance(material, warehouse, "1"); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(read(List.of(eq("warehouseId", warehouse))).getItems().isEmpty());
    }

    /** 仅清理本测试余额和主数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (UUID id : balanceIds) jdbc.update("DELETE FROM erp_stock_balance WHERE id = ?", id.toString());
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 创建库存夹具，所有写入均在查询模块外的工作单元内。
     * @param material 物料
     * @param warehouse 仓库
     * @param quantity 数量文本
     * @return 已保存余额
     */
    private StockBalance balance(UUID material, UUID warehouse, String quantity) {
        var balance = StockBalance.open(material, warehouse); balance.receive(new Quantity(new BigDecimal(quantity)));
        balanceIds.add(balance.getId()); unitOfWork.execute(() -> { balances.save(balance); return null; }); return balance;
    }

    /**
     * 创建独立主数据夹具。
     * @param kind 类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "库存查询", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 执行默认分页余额读取。
     * @param filters 过滤表达
     * @return 分页结果
     */
    private QueryResult<StockBalanceQuery.Row> read(List<QuerySpec.FilterInput> filters) {
        return gateway.execute(StockBalanceQuery.create(1, 20, List.of(), filters), context);
    }

    /**
     * 创建单值精确过滤输入。
     * @param field 字段
     * @param value 值
     * @return 未解析过滤表达
     */
    private QuerySpec.FilterInput eq(String field, Object value) { return new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null); }
}
