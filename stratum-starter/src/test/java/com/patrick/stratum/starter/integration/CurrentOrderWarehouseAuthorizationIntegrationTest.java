package com.patrick.stratum.starter.integration;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.auth.CurrentAuthorizationApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.CurrentOrderWarehouseAuthorizationApplicationService;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AccountOrderWarehouseAccess;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.WarehouseScope;
import com.patrick.stratum.domain.trade.TradeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 原订单仓库应用入口验证真实范围、独立API资格、可信主体及查询/事务故障关闭。 */
class CurrentOrderWarehouseAuthorizationIntegrationTest extends TradeOrderScopeIntegrationTest {
    @Autowired private AccountOrderWarehouseAccess access;
    @Autowired private AccountPermissions permissions;

    /**
     * 默认无范围404，指定/ALL/撤回实时可见；不要求读取API，未知和错误类别同样404。
     * @throws Exception HTTP失败
     */
    @Test
    void checksActualOrderWarehouseWithoutRequiringReadApi() throws Exception {
        var service = service(user);
        for (var kind : TradeOrder.Kind.values()) error(CommonErrorCode.NOT_FOUND, () -> service.require(kind, fixtures.get(kind).getFirst().getId()));
        configure(role, WarehouseScope.custom(Set.of(first)));
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", 1, "name", "订单范围", "permissions", Set.of())), 200);
        for (var kind : TradeOrder.Kind.values()) {
            var allowed = fixtures.get(kind).getFirst(); var hidden = fixtures.get(kind).getLast();
            assertDoesNotThrow(() -> service.require(kind, allowed.getId()));
            error(CommonErrorCode.NOT_FOUND, () -> service.require(kind, hidden.getId()));
            assertEquals(403, request("GET", path(kind) + "/" + allowed.getId(), token, null).statusCode());
        }
        configure(role, WarehouseScope.all());
        for (var kind : TradeOrder.Kind.values()) assertDoesNotThrow(() -> service.require(kind, fixtures.get(kind).getLast().getId()));
        var purchase = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst();
        error(CommonErrorCode.NOT_FOUND, () -> service.require(TradeOrder.Kind.SALE, purchase.getId()));
        error(CommonErrorCode.NOT_FOUND, () -> service.require(TradeOrder.Kind.PURCHASE, UUID.randomUUID()));
        configure(role, WarehouseScope.custom(Set.of()));
        error(CommonErrorCode.NOT_FOUND, () -> service.require(purchase.getKind(), purchase.getId()));
    }

    /** 停用和未知当前账号401，直接端口也拒绝，查询在活动写事务内继续失败。 */
    @Test
    void rejectsDisabledMissingAndTransactionalIdentity() {
        configure(role, WarehouseScope.all()); var order = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst();
        assertDoesNotThrow(() -> service(user).require(order.getKind(), order.getId()));
        assertThrows(IllegalStateException.class, () -> work.execute(() -> { service(user).require(order.getKind(), order.getId()); return null; }));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        error(CommonErrorCode.UNAUTHORIZED, () -> service(user).require(order.getKind(), order.getId()));
        assertFalse(access.permits(user, order.getKind(), order.getId(), "order-disabled"));
        error(CommonErrorCode.UNAUTHORIZED, () -> service(UUID.randomUUID()).require(order.getKind(), order.getId()));
        assertFalse(access.permits(UUID.randomUUID(), order.getKind(), order.getId(), "order-missing"));
    }

    /** 当前身份与trace由服务器传递；匿名/状态故障先拒绝，范围故障不得被吞掉或缓存。 */
    @Test
    void passesTrustedSubjectAndFailsClosedBeforeOrDuringTargetLookup() {
        var order = fixtures.get(TradeOrder.Kind.PURCHASE).getFirst(); var calls = new AtomicInteger();
        var current = new CurrentAuthorizationApplicationService(identity(user), (id, trace) -> Optional.of(new AccountPermissions.Snapshot(user, true, Set.of())));
        AccountOrderWarehouseAccess probe = (id, kind, target, trace) -> {
            assertEquals(user, id); assertEquals(order.getKind(), kind); assertEquals(order.getId(), target);
            assertEquals("current-order-warehouse", trace); calls.incrementAndGet(); return false;
        };
        var previous = TraceIdContext.getTraceId(); TraceIdContext.setTraceId("current-order-warehouse");
        try {
            var denied = new CurrentOrderWarehouseAuthorizationApplicationService(current, probe);
            error(CommonErrorCode.NOT_FOUND, () -> denied.require(order.getKind(), order.getId()));
            error(CommonErrorCode.NOT_FOUND, () -> denied.require(order.getKind(), order.getId())); assertEquals(2, calls.get());
            var anonymous = new CurrentAuthorizationApplicationService(() -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); }, permissions);
            error(CommonErrorCode.UNAUTHORIZED, () -> new CurrentOrderWarehouseAuthorizationApplicationService(anonymous, probe).require(order.getKind(), order.getId()));
            var stateFailure = new CurrentAuthorizationApplicationService(identity(user), (id, trace) -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); });
            error(CommonErrorCode.INTERNAL_ERROR, () -> new CurrentOrderWarehouseAuthorizationApplicationService(stateFailure, probe).require(order.getKind(), order.getId()));
            assertEquals(2, calls.get());
            var unavailable = new CurrentOrderWarehouseAuthorizationApplicationService(current, (id, kind, target, trace) -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); });
            error(CommonErrorCode.INTERNAL_ERROR, () -> unavailable.require(order.getKind(), order.getId()));
            assertEquals("current-order-warehouse", TraceIdContext.getTraceId().orElseThrow());
        } finally { if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow()); else TraceIdContext.clear(); }
    }

    /**
     * 用服务器身份替身和生产权限端口装配应用入口。
     * @param account 当前账号
     * @return 原订单仓库检查
     */
    private CurrentOrderWarehouseAuthorizationApplicationService service(UUID account) {
        return new CurrentOrderWarehouseAuthorizationApplicationService(new CurrentAuthorizationApplicationService(identity(account), permissions), access);
    }

    /**
     * 构造固定可信身份，不接受HTTP自报主体。
     * @param account 当前账号
     * @return 身份端口替身
     */
    private CurrentIdentity identity(UUID account) { return () -> new CurrentIdentity.Snapshot(account, UUID.randomUUID(), 0); }
}
