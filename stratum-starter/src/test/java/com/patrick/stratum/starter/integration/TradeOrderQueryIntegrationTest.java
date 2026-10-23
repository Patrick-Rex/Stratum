package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import com.patrick.stratum.query.trade.TradeOrderQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证采购销售摘要查询的类别、状态、金额、时间和分页。 */
class TradeOrderQueryIntegrationTest extends MariaDbIntegrationTest {
    private final QueryGateway gateway;
    private final MasterDataRepository masters;
    private final TradeOrderRepository orders;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> masterIds = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();
    private final QueryContext context = QueryContext.anonymous(UUID.randomUUID().toString());

    /**
     * 装配查询入口与写入夹具。
     * @param gateway 查询路由
     * @param masters 主数据夹具仓储
     * @param orders 订单夹具仓储
     * @param unitOfWork 夹具事务
     * @param jdbc 清理客户端
     */
    @Autowired
    TradeOrderQueryIntegrationTest(QueryGateway gateway, MasterDataRepository masters, TradeOrderRepository orders,
            UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.gateway = gateway; this.masters = masters; this.orders = orders; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 类别、交易方、仓库和单号条件保留精确摘要，跨类别ID不能匹配。 */
    @Test
    void filtersOrderIdentityAndPreservesSummary() {
        UUID warehouse = master(MasterData.Kind.WAREHOUSE); UUID material = master(MasterData.Kind.MATERIAL);
        for (var kind : TradeOrder.Kind.values()) {
            UUID party = master(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER);
            var order = order(kind, party, warehouse, material, "0.005");
            var result = read(kind, List.of(eq("warehouseId", warehouse), eq("partyId", party), eq("orderNumber", order.getOrderNumber())));
            assertEquals(1, result.getTotal()); var row = result.getItems().getFirst();
            assertEquals(order.getId(), row.id()); assertEquals(kind, row.kind()); assertEquals(party, row.partyId());
            assertEquals(warehouse, row.warehouseId()); assertEquals(order.getTotalAmount(), row.totalAmount());
            assertEquals(order.getCreatedAt(), row.createdAt()); assertEquals(TradeOrder.Status.PENDING, row.status()); assertEquals(0, row.version());
            var other = kind == TradeOrder.Kind.PURCHASE ? TradeOrder.Kind.SALE : TradeOrder.Kind.PURCHASE;
            assertTrue(read(other, List.of(eq("id", order.getId()))).getItems().isEmpty());
        }
        assertTrue(read(TradeOrder.Kind.PURCHASE, List.of(eq("id", UUID.randomUUID()))).getItems().isEmpty());
    }

    /** 状态和版本从已提交订单读取，金额范围不改变持久化舍入结果。 */
    @Test
    void readsCurrentProgressAndInclusiveAmountRange() {
        UUID party = master(MasterData.Kind.SUPPLIER); UUID warehouse = master(MasterData.Kind.WAREHOUSE); UUID material = master(MasterData.Kind.MATERIAL);
        var order = order(TradeOrder.Kind.PURCHASE, party, warehouse, material, "0.005");
        for (var state : List.of(TradeOrder.Status.PARTIAL, TradeOrder.Status.COMPLETED)) {
            unitOfWork.execute(() -> {
                order.fulfill(Map.of(order.getLines().getFirst().getId(), new Quantity(new BigDecimal("0.5")))); orders.save(order); return null;
            });
            var row = read(TradeOrder.Kind.PURCHASE, List.of(eq("id", order.getId()), eq("status", state.name()),
                    new QuerySpec.FilterInput("totalAmount", QuerySpec.Operator.BETWEEN, new BigDecimal("0.01"), new BigDecimal("0.01")))).getItems().getFirst();
            assertEquals(state, row.status()); assertEquals(order.getVersion().longValue(), row.version()); assertEquals(new BigDecimal("0.01"), row.totalAmount());
        }
        assertTrue(read(TradeOrder.Kind.PURCHASE, List.of(eq("id", order.getId()), eq("status", "PENDING"))).getItems().isEmpty());
        assertTrue(read(TradeOrder.Kind.PURCHASE, List.of(eq("id", order.getId()), eq("totalAmount", new BigDecimal("0.005")))).getItems().isEmpty());
    }

    /** 创建时间闭区间含端点，等金额跨页按ID排序，默认按最新创建时间读取。 */
    @Test
    void paginatesStableAmountsAndFiltersTime() {
        UUID party = master(MasterData.Kind.CUSTOMER); UUID warehouse = master(MasterData.Kind.WAREHOUSE); UUID material = master(MasterData.Kind.MATERIAL);
        var first = order(TradeOrder.Kind.SALE, party, warehouse, material, "1");
        var second = order(TradeOrder.Kind.SALE, party, warehouse, material, "1");
        var filters = List.of(eq("warehouseId", warehouse), new QuerySpec.FilterInput("createdAt", QuerySpec.Operator.BETWEEN, first.getCreatedAt(), second.getCreatedAt()));
        var ids = new ArrayList<String>();
        for (int page = 1; page <= 2; page++) {
            var result = gateway.execute(TradeOrderQuery.create(TradeOrder.Kind.SALE, page, 1, List.of("totalAmount,asc"), filters), context);
            assertEquals(2, result.getTotal()); ids.add(result.getItems().getFirst().id().toString());
        }
        assertEquals(orderIds.stream().map(UUID::toString).sorted().toList(), ids);
        assertEquals(second.getId(), read(TradeOrder.Kind.SALE, filters).getItems().getFirst().id());
        assertEquals(first.getId(), read(TradeOrder.Kind.SALE, List.of(eq("id", first.getId()), eq("createdAt", first.getCreatedAt()))).getItems().getFirst().id());
    }

    /** 状态和参数形状严格校验，单号注入文本不能改变过滤，查询拒绝写事务。 */
    @Test
    void rejectsInvalidInputsAndWriteTransactions() {
        for (var filter : List.of(eq("status", "UNKNOWN"), eq("partyId", "' OR 1=1"), eq("totalAmount", "1"),
                new QuerySpec.FilterInput("totalAmount", QuerySpec.Operator.BETWEEN, BigDecimal.TEN, BigDecimal.ONE))) {
            assertThrows(IllegalArgumentException.class, () -> read(TradeOrder.Kind.PURCHASE, List.of(filter)));
        }
        assertThrows(IllegalArgumentException.class, () -> TradeOrderQuery.create(TradeOrder.Kind.PURCHASE, 1, 20, List.of("status,asc"), List.of()));
        assertTrue(read(TradeOrder.Kind.PURCHASE, List.of(eq("orderNumber", "' OR 1=1 --"))).getItems().isEmpty());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> read(TradeOrder.Kind.PURCHASE, List.of())));
    }

    /** 仅删除自身订单、明细和主数据夹具。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (UUID id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id.toString());
            for (UUID id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id.toString());
            for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            return null;
        });
    }

    /**
     * 保存单行订单夹具。
     * @param kind 类别
     * @param party 交易方
     * @param warehouse 仓库
     * @param material 物料
     * @param price 单价文本
     * @return 已保存订单
     */
    private TradeOrder order(TradeOrder.Kind kind, UUID party, UUID warehouse, UUID material, String price) {
        var order = TradeOrder.create(kind, party, warehouse, List.of(OrderLine.create(material, new Quantity(BigDecimal.ONE), new UnitPrice(new BigDecimal(price)))));
        orderIds.add(order.getId()); unitOfWork.execute(() -> { orders.save(order); return null; }); return order;
    }

    /**
     * 保存真实主数据夹具。
     * @param kind 类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var master = MasterData.create(kind, UUID.randomUUID().toString(), "订单读取", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(master.getId()); unitOfWork.execute(() -> { masters.save(master); return null; }); return UUID.fromString(master.getId());
    }

    /**
     * 经统一入口读取默认分页。
     * @param kind 类别
     * @param filters 条件
     * @return 摘要分页
     */
    private QueryResult<TradeOrderQuery.Row> read(TradeOrder.Kind kind, List<QuerySpec.FilterInput> filters) {
        return gateway.execute(TradeOrderQuery.create(kind, 1, 20, List.of(), filters), context);
    }

    /**
     * 创建精确匹配输入。
     * @param field 字段
     * @param value 值
     * @return 未解析条件
     */
    private QuerySpec.FilterInput eq(String field, Object value) { return new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null); }
}
