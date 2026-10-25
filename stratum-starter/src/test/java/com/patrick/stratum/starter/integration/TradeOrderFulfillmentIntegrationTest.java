package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.command.CommandGateway;
import com.patrick.stratum.application.trade.CreateOrderCommand;
import com.patrick.stratum.application.trade.FulfillOrderCommand;
import com.patrick.stratum.application.trade.FulfillmentResult;
import com.patrick.stratum.application.trade.OrderResult;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.inventory.StockBalanceQuery;
import com.patrick.stratum.query.inventory.StockMovementQuery;
import com.patrick.stratum.query.masterdata.MasterDataQuery;
import com.patrick.stratum.query.spec.QuerySpec;
import com.patrick.stratum.query.trade.FulfillmentQuery;
import com.patrick.stratum.query.trade.TradeOrderDetailQuery;
import com.patrick.stratum.query.trade.TradeOrderQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 完整命令链验证分批采购收货、销售出库和并发下的跨聚合原子性。 */
class TradeOrderFulfillmentIntegrationTest extends WarehouseAuthorizedQueryIntegrationTest {
    /**
     * 值对象：本测试的独立业务维度。
     * @param material 物料
     * @param warehouse 仓库
     * @param supplier 供应商
     * @param customer 客户
     */
    private record Fixture(UUID material, UUID warehouse, UUID supplier, UUID customer) { }
    private final CommandGateway gateway;
    private final MasterDataRepository masters;
    private final TradeOrderRepository orders;
    private final StockBalanceRepository balances;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final QueryGateway queries;
    private final String subject = UUID.randomUUID().toString();
    private final List<String> masterIds = new ArrayList<>();
    private final List<String> orderIds = new ArrayList<>();

    /**
     * 注入完整写链路与独立数据库断言。
     * @param gateway 命令路由
     * @param masters 主数据仓储
     * @param orders 订单仓储
     * @param balances 余额仓储
     * @param unitOfWork 工作单元
     * @param jdbc 断言及清理客户端
     * @param queries 只读查询入口
     */
    @Autowired
    TradeOrderFulfillmentIntegrationTest(CommandGateway gateway, MasterDataRepository masters, TradeOrderRepository orders,
            StockBalanceRepository balances, UnitOfWork unitOfWork, JdbcTemplate jdbc, QueryGateway queries) {
        this.gateway = gateway; this.masters = masters; this.orders = orders; this.balances = balances;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc;
        this.queries = queries;
    }

    /** 关联测试事件，工作线程另行显式传播。 */
    @BeforeEach
    void bindTrace() { TraceIdContext.setTraceId(subject); }

    /** 分批收货增加库存，同键重放保持原履约标识及当时订单快照。 */
    @Test
    void receivesInPartsAndReplaysOriginalSnapshot() {
        var fixture = fixture(); var order = create(TradeOrder.Kind.PURCHASE, fixture, "1");
        var firstCommand = command(order, "0.4"); var first = post(firstCommand);
        assertEquals(TradeOrder.Status.PARTIAL, first.getOrder().getStatus());
        assertEquals(first.getPostingId(), post(firstCommand).getPostingId());
        var second = post(command(first.getOrder(), "0.6"));
        assertEquals(TradeOrder.Status.COMPLETED, second.getOrder().getStatus());
        assertEquals(TradeOrder.Status.PARTIAL, post(firstCommand).getOrder().getStatus());
        assertStock(fixture, "1"); assertPostingCount(order, 2, 2); assertCounts(5, 3);
    }

