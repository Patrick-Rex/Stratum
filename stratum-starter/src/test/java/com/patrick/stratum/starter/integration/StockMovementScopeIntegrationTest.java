package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.inventory.StockBalanceRepository;
import com.patrick.stratum.domain.inventory.StockMovement;
import com.patrick.stratum.domain.inventory.StockMovementRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.inventory.StockMovementQuery;
import com.patrick.stratum.query.inventory.StockMovementQueryHandler;
import com.patrick.stratum.query.inventory.StockMovementView;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 库存流水通过真实余额仓库过滤，验证来源不可越权、总数分页及实时角色并集。 */
class StockMovementScopeIntegrationTest extends InventoryScopeIntegrationTest {
    @Autowired private StockBalanceRepository balances;
    @Autowired private StockMovementRepository movements;
    private InventorySourceFixture sources;
    private StockMovement allowed;
    private StockMovement next;
    private StockMovement hidden;
    private final Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    /** 创建第一仓库两批收货与第二仓库出库，所有来源均满足真实订单履约外键。 */
    @BeforeEach
    void prepareMovements() {
        sources = new InventorySourceFixture(jdbc, work);
        var source = new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        allowed = movement(first, source, StockMovement.Direction.RECEIPT);
        next = movement(first, new StockMovement.Source(source.orderId(), UUID.randomUUID(), source.orderLineId()), StockMovement.Direction.RECEIPT);
        hidden = movement(second, new StockMovement.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), StockMovement.Direction.ISSUE);
    }

    /** 在共用夹具清理余额和仓库前移除自身流水及订单来源。 */
    @AfterEach
    void cleanMovements() {
        for (UUID id : balanceIds) jdbc.update("DELETE FROM erp_stock_movement WHERE balance_id=?", id.toString());
        if (sources != null) work.execute(() -> { sources.clean(); return null; });
    }

    /**
     * 默认空、指定、ALL与撤回均先于总数分页；所有来源定位字段都不能读取另一仓库。
     * @throws Exception HTTP失败
     */
    @Test
    void filtersHistoryCountsPagesAndEverySourceIdentifier() throws Exception {
        assertEquals(0, read(1, 1, List.of()).getTotal());
        configure(role, WarehouseScope.custom(Set.of(first)));
        var page = read(1, 1, List.of()); assertEquals(2, page.getTotal()); assertEquals(1, page.getItems().size());
        assertEquals(2, read(2, 1, List.of()).getTotal());
        assertNotEquals(page.getItems().getFirst().id(), read(2, 1, List.of()).getItems().getFirst().id());
        assertTrue(read(3, 1, List.of()).getItems().isEmpty());
        for (var filter : List.of(eq("id", hidden.getId()), eq("balanceId", hidden.getBalanceId()),
                eq("orderId", hidden.getSource().orderId()), eq("postingId", hidden.getSource().postingId()), eq("orderLineId", hidden.getSource().orderLineId()))) {
            assertEquals(0, read(1, 20, List.of(filter)).getTotal());
        }
        var visible = read(1, 20, List.of(eq("orderId", allowed.getSource().orderId())));
        assertEquals(Set.of(allowed.getId(), next.getId()), visible.getItems().stream().map(StockMovementQuery.Row::id).collect(java.util.stream.Collectors.toSet()));
        String path = "/api/erp/stock-movements?balanceId=" + hidden.getBalanceId();
        assertEquals(0, data(request("GET", path, token, null), 200).path("total").asLong());
        configure(role, WarehouseScope.all()); assertEquals(3, read(1, 20, List.of()).getTotal());
        assertEquals(1, data(request("GET", path, token, null), 200).path("total").asLong());
        configure(role, WarehouseScope.custom(Set.of()));
        assertEquals(0, data(request("GET", path, token, null), 200).path("total").asLong());
        assertEquals(1, data(request("GET", path, accessToken(), null), 200).path("total").asLong());
    }

    /**
     * 重叠角色只计算一次流水，去除成员和停用即时失去对应仓库历史。
     * @throws Exception HTTP失败
     */
    @Test
    void evaluatesLiveRoleUnionAndEnabledAccount() throws Exception {
        UUID other = createRole("movement-union-" + UUID.randomUUID(), "流水并集");
        configure(role, WarehouseScope.custom(Set.of(first))); configure(other, WarehouseScope.custom(Set.of(first, second)));
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role, other)); return null; });
        assertEquals(3, read(1, 20, List.of()).getTotal());
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        assertEquals(2, read(1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString()); assertEquals(0, read(1, 20, List.of()).getTotal());
        jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString());
        jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role.toString()); assertEquals(0, read(1, 20, List.of()).getTotal());
    }

    /** 匿名或畸形主体拒绝，自报范围无效，事务和ORM故障不能绕过过滤。 */
    @Test
    void failsClosedForContextTransactionAndStorageErrors() {
        var query = StockMovementQuery.create(1, 20, List.of(), List.of());
        error(CommonErrorCode.UNAUTHORIZED, () -> queries.execute(query, QueryContext.anonymous("movement-anonymous")));
        error(CommonErrorCode.FORBIDDEN, () -> queries.execute(query, new QueryContext.Snapshot("movement-invalid", Optional.of("1-1-1-1-1"), Set.of("all"))));
        assertEquals(0, queries.execute(query, new QueryContext.Snapshot("movement-missing", Optional.of(UUID.randomUUID().toString()), Set.of("all"))).getTotal());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> queries.execute(query, context)));
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(StockMovementView.class)).thenThrow(new EasyQueryException("sensitive-movement-scope"));
        error(CommonErrorCode.INTERNAL_ERROR, () -> new StockMovementQueryHandler(database).handle(query, context));
    }

    /**
     * 创建具有真实来源及余额版本的流水。
     * @param warehouse 所属仓库
     * @param source 订单履约来源
     * @param direction 收发方向
     * @return 不可变流水
     */
    private StockMovement movement(UUID warehouse, StockMovement.Source source, StockMovement.Direction direction) {
        var amount = new Quantity(new BigDecimal("0.125")); sources.prepare(source, material, warehouse, direction, amount);
        return work.execute(() -> {
            var balance = balances.find(material, warehouse).orElseThrow();
            if (direction == StockMovement.Direction.RECEIPT) balance.receive(amount); else balance.issue(amount);
            balances.save(balance);
            var value = StockMovement.restore(UUID.randomUUID(), balance.getId(), source, direction, amount, balance.getQuantity(), balance.getVersion(), occurredAt);
            movements.append(value); return value;
        });
    }

    /**
     * 以本用例精确时间隔离测试数据，额外条件继续与强制仓库范围相交。
     * @param page 页码
     * @param size 页大小
     * @param extra 附加条件
     * @return 流水页
     */
    private QueryResult<StockMovementQuery.Row> read(int page, int size, List<QuerySpec.FilterInput> extra) {
        var filters = new ArrayList<>(extra); filters.add(new QuerySpec.FilterInput("occurredAt", QuerySpec.Operator.EQ, occurredAt, null));
        return queries.execute(StockMovementQuery.create(page, size, List.of("occurredAt,asc"), filters), context);
    }
}
