package com.patrick.stratum.starter.integration;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.auth.CurrentAuthorizationApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.Permission;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/** 实时鉴权经真实管理接口和权限查询验证；用显式可信身份夹具隔离本节点尚未接入的业务HTTP。 */
class CurrentAuthorizationIntegrationTest extends AdministrationHttpIntegrationTest {
    @Autowired private AccountPermissions permissions;
    @Autowired private CurrentAuthorizationApplicationService configured;
    @Autowired private UnitOfWork work;

    /**
     * 同一身份逐次读取授权和撤回，不依赖重新创建服务或刷新身份快照。
     * @throws Exception HTTP失败
     */
    @Test
    void observesLiveRoleChangesWithoutCachingAuthorization() throws Exception {
        UUID user = account(); UUID role = createRole("live-" + UUID.randomUUID(), "实时授权");
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        var service = service(user);
        error(CommonErrorCode.FORBIDDEN, () -> service.requireApi(PermissionCatalog.INVENTORY_READ));
        var grants = Set.of(PermissionCatalog.INVENTORY_READ.permission(), PermissionCatalog.INVENTORY_FEATURE.permission());
        configure(role, 0, grants);
        var snapshot = service.requireApi(PermissionCatalog.INVENTORY_READ);
        assertEquals(user, snapshot.userId()); assertEquals(grants, snapshot.permissions());
        error(CommonErrorCode.FORBIDDEN, () -> service.requireApi(PermissionCatalog.SALES_READ));
        configure(role, 1, Set.of());
        error(CommonErrorCode.FORBIDDEN, () -> service.requireApi(PermissionCatalog.INVENTORY_READ));
        assertEquals(grants, snapshot.permissions());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.permissions().clear());
    }

    /**
     * 普通账号没有默认权限，功能条目不放行API；未知和停用账号拒绝当前权限读取。
     * @throws Exception HTTP失败
     */
    @Test
    void separatesFeaturePermissionsAndInvalidAccounts() throws Exception {
        UUID user = account(); var service = service(user);
        assertTrue(service.current().permissions().isEmpty());
        error(CommonErrorCode.FORBIDDEN, () -> service.requireApi(PermissionCatalog.MATERIAL_READ));
        UUID role = createRole("feature-" + UUID.randomUUID(), "功能展示");
        configure(role, 0, Set.of(PermissionCatalog.INVENTORY_FEATURE.permission()));
        data(request("PUT", "/api/auth/accounts/" + user + "/roles", accessToken(), Map.of("version", 0, "roleIds", Set.of(role))), 200);
        assertEquals(Set.of("erp.inventory"), service.current().codes(Permission.Type.FEAT));
        error(CommonErrorCode.FORBIDDEN, () -> service.requireApi(PermissionCatalog.INVENTORY_READ));
        assertThrows(IllegalArgumentException.class, () -> service.requireApi(PermissionCatalog.INVENTORY_FEATURE));
        data(request("PATCH", "/api/auth/accounts/" + user + "/status", accessToken(), Map.of("version", 1, "enabled", false)), 200);
        error(CommonErrorCode.UNAUTHORIZED, service::current);
        error(CommonErrorCode.UNAUTHORIZED, () -> service(UUID.randomUUID()).current());
        error(CommonErrorCode.UNAUTHORIZED, configured::current);
    }

    /**
     * 真实查询网关拒绝写事务内的隐式读取，避免事务快照缓存旧授权。
     * @throws Exception HTTP失败
     */
    @Test
    void rejectsReadingPermissionsInsideWriteTransactions() throws Exception {
        UUID user = account();
        assertThrows(IllegalStateException.class, () -> work.execute(() -> service(user).current()));
    }

    /** 缺少可信身份时不读取权限；错误主体、存储故障和相同编码但错误类型均不得放行。 */
    @Test
    void failsClosedAndPreservesTrustedIdentityAndTrace() {
        UUID user = UUID.randomUUID(); var identity = identity(user); var calls = new AtomicInteger();
        AccountPermissions lookup = (actual, trace) -> {
            assertEquals(user, actual); assertEquals("current-authorization", trace); calls.incrementAndGet();
            return Optional.of(new AccountPermissions.Snapshot(user, true,
                    Set.of(new Permission("erp.inventory.read", Permission.Type.FEAT))));
        };
        var previousTrace = TraceIdContext.getTraceId(); TraceIdContext.setTraceId("current-authorization");
        try {
            var wrongType = new CurrentAuthorizationApplicationService(identity, lookup);
            error(CommonErrorCode.FORBIDDEN, () -> wrongType.requireApi(PermissionCatalog.INVENTORY_READ));
            error(CommonErrorCode.FORBIDDEN, () -> wrongType.requireApi(PermissionCatalog.INVENTORY_READ));
            assertEquals(2, calls.get());
            var anonymous = new CurrentAuthorizationApplicationService(() -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); }, lookup);
            error(CommonErrorCode.UNAUTHORIZED, anonymous::current); assertEquals(2, calls.get());
            var mismatch = new CurrentAuthorizationApplicationService(identity,
                    (id, trace) -> Optional.of(new AccountPermissions.Snapshot(UUID.randomUUID(), true, PermissionCatalog.permissions())));
            error(CommonErrorCode.UNAUTHORIZED, mismatch::current);
            var unavailable = new CurrentAuthorizationApplicationService(identity,
                    (id, trace) -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); });
            error(CommonErrorCode.INTERNAL_ERROR, () -> unavailable.requireApi(PermissionCatalog.INVENTORY_READ));
            assertEquals("current-authorization", TraceIdContext.getTraceId().orElseThrow());
        } finally {
            if (previousTrace.isPresent()) TraceIdContext.setTraceId(previousTrace.orElseThrow()); else TraceIdContext.clear();
        }
    }

    /**
     * 创建随机普通账号并沿用父夹具清理。
     * @return 本测试账号UUID
     * @throws Exception HTTP失败
     */
    private UUID account() throws Exception { return createAccount("authz-" + UUID.randomUUID(), UUID.randomUUID().toString()); }

    /**
     * 通过管理接口替换本测试角色权限。
     * @param role 角色UUID
     * @param version 当前角色版本
     * @param grants 完整授权集合
     * @throws Exception HTTP失败
     */
    private void configure(UUID role, long version, Set<Permission> grants) throws Exception {
        data(request("PUT", "/api/auth/roles/" + role, accessToken(), Map.of("version", version, "name", "实时权限", "permissions", grants)), 200);
    }

    /**
     * 用测试可信身份和生产权限查询构造被测服务，不使用客户端主体字段。
     * @param user 测试账号UUID
     * @return 独立应用入口
     */
    private CurrentAuthorizationApplicationService service(UUID user) { return new CurrentAuthorizationApplicationService(identity(user), permissions); }

    /**
     * 构造当前身份端口的可信测试替身。
     * @param user 测试主体
     * @return 固定身份端口
     */
    private CurrentIdentity identity(UUID user) { return () -> new CurrentIdentity.Snapshot(user, UUID.randomUUID(), 0); }

    /**
     * 仅核对安全错误码，不输出凭据或异常内部信息。
     * @param expected 预期错误码
     * @param action 被拒绝操作
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(StratumException.class, action).getErrorCode());
    }
}
