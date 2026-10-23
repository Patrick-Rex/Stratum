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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 完整命令链验证分批采购收货、销售出库和并发下的跨聚合原子性。 */
class TradeOrderFulfillmentIntegrationTest extends MariaDbIntegrationTest {
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
     */
    @Autowired
    TradeOrderFulfillmentIntegrationTest(CommandGateway gateway, MasterDataRepository masters, TradeOrderRepository orders,
            StockBalanceRepository balances, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.gateway = gateway; this.masters = masters; this.orders = orders; this.balances = balances;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc;
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

    /** 过量、未知行、重复行、错误类别和过期版本均不能增加履约次数。 */
    @Test
    void rejectsInvalidFulfillmentRequests() {
        var fixture = fixture(); var order = create(TradeOrder.Kind.PURCHASE, fixture, "1");
        assertConflict(() -> post(command(order, "1.01")));
        var unknown = FulfillOrderCommand.Line.builder().orderLineId(UUID.randomUUID()).quantity(BigDecimal.ONE).build();
        assertConflict(() -> post(command(order, List.of(unknown), order.getKind())));
        var line = command(order, "0.25").getLines().getFirst();
        assertThrows(IllegalArgumentException.class, () -> post(command(order, List.of(line, line), order.getKind())));
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