    /** 流水来源必须完整引用同一履约行，拒绝拼接其他订单、履约或行，也禁止删除被引用来源。 */
    @Test
    void databaseProtectsExactFulfillmentSource() {
        var fixture = fixture();
        var first = create(TradeOrder.Kind.PURCHASE, fixture, "1");
        var second = create(TradeOrder.Kind.PURCHASE, fixture, "1");
        var firstPosting = post(command(first, "1"));
        var secondPosting = post(command(second, "1"));
        for (var replacement : List.of(
                new String[] {"order_id", second.getId().toString()},
                new String[] {"posting_id", secondPosting.getPostingId().toString()},
                new String[] {"order_line_id", second.getLines().getFirst().getId().toString()},
                new String[] {"posting_id", UUID.randomUUID().toString()})) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "UPDATE erp_stock_movement SET " + replacement[0] + " = ? WHERE posting_id = ?",
                    replacement[1], firstPosting.getPostingId().toString()));
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM erp_fulfillment_line WHERE posting_id = ?", firstPosting.getPostingId().toString()));
        assertEquals(first.getId().toString(), jdbc.queryForObject(
                "SELECT order_id FROM erp_stock_movement WHERE posting_id = ?", String.class, firstPosting.getPostingId().toString()));
        assertStock(fixture, "2");
    }

    /** 真实采购收货后分批销售出库，精确扣减库存并完成订单。 */
    @Test
    void issuesSalesAgainstReceivedStock() {
        var fixture = fixture(); post(command(create(TradeOrder.Kind.PURCHASE, fixture, "2"), "2"));
        var sale = create(TradeOrder.Kind.SALE, fixture, "1");
        var partial = post(command(sale, "0.25"));
        var completed = post(command(partial.getOrder(), "0.75"));
        assertEquals(TradeOrder.Status.COMPLETED, completed.getOrder().getStatus());
        assertStock(fixture, "1"); assertPostingCount(sale, 2, 2); assertCounts(8, 5);
    }

    /** 真实命令提交后六类查询一致，历史批次与当前进度分离，重放不产生新流水。 */
    @Test
    void queriesReflectCommittedPurchaseAndSaleWithoutDuplicatingReplay() {
        var fixture = fixture();
        var purchase = create(TradeOrder.Kind.PURCHASE, fixture, "2");
        post(command(purchase, "2"));
        var sale = create(TradeOrder.Kind.SALE, fixture, "1");
        var request = command(sale, "0.25"); var first = post(request);
        post(command(first.getOrder(), "0.75"));
        assertEquals(TradeOrder.Status.PARTIAL, post(request).getOrder().getStatus());
        var master = queries.execute(MasterDataQuery.create(MasterData.Kind.MATERIAL, 1, 1, List.of(), exact("id", fixture.material())), context);
        assertEquals(fixture.material(), master.getItems().getFirst().id());
        var summary = queries.execute(TradeOrderQuery.create(TradeOrder.Kind.SALE, 1, 1, List.of(), exact("id", sale.getId())), context).getItems().getFirst();
        var detail = queries.execute(TradeOrderDetailQuery.create(sale.getKind(), sale.getId()), context).getItems().getFirst();
        assertEquals(summary, detail.order());
        assertEquals(TradeOrder.Status.COMPLETED, summary.status());
        assertEquals(2L, summary.version());
        assertEquals(0, BigDecimal.ONE.compareTo(detail.lines().getFirst().fulfilled()));
        var balance = queries.execute(StockBalanceQuery.create(1, 1, List.of(), exact("materialId", fixture.material())), context).getItems().getFirst();
        assertEquals(0, BigDecimal.ONE.compareTo(balance.quantity()));
        var history = queries.execute(FulfillmentQuery.create(sale.getKind(), sale.getId(), 1, 20, List.of("orderVersion,asc"), List.of()), context);
        assertEquals(2, history.getTotal());
        assertEquals(first.getPostingId(), history.getItems().getFirst().id());
        assertEquals(0, new BigDecimal("0.25").compareTo(history.getItems().getFirst().lines().getFirst().quantity()));
        var movements = queries.execute(StockMovementQuery.create(1, 20, List.of("occurredAt,asc"), exact("orderId", sale.getId())), context);
        assertEquals(2, movements.getTotal());
        for (var movement : movements.getItems()) {
            var posting = history.getItems().stream().filter(row -> row.id().equals(movement.postingId())).findFirst().orElseThrow();
            assertEquals(balance.id(), movement.balanceId());
            assertEquals(posting.lines().getFirst().orderLineId(), movement.orderLineId());
            assertEquals(0, posting.lines().getFirst().quantity().compareTo(movement.quantity()));
        }
        assertCounts(8, 5);
    }

    /** 外层失败后查询只看到原订单，不暴露回滚的库存、履约和流水，事务内拒绝查询。 */
    @Test
    void queriesExcludeRolledBackFulfillmentAndRejectActiveTransaction() {
        var fixture = fixture(); var order = create(TradeOrder.Kind.PURCHASE, fixture, "1");
        var detailQuery = TradeOrderDetailQuery.create(order.getKind(), order.getId());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            post(command(order, "1"));
            return queries.execute(detailQuery, context);
        }));
        var detail = queries.execute(detailQuery, context).getItems().getFirst();
        assertEquals(0L, detail.order().version());
        assertEquals(TradeOrder.Status.PENDING, detail.order().status());
        assertEquals(0, BigDecimal.ZERO.compareTo(detail.lines().getFirst().fulfilled()));
        assertEquals(0, queries.execute(FulfillmentQuery.create(order.getKind(), order.getId(), 1, 20, List.of(), List.of()), context).getTotal());
        assertEquals(0, queries.execute(StockMovementQuery.create(1, 20, List.of(), exact("orderId", order.getId())), context).getTotal());
        assertEquals(0, queries.execute(StockBalanceQuery.create(1, 20, List.of(), exact("materialId", fixture.material())), context).getTotal());
        assertCounts(1, 1);
    }

    /**
     * 构造固定字段精确过滤。
     * @param field 白名单字段
     * @param value 参数值
     * @return 单条件列表
     */
    private List<QuerySpec.FilterInput> exact(String field, Object value) {
        return List.of(new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null));
    }

    /** 同物料多行共享余额但保留各行来源和递增余额版本，引用主数据不能删除。 */
    @Test
    void fulfillsMultipleLinesForSameMaterialAtomically() {
        var fixture = fixture();
        for (var kind : TradeOrder.Kind.values()) {
            var order = create(kind, fixture, List.of(createLine(fixture.material(), "0.25"), createLine(fixture.material(), "0.75")));
            var lines = order.getLines().stream().map(line -> FulfillOrderCommand.Line.builder()
                    .orderLineId(line.getId()).quantity(line.getQuantity()).build()).toList();
            var request = command(order, lines, kind);
            var completed = post(request);
            assertEquals(TradeOrder.Status.COMPLETED, completed.getOrder().getStatus());
            assertEquals(completed.getPostingId(), post(request).getPostingId());
            assertPostingCount(order, 1, 2);
            for (UUID reference : List.of(fixture.material(), fixture.warehouse(), kind == TradeOrder.Kind.PURCHASE ? fixture.supplier() : fixture.customer())) {
                assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update("DELETE FROM erp_master_data WHERE id = ?", reference.toString()));
            }
        }
        assertStock(fixture, "0"); assertCounts(8, 4);
        assertEquals(List.of(0L, 1L, 2L, 3L), jdbc.queryForList("""
                SELECT m.balance_version FROM erp_stock_movement m
                JOIN erp_stock_balance b ON b.id = m.balance_id
                WHERE b.material_id = ? AND b.warehouse_id = ? ORDER BY m.balance_version
                """, Long.class, fixture.material().toString(), fixture.warehouse().toString()));
    }

    /** 过量、未知行、重复行、错误类别和过期版本均不能增加履约次数。 */
    @Test
    void rejectsInvalidFulfillmentRequests() {
        var fixture = fixture(); var order = create(TradeOrder.Kind.PURCHASE, fixture, "1");
        assertConflict(() -> post(command(order, "1.01")));
        var unknown = FulfillOrderCommand.Line.builder().orderLineId(UUID.randomUUID()).quantity(BigDecimal.ONE).build();
        assertConflict(() -> post(command(order, List.of(unknown), order.getKind())));
        var line = command(order, "0.25").getLines().getFirst();
        assertEquals(CommonErrorCode.BAD_REQUEST, assertThrows(StratumException.class,
                () -> post(command(order, List.of(line, line), order.getKind()))).getErrorCode());
        assertEquals(CommonErrorCode.NOT_FOUND, assertThrows(StratumException.class,
                () -> post(command(order, List.of(line), TradeOrder.Kind.SALE))).getErrorCode());
        post(command(order, "0.25"));
        assertConflict(() -> post(command(order, "0.25")));
        assertStock(fixture, "0.25"); assertPostingCount(order, 1, 1); assertCounts(3, 2);
    }

    /** 后续物料库存不足时，先前出库、订单进度、凭据、事件和回执整体回滚。 */
    @Test
    void insufficientLaterMaterialRollsBackWholeOrder() {
        var fixture = fixture(); UUID other = master(MasterData.Kind.MATERIAL);
        UUID low = fixture.material().compareTo(other) < 0 ? fixture.material() : other;
        UUID high = low.equals(other) ? fixture.material() : other;
        var stocked = new Fixture(low, fixture.warehouse(), fixture.supplier(), fixture.customer());
        post(command(create(TradeOrder.Kind.PURCHASE, stocked, "1"), "1"));
        var sale = create(TradeOrder.Kind.SALE, fixture, List.of(createLine(low, "1"), createLine(high, "1")));
        var lines = sale.getLines().stream().map(line -> FulfillOrderCommand.Line.builder().orderLineId(line.getId()).quantity(BigDecimal.ONE).build()).toList();
        var request = command(sale, lines, sale.getKind());
        assertConflict(() -> post(request));
        var current = orders.findById(sale.getId()).orElseThrow();
        assertEquals(0L, current.getVersion()); assertEquals(TradeOrder.Status.PENDING, current.getStatus());
        assertStock(stocked, "1"); assertPostingCount(sale, 0, 0); assertCounts(4, 3);
        post(command(create(TradeOrder.Kind.PURCHASE, new Fixture(high, fixture.warehouse(), fixture.supplier(), fixture.customer()), "1"), "1"));
        assertEquals(TradeOrder.Status.COMPLETED, post(request).getOrder().getStatus());
        assertStock(stocked, "0"); assertPostingCount(sale, 1, 2);
    }

    /** 调用方失败时新增库存也回滚，沿用原请求可重试。 */
    @Test
    void outerFailureRollsBackEveryPostingSideEffect() {
        var fixture = fixture(); var order = create(TradeOrder.Kind.PURCHASE, fixture, "1"); var request = command(order, "1");
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            post(request); throw new IllegalStateException("测试外层回滚");
        }));
        assertTrue(balances.find(fixture.material(), fixture.warehouse()).isEmpty());
        assertEquals(0L, orders.findById(order.getId()).orElseThrow().getVersion());
        assertPostingCount(order, 0, 0); assertCounts(1, 1);
        post(request); assertStock(fixture, "1"); assertCounts(3, 2);
    }

    /** 同键并发履约可重放胜出结果，只产生一份业务效果。
     * @throws Exception 并发失败或超时
     */
    @Test
    void concurrentDuplicateRequestHasOneEffect() throws Exception {
        var fixture = fixture(); var order = create(TradeOrder.Kind.PURCHASE, fixture, "1"); var request = command(order, "0.75");
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> compete(request, ready)); var second = executor.submit(() -> compete(request, ready));
            UUID one = first.get(15, TimeUnit.SECONDS); UUID two = second.get(15, TimeUnit.SECONDS);
            assertTrue(one != null || two != null);
            var replay = post(request); assertEquals(one == null ? two : one, replay.getPostingId());
            if (one != null && two != null) assertEquals(one, two);
        }
        assertStock(fixture, "0.75"); assertPostingCount(order, 1, 1); assertCounts(3, 2);
    }

    /** 不同销售订单竞争同一份库存时只能完成一单，失败单保持待履约。
     * @throws Exception 并发失败或超时
     */
    @Test
    void concurrentSalesCannotOversellSharedStock() throws Exception {
        var fixture = fixture(); post(command(create(TradeOrder.Kind.PURCHASE, fixture, "1"), "1"));
        var firstOrder = create(TradeOrder.Kind.SALE, fixture, "1"); var secondOrder = create(TradeOrder.Kind.SALE, fixture, "1");
        var firstRequest = command(firstOrder, "1"); var secondRequest = command(secondOrder, "1");
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> compete(firstRequest, ready)); var second = executor.submit(() -> compete(secondRequest, ready));
            assertEquals(1, (first.get(15, TimeUnit.SECONDS) == null ? 0 : 1) + (second.get(15, TimeUnit.SECONDS) == null ? 0 : 1));
        }
        assertStock(fixture, "0");
        assertEquals(1, List.of(firstOrder, secondOrder).stream().filter(order -> orders.findById(order.getId()).orElseThrow().getStatus() == TradeOrder.Status.COMPLETED).count());
        assertCounts(7, 5);
    }

    /** 按引用顺序清理测试订单、库存、凭据、回执和事件，不清空共享服务。 */
    @AfterEach
    void cleanOwnRows() {
        try {
            unitOfWork.execute(() -> {
                jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", subject);
                jdbc.update("DELETE FROM idempotency_receipt WHERE scope LIKE ?", "trade:%:" + subject);
                for (String id : orderIds) jdbc.update("DELETE FROM erp_stock_movement WHERE order_id = ?", id);
                for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id = ?", id);
                for (String id : orderIds) jdbc.update("DELETE FROM erp_fulfillment WHERE order_id = ?", id);
                for (String id : orderIds) jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", id);
                for (String id : orderIds) jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", id);
                for (String id : masterIds) jdbc.update("DELETE FROM erp_stock_balance WHERE material_id = ? OR warehouse_id = ?", id, id);
                for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
                return null;
            });
        } finally { TraceIdContext.clear(); }
    }

    /**
     * 建立独立主数据维度。
     * @return 测试业务维度
     */
    private Fixture fixture() { return new Fixture(master(MasterData.Kind.MATERIAL), master(MasterData.Kind.WAREHOUSE), master(MasterData.Kind.SUPPLIER), master(MasterData.Kind.CUSTOMER)); }

    /**
     * 保存独立主数据。
     * @param kind 类别
     * @return 主数据UUID
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "履约命令测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 创建一行采购或销售订单。
     * @param kind 类别
     * @param fixture 业务维度
     * @param quantity 订购数量
     * @return 保存结果
     */
    private OrderResult create(TradeOrder.Kind kind, Fixture fixture, String quantity) { return create(kind, fixture, List.of(createLine(fixture.material(), quantity))); }

    /**
     * 创建有界多行订单。
     * @param kind 类别
     * @param fixture 固定交易方与仓库
     * @param lines 固定明细
     * @return 保存结果
     */
    private OrderResult create(TradeOrder.Kind kind, Fixture fixture, List<CreateOrderCommand.Line> lines) {
        var result = gateway.execute(CreateOrderCommand.builder().subjectId(subject).idempotencyKey(UUID.randomUUID().toString()).kind(kind)
                .partyId(kind == TradeOrder.Kind.PURCHASE ? fixture.supplier() : fixture.customer()).warehouseId(fixture.warehouse()).lines(lines).build()).data();
        orderIds.add(result.getId().toString()); return result;
    }

    /**
     * 构建固定单价的输入行。
     * @param material 物料
     * @param quantity 订购数量文本
     * @return 创建行
     */
    private CreateOrderCommand.Line createLine(UUID material, String quantity) { return CreateOrderCommand.Line.builder().materialId(material).quantity(new BigDecimal(quantity)).unitPrice(BigDecimal.ONE).build(); }

    /**
     * 为首行创建新的幂等履约请求。
     * @param order 当前订单快照
     * @param quantity 当次数量文本
     * @return 履约命令
     */
    private FulfillOrderCommand command(OrderResult order, String quantity) {
        return command(order, List.of(FulfillOrderCommand.Line.builder().orderLineId(order.getLines().getFirst().getId()).quantity(new BigDecimal(quantity)).build()), order.getKind());
    }

    /**
     * 从观察版本构建多行请求。
     * @param order 订单快照
     * @param lines 本次明细
     * @param kind 路由类别
     * @return 履约命令
     */
    private FulfillOrderCommand command(OrderResult order, List<FulfillOrderCommand.Line> lines, TradeOrder.Kind kind) {
        return FulfillOrderCommand.builder().subjectId(subject).idempotencyKey(UUID.randomUUID().toString()).kind(kind)
                .orderId(order.getId()).version(order.getVersion()).lines(lines).build();
    }

    /**
     * 通过真实命令入口履约。
     * @param command 履约请求
     * @return 提交或重放结果
     */
    private FulfillmentResult post(FulfillOrderCommand command) { return gateway.execute(command).data(); }

    /**
     * 检查精确库存数量。
     * @param fixture 库存维度
     * @param expected 期望数量
     */
    private void assertStock(Fixture fixture, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(balances.find(fixture.material(), fixture.warehouse()).orElseThrow().getQuantity().value()));
    }

    /**
     * 核对单据履约批次及对应库存流水数。
     * @param order 订单
     * @param postings 批次数
     * @param movements 流水数
     */
    private void assertPostingCount(OrderResult order, int postings, int movements) {
        assertEquals(postings, jdbc.queryForObject("SELECT COUNT(*) FROM erp_fulfillment WHERE order_id = ?", Integer.class, order.getId().toString()));
        assertEquals(movements, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_movement WHERE order_id = ?", Integer.class, order.getId().toString()));
    }

    /**
     * 核对本测试事件与幂等回执。
     * @param events 事件数
     * @param receipts 回执数
     */
    private void assertCounts(int events, int receipts) {
        assertEquals(events, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, subject));
        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE scope LIKE ?", Integer.class, "trade:%:" + subject));
    }

    /**
     * 验证业务冲突。
     * @param operation 预期冲突操作
     */
    private void assertConflict(Runnable operation) { assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, operation::run).getErrorCode()); }

    /**
     * 就绪屏障后发起独立事务，显式传播trace并恢复线程。
     * @param command 履约请求
     * @param ready 就绪屏障
     * @return 成功履约UUID，冲突为空
     */
    private UUID compete(FulfillOrderCommand command, CountDownLatch ready) {
        TraceIdContext.setTraceId(subject);
        try {
            ready.countDown(); assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
            return post(command).getPostingId();
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode()); return null;
        } finally { TraceIdContext.clear(); }
    }
}
