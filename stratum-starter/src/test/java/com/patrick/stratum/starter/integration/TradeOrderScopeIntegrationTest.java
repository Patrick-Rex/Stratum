package com.patrick.stratum.starter.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.domain.auth.RoleWarehouseScopeRepository;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.inventory.Quantity;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.domain.trade.OrderLine;
import com.patrick.stratum.domain.trade.TradeOrder;
import com.patrick.stratum.domain.trade.TradeOrderRepository;
import com.patrick.stratum.domain.trade.UnitPrice;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.spec.QuerySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 订单读取范围夹具：真实普通账号及双仓库采购销售订单，角色默认没有仓库范围。 */
abstract class TradeOrderScopeIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired protected QueryGateway queries;
    @Autowired protected TradeOrderRepository orders;
    @Autowired protected AuthorizationAdministrationRepository administration;
    @Autowired protected UnitOfWork work;
    @Autowired private MasterDataRepository masters;
    @Autowired private RoleWarehouseScopeRepository scopes;
    protected final Map<TradeOrder.Kind, List<TradeOrder>> fixtures = new EnumMap<>(TradeOrder.Kind.class);
    private final List<String> masterIds = new ArrayList<>();
    private final Set<UUID> configuredRoles = new HashSet<>();
    protected UUID user;
    protected UUID role;
    protected UUID first;
    protected UUID second;
    protected QueryContext context;
    protected String token;

    /**
     * 每种订单在第一仓库准备两份、第二仓库一份；账号只具备两类读取API权限。
     * @throws Exception HTTP失败
     */
    @BeforeEach
    void prepareOrdersAndIdentity() throws Exception {
        UUID material = master(MasterData.Kind.MATERIAL); first = master(MasterData.Kind.WAREHOUSE); second = master(MasterData.Kind.WAREHOUSE);
        for (var kind : TradeOrder.Kind.values()) {
            UUID party = master(kind == TradeOrder.Kind.PURCHASE ? MasterData.Kind.SUPPLIER : MasterData.Kind.CUSTOMER);
            var values = new ArrayList<TradeOrder>(); fixtures.put(kind, values);
            for (UUID warehouse : List.of(first, first, second)) {
                var value = TradeOrder.create(kind, party, warehouse, List.of(OrderLine.create(material, new Quantity(BigDecimal.ONE), new UnitPrice(BigDecimal.ONE))));
                values.add(value); work.execute(() -> { orders.save(value); return null; });
            }
        }
        String username = "order-scope-" + UUID.randomUUID(), password = UUID.randomUUID().toString();
        user = createAccount(username, password); role = createRole("order-scope-" + UUID.randomUUID(), "订单范围");
        work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, Set.of(role)); return null; });
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 0, "name", "订单范围", "permissions",
                Set.of(PermissionCatalog.PURCHASE_READ.permission(), PermissionCatalog.SALES_READ.permission()))), 200);
        token = login(username, password).accessToken();
        context = new QueryContext.Snapshot("order-scope", Optional.of(user.toString()), Set.of());
    }

    /** 解除自身角色范围外键后按引用顺序清理订单来源和主数据，父夹具清理账号角色。 */
    @AfterEach
    void cleanOrders() {
        if (role != null) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", role.toString());
        for (UUID id : configuredRoles) jdbc.update("DELETE FROM auth_role_warehouse WHERE role_id=?", id.toString());
        for (var values : fixtures.values()) for (var order : values) {
            String id = order.getId().toString();
            jdbc.update("DELETE FROM erp_fulfillment_line WHERE order_id=?", id);
            jdbc.update("DELETE FROM erp_fulfillment WHERE order_id=?", id);
            jdbc.update("DELETE FROM erp_order_line WHERE order_id=?", id);
            jdbc.update("DELETE FROM erp_trade_order WHERE id=?", id);
        }
        for (String id : masterIds) jdbc.update("DELETE FROM erp_master_data WHERE id=?", id);
    }

    /**
     * 保存当前角色完整范围；不撤销会话，以验证实时读取。
     * @param target 角色
     * @param scope 完整范围
     */
    protected void configure(UUID target, WarehouseScope scope) {
        work.execute(() -> {
            administration.lock(); var value = scopes.findByRoleId(target.toString()).orElseThrow();
            value.replace(scope); scopes.save(value); configuredRoles.add(target); return null;
        });
    }

    /**
     * 构造白名单精确条件。
     * @param field 字段名
     * @param value 类型化值
     * @return 精确过滤
     */
    protected QuerySpec.FilterInput eq(String field, Object value) { return new QuerySpec.FilterInput(field, QuerySpec.Operator.EQ, value, null); }

    /**
     * 获取采购或销售HTTP集合路径。
     * @param kind 类别
     * @return 路径
     */
    protected String path(TradeOrder.Kind kind) { return "/api/erp/orders/" + kind; }

    /**
     * 验证无底层敏感原因的业务错误。
     * @param expected 预期错误
     * @param action 触发操作
     */
    protected void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }

    /**
     * 创建并登记独立主数据。
     * @param kind 类别
     * @return 标识
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "订单范围", kind == MasterData.Kind.MATERIAL ? "件" : null);
        masterIds.add(value.getId()); work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }
}
