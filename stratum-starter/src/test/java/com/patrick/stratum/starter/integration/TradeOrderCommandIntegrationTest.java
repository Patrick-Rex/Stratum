package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.command.CommandGateway;
import com.patrick.stratum.application.trade.CreateOrderCommand;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.TradeOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实命令路由验证采购销售订单创建、结果重放与同事务事件回执。 */
class TradeOrderCommandIntegrationTest extends MariaDbIntegrationTest {
    private final CommandGateway gateway;
    private final MasterDataRepository masters;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final String subject = UUID.randomUUID().toString();
    private final List<String> masterIds = new ArrayList<>();

    /**
     * 装配真实命令入口及测试辅助依赖。
     * @param gateway 命令路由
     * @param masters 主数据仓储
     * @param unitOfWork 工作单元
     * @param jdbc 数据断言及清理客户端
     */
    @Autowired
    TradeOrderCommandIntegrationTest(CommandGateway gateway, MasterDataRepository masters, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.gateway = gateway; this.masters = masters; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 关联本测试事件与服务器主体，避免影响其他测试数据。 */
    @BeforeEach
    void bindTrace() { TraceIdContext.setTraceId(subject); }

    /** 采购销售均创建固定订单、精确金额、事件与回执，不提前改变库存。 */
    @Test
    void createsBothKindsWithEventsAndReceipts() {
        for (var kind : TradeOrder.Kind.values()) {
            var command = command(kind); var result = gateway.execute(command).data();
            assertEquals(kind, result.getKind()); assertEquals(command.getPartyId(), result.getPartyId());
            assertEquals(command.getWarehouseId(), result.getWarehouseId()); assertEquals(TradeOrder.Status.PENDING, result.getStatus());
            assertEquals(0, result.getVersion()); assertEquals(new BigDecimal("0.01"), result.getTotalAmount());
            assertEquals(2, result.getLines().size()); assertNotEquals(result.getLines().getFirst().getId(), result.getLines().getLast().getId());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM erp_order_line WHERE order_id = ?", Integer.class, result.getId().toString()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_stock_balance WHERE warehouse_id = ?", Integer.class, result.getWarehouseId().toString()));
        }
        assertCounts(2, 2);
        assertEquals(List.of("erp.purchase.created", "erp.sale.created"), jdbc.queryForList(
                "SELECT event_type FROM outbox_event WHERE trace_id = ? ORDER BY event_type", String.class, subject));
    }

    /** 同键重放保持订单及行UUID，异参同键拒绝，不再生成事件或回执。 */
    @Test
    void replaysCompleteResultAndRejectsChangedRequest() {
        var command = command(TradeOrder.Kind.PURCHASE);
        var first = gateway.execute(command).data(); var repeated = gateway.execute(command).data();
        assertEquals(first.getId(), repeated.getId()); assertEquals(first.getOrderNumber(), repeated.getOrderNumber());
        assertEquals(first.getTotalAmount(), repeated.getTotalAmount());
        assertEquals(first.getLines().getFirst().getId(), repeated.getLines().getFirst().getId());
        assertEquals(first.getLines().getFirst().getQuantity(), repeated.getLines().getFirst().getQuantity());
        var changed = CreateOrderCommand.builder().subjectId(subject).idempotencyKey(command.getIdempotencyKey()).kind(command.getKind())
                .partyId(command.getPartyId()).warehouseId(command.getWarehouseId()).lines(List.of(command.getLines().getFirst())).build();
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> gateway.execute(changed)).getErrorCode());
        assertCounts(1, 1);
    }

    /** 错误类别、未知主数据及非法数量不保存订单、事件或成功回执。 */
    @Test
    void rejectsBadReferencesAndQuantitiesWithoutSideEffects() {
        var command = command(TradeOrder.Kind.PURCHASE);
        var wrongParty = CreateOrderCommand.builder().subjectId(subject).idempotencyKey("wrong-party").kind(TradeOrder.Kind.SALE)
                .partyId(command.getPartyId()).warehouseId(command.getWarehouseId()).lines(command.getLines()).build();
        assertEquals(CommonErrorCode.NOT_FOUND, assertThrows(StratumException.class, () -> gateway.execute(wrongParty)).getErrorCode());
        var missing = CreateOrderCommand.builder().subjectId(subject).idempotencyKey("missing").kind(command.getKind())
                .partyId(UUID.randomUUID()).warehouseId(command.getWarehouseId()).lines(command.getLines()).build();
        assertEquals(CommonErrorCode.NOT_FOUND, assertThrows(StratumException.class, () -> gateway.execute(missing)).getErrorCode());
        var zero = CreateOrderCommand.Line.builder().materialId(command.getLines().getFirst().getMaterialId())
                .quantity(BigDecimal.ZERO).unitPrice(BigDecimal.ONE).build();
        var invalid = CreateOrderCommand.builder().subjectId(subject).idempotencyKey("zero").kind(command.getKind())
                .partyId(command.getPartyId()).warehouseId(command.getWarehouseId()).lines(List.of(zero)).build();
        assertEquals(CommonErrorCode.BAD_REQUEST, assertThrows(StratumException.class, () -> gateway.execute(invalid)).getErrorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE party_id = ?", Integer.class, command.getPartyId().toString()));
        assertCounts(0, 0);
    }

    /** 外层业务失败撤销全部写入，原幂等键重试可成功创建。 */
    @Test
    void rollsBackOrderLinesEventsAndReceipt() {
        var command = command(TradeOrder.Kind.SALE);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            gateway.execute(command); throw new IllegalStateException("测试回滚");
        }));
        assertCounts(0, 0);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_trade_order WHERE party_id = ?", Integer.class, command.getPartyId().toString()));
        gateway.execute(command); assertCounts(1, 1);
    }

    /** 删除本测试关联订单及事件回执，再释放主数据和线程上下文。 */
    @AfterEach
    void cleanOwnRows() {
        try {
            unitOfWork.execute(() -> {
                jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", subject);
                jdbc.update("DELETE FROM idempotency_receipt WHERE scope LIKE ?", "trade:%:" + subject);
                for (String id : masterIds) {
                    for (String orderId : jdbc.queryForList("SELECT id FROM erp_trade_order WHERE party_id = ?", String.class, id)) {
                        jdbc.update("DELETE FROM erp_order_line WHERE order_id = ?", orderId);
                        jdbc.update("DELETE FROM erp_trade_order WHERE id = ?", orderId);
                    }
                }
                for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
                return null;
            });
        } finally { TraceIdContext.clear(); }
    }

    /**
     * 创建拥有独立主数据的两行命令。
     * @param kind 订单类别
     * @return 创建命令
     */
    private CreateOrderCommand command(TradeOrder.Kind kind) {
        UUID material = master(MasterData.Kind.MATERIAL);
        var line = CreateOrderCommand.Line.builder().materialId(material).quantity(BigDecimal.ONE).unitPrice(new BigDecimal("0.005")).build();
        return CreateOrderCommand.builder().subjectId(subject).idempotencyKey(UUID.randomUUID().toString()).kind(kind)
                .partyId(master(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER))
                .warehouseId(master(MasterData.Kind.WAREHOUSE)).lines(List.of(line, line)).build();
    }

    /**
     * 保存独立测试主数据，不额外产生业务事件。
     * @param kind 主数据类别
     * @return 主数据UUID
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "订单命令测试", kind == MasterData.Kind.MATERIAL ? "个" : null);
        masterIds.add(value.getId()); unitOfWork.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 核对本测试产生的Outbox和回执数量。
     * @param events 预期事件数
     * @param receipts 预期成功回执数
     */
    private void assertCounts(int events, int receipts) {
        assertEquals(events, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, subject));
        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE scope LIKE ?", Integer.class, "trade:%:" + subject));
    }
}
