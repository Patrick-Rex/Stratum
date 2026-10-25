package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.Fulfillment;
import com.patrick.stratum.domain.trade.FulfillmentRepository;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;
import com.patrick.stratum.query.trade.TradeOrderQuery;
import com.patrick.stratum.query.trade.TradeOrderDetailQuery;
import com.patrick.stratum.query.trade.FulfillmentQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证采购销售摘要查询的类别、状态、金额、时间和分页。 */
class TradeOrderQueryIntegrationTest extends WarehouseAuthorizedQueryIntegrationTest {
    private final QueryGateway gateway;
    private final MasterDataRepository masters;
    private final TradeOrderRepository orders;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final FulfillmentRepository postings;
    private final List<String> masterIds = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();

    /**
     * 装配查询入口与写入夹具。
     * @param gateway 查询路由
     * @param masters 主数据夹具仓储
     * @param orders 订单夹具仓储
     * @param unitOfWork 夹具事务
     * @param jdbc 清理客户端
     * @param postings 履约夹具仓储
     */
    @Autowired
    TradeOrderQueryIntegrationTest(QueryGateway gateway, MasterDataRepository masters, TradeOrderRepository orders,
            UnitOfWork unitOfWork, JdbcTemplate jdbc, FulfillmentRepository postings) {
        this.gateway = gateway; this.masters = masters; this.orders = orders; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
        this.postings = postings;
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

    /** 详情按原始行号返回最多100行，保留固定单价、数量和已提交履约进度。 */
    @Test
    void loadsMaximumDetailLinesInOriginalOrder() {
        UUID party = master(MasterData.Kind.SUPPLIER); UUID warehouse = master(MasterData.Kind.WAREHOUSE); UUID material = master(MasterData.Kind.MATERIAL);
        var lines = IntStream.rangeClosed(1, TradeOrder.MAX_LINES).mapToObj(index -> OrderLine.create(material,
                new Quantity(BigDecimal.ONE), new UnitPrice(BigDecimal.valueOf(index, 2)))).toList();
        var order = TradeOrder.create(TradeOrder.Kind.PURCHASE, party, warehouse, lines);
        orderIds.add(order.getId());
        unitOfWork.execute(() -> {
            orders.save(order); order.fulfill(Map.of(lines.getFirst().getId(), new Quantity(new BigDecimal("0.125")))); orders.save(order); return null;
        });
        var detail = gateway.execute(TradeOrderDetailQuery.create(order.getKind(), order.getId()), context).getItems().getFirst();
        assertEquals(100, detail.lines().size()); assertEquals(TradeOrder.Status.PARTIAL, detail.order().status());
        assertEquals(order.getTotalAmount(), detail.order().totalAmount()); assertEquals(1, detail.order().version());
        for (int index = 0; index < lines.size(); index++) {
            var expected = order.getLines().get(index); var actual = detail.lines().get(index);
            assertEquals(index + 1, actual.lineNumber()); assertEquals(expected.getId(), actual.id());
            assertEquals(expected.getMaterialId(), actual.materialId()); assertEquals(expected.getOrdered().value(), actual.quantity());
            assertEquals(expected.getUnitPrice().value(), actual.unitPrice()); assertEquals(expected.getFulfilled().value(), actual.fulfilled());
        }
        assertThrows(UnsupportedOperationException.class, () -> detail.lines().clear());
    }

    /** 缺失和跨类别详情返回空结果，详情读取也拒绝加入写工作单元。 */
    @Test
    void protectsDetailKindAndTransactionBoundary() {
        var order = order(TradeOrder.Kind.SALE, master(MasterData.Kind.CUSTOMER), master(MasterData.Kind.WAREHOUSE), master(MasterData.Kind.MATERIAL), "1");
        for (var query : List.of(TradeOrderDetailQuery.create(TradeOrder.Kind.PURCHASE, order.getId()), TradeOrderDetailQuery.create(TradeOrder.Kind.SALE, UUID.randomUUID()))) {
            var result = gateway.execute(query, context); assertEquals(0, result.getTotal()); assertTrue(result.getItems().isEmpty());
        }
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> gateway.execute(TradeOrderDetailQuery.create(order.getKind(), order.getId()), context)));
    }

    /**
     * 并发提交履约进度时，每次详情头版本与行数量属于同一语句快照。
     * @throws Exception 并发任务超时或失败
     */
    @Test
    void readsConsistentDetailDuringConcurrentProgress() throws Exception {
        var order = order(TradeOrder.Kind.PURCHASE, master(MasterData.Kind.SUPPLIER), master(MasterData.Kind.WAREHOUSE), master(MasterData.Kind.MATERIAL), "1");
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                ready.countDown(); assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
                for (int index = 0; index < 20; index++) unitOfWork.execute(() -> {
                    order.fulfill(Map.of(order.getLines().getFirst().getId(), new Quantity(new BigDecimal("0.01")))); orders.save(order); return null;
                });
            });
            var reader = executor.submit(() -> {
                ready.countDown(); assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
                for (int index = 0; index < 30; index++) {
                    var detail = gateway.execute(TradeOrderDetailQuery.create(order.getKind(), order.getId()), context).getItems().getFirst();
                    assertEquals(0, BigDecimal.valueOf(detail.order().version(), 2).compareTo(detail.lines().getFirst().fulfilled()));
                    assertEquals(detail.order().version() == 0 ? TradeOrder.Status.PENDING : TradeOrder.Status.PARTIAL, detail.order().status());
                }
            });
            writer.get(20, TimeUnit.SECONDS); reader.get(20, TimeUnit.SECONDS);
        }
        assertEquals(20, gateway.execute(TradeOrderDetailQuery.create(order.getKind(), order.getId()), context).getItems().getFirst().order().version());
    }

    /** 分批履约历史保持当时版本和数量，筛选头记录后仅返回相应批次明细。 */
    @Test
    void readsImmutablePartialFulfillmentHistory() {
        for (var kind : TradeOrder.Kind.values()) {
            var order = order(kind, master(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER),
                    master(MasterData.Kind.WAREHOUSE), master(MasterData.Kind.MATERIAL), "1");
            var first = fulfill(order, "0.4"); var second = fulfill(order, "0.6");
            var history = gateway.execute(FulfillmentQuery.create(kind, order.getId(), 1, 20, List.of(), List.of()), context);
            assertEquals(2, history.getTotal()); assertEquals(List.of(second.getId(), first.getId()), history.getItems().stream().map(FulfillmentQuery.Row::id).toList());
            var old = history.getItems().getLast();
            assertEquals(1, old.orderVersion()); assertEquals(first.getCreatedAt(), old.createdAt()); assertEquals(order.getId(), old.orderId());
            assertEquals(0, new BigDecimal("0.4").compareTo(old.lines().getFirst().quantity()));
            assertEquals(order.getLines().getFirst().getId(), old.lines().getFirst().orderLineId());
            var filters = List.of(eq("id", first.getId()), eq("orderVersion", 1L), eq("createdAt", first.getCreatedAt()));
            assertEquals(old, gateway.execute(FulfillmentQuery.create(kind, order.getId(), 1, 20, List.of(), filters), context).getItems().getFirst());
            var range = List.of(new QuerySpec.FilterInput("orderVersion", QuerySpec.Operator.BETWEEN, 1L, 2L),
                    new QuerySpec.FilterInput("createdAt", QuerySpec.Operator.BETWEEN, first.getCreatedAt(), second.getCreatedAt()));
            assertEquals(2, gateway.execute(FulfillmentQuery.create(kind, order.getId(), 1, 20, List.of("orderVersion,asc"), range), context).getTotal());
        }
    }

    /** 履约按头分页，每批100行仍完整有序，分页边界不会截断明细。 */
    @Test
    void paginatesFulfillmentsWithoutTruncatingHundredLines() {
        UUID material = master(MasterData.Kind.MATERIAL);
        var lines = IntStream.range(0, TradeOrder.MAX_LINES).mapToObj(index -> OrderLine.create(material, new Quantity(BigDecimal.ONE), new UnitPrice(BigDecimal.ONE))).toList();
        var order = TradeOrder.create(TradeOrder.Kind.PURCHASE, master(MasterData.Kind.SUPPLIER), master(MasterData.Kind.WAREHOUSE), lines);
        orderIds.add(order.getId()); unitOfWork.execute(() -> { orders.save(order); return null; });
        var first = fulfill(order, "0.25"); var second = fulfill(order, "0.75");
        for (int page = 1; page <= 2; page++) {
            var result = gateway.execute(FulfillmentQuery.create(order.getKind(), order.getId(), page, 1, List.of("orderVersion,asc"), List.of()), context);
            assertEquals(2, result.getTotal()); assertEquals(1, result.getItems().size());
            var row = result.getItems().getFirst(); assertEquals(page == 1 ? first.getId() : second.getId(), row.id()); assertEquals(100, row.lines().size());
            for (int index = 0; index < lines.size(); index++) {
                assertEquals(index + 1, row.lines().get(index).lineNumber()); assertEquals(lines.get(index).getId(), row.lines().get(index).orderLineId());
                assertEquals(material, row.lines().get(index).materialId());
            }
            assertThrows(UnsupportedOperationException.class, () -> row.lines().clear());
        }
        var empty = gateway.execute(FulfillmentQuery.create(order.getKind(), order.getId(), 3, 1, List.of(), List.of()), context);
        assertEquals(2, empty.getTotal()); assertTrue(empty.getItems().isEmpty());
    }

    /** 履约查询拒绝错误输入及写事务，其他订单或类别不能取得已知凭据。 */
    @Test
    void protectsFulfillmentHistoryScopeAndInput() {
        var order = order(TradeOrder.Kind.SALE, master(MasterData.Kind.CUSTOMER), master(MasterData.Kind.WAREHOUSE), master(MasterData.Kind.MATERIAL), "1");
        var posting = fulfill(order, "1");
        for (var query : List.of(FulfillmentQuery.create(TradeOrder.Kind.PURCHASE, order.getId(), 1, 20, List.of(), List.of()),
                FulfillmentQuery.create(TradeOrder.Kind.SALE, UUID.randomUUID(), 1, 20, List.of(), List.of(eq("id", posting.getId()))))) {
            assertTrue(gateway.execute(query, context).getItems().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> FulfillmentQuery.create(order.getKind(), order.getId(), 1, 20, List.of(), List.of(eq("orderVersion", "1"))));
        assertThrows(IllegalArgumentException.class, () -> FulfillmentQuery.create(order.getKind(), order.getId(), 1, 20, List.of("orderId,asc"), List.of()));
        var valid = FulfillmentQuery.create(order.getKind(), order.getId(), 1, 20, List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> gateway.execute(valid, context)));
    }

    /** 仅删除自身订单、明细和主数据夹具。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (UUID id : orderIds) jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id = ?", id.toString());
            for (UUID id : orderIds) jdbc.update("DELETE FROM erp_fulfillment WHERE order_id = ?", id.toString());
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
     * 为每行记录相同数量的履约夹具，订单和凭据同事务提交，不执行库存用例。
     * @param order 订单
     * @param quantity 本次数量文本
     * @return 已保存凭据
     */
    private Fulfillment fulfill(TradeOrder order, String quantity) {
        var values = order.getLines().stream().collect(Collectors.toMap(OrderLine::getId, ignored -> new Quantity(new BigDecimal(quantity))));
        return unitOfWork.execute(() -> {
            order.fulfill(values); orders.save(order); var posting = Fulfillment.create(order, values); postings.append(posting); return posting;
        });
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
