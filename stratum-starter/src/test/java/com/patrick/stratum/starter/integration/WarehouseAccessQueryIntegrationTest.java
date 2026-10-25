package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AccountManagementView;
import com.patrick.stratum.query.auth.WarehouseAccessQuery;
import com.patrick.stratum.query.auth.WarehouseAccessQueryHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实数据库验证当前主体仓库权限、ALL/CUSTOM角色并集、实时变更与固定查询边界。 */
class WarehouseAccessQueryIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> roleIds = new ArrayList<>();
    private final List<String> userIds = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /** 按引用顺序只删除本测试账号、角色及仓库，不触碰共享目录或既有管理员角色。 */
    @AfterEach
    void clean() {
        for (String id : userIds) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", id); jdbc.update("DELETE FROM auth_user WHERE id=?", id);
        }
        for (String id : roleIds) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", id); jdbc.update("DELETE FROM auth_role WHERE id=?", id);
        }
        if (!masterIds.isEmpty()) jdbc.batchUpdate("DELETE FROM erp_master_data WHERE id=?", masterIds.stream().map(id -> new Object[]{id}).toList());
    }

    /** 无角色默认拒绝，多个指定角色取并集，配置及成员撤回在同一查询上下文立即可见。 */
    @Test
    void unionsCurrentRoleScopesAndObservesLiveChanges() {
        UUID user = account(), first = warehouse(), second = warehouse(), excluded = warehouse();
        assertFalse(access(user, first)); UUID left = role(), right = role(); assign(user, Set.of(left, right));
        assertFalse(access(user, first)); configure(left, WarehouseScope.custom(Set.of(first)));
        configure(right, WarehouseScope.custom(Set.of(first, second)));
        assertTrue(access(user, first)); assertTrue(access(user, second)); assertFalse(access(user, excluded));
        configure(left, WarehouseScope.custom(Set.of())); assertTrue(access(user, first));
        assign(user, Set.of(left)); assertFalse(access(user, first)); assertFalse(access(user, second));
        configure(left, WarehouseScope.all()); assertTrue(access(user, excluded)); assertTrue(access(user, UUID.randomUUID()));
        configure(left, WarehouseScope.custom(Set.of())); assertFalse(access(user, excluded));
    }

    /** 管理员通过真实角色ALL访问，停用、删除成员及缺失账号均不能沿用先前范围。 */
    @Test
    void requiresEnabledExistingAccountAndActualAdministratorMembership() {
        UUID user = account(); UUID admin = work.execute(() -> {
            administration.lock(); roles.registerCatalog();
            var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                var created = AuthorizationRole.administrator(); roleIds.add(created.getId()); roles.save(created); return created;
            });
            return UUID.fromString(role.getId());
        });
        assign(user, Set.of(admin)); UUID target = UUID.randomUUID(); assertTrue(access(user, target));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString()); assertFalse(access(user, target));
        jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString()); assertTrue(access(user, target));
        assign(user, Set.of()); assertFalse(access(user, target)); assertFalse(access(UUID.randomUUID(), target));
    }

    /** 查询主体必须精确绑定服务器上下文，自报权限、匿名和活动事务均不能扩大范围。 */
    @Test
    void bindsTrustedSubjectAndRejectsAnonymousOrTransactionalReads() {
        UUID user = account(), target = warehouse(); var query = WarehouseAccessQuery.create(user, target);
        error(CommonErrorCode.UNAUTHORIZED, () -> gateway.execute(query, QueryContext.anonymous("warehouse-query")));
        for (String actor : List.of(UUID.randomUUID().toString(), "' OR 1=1", "administrator")) {
            var forged = new QueryContext.Snapshot("warehouse-query", Optional.of(actor), Set.of("auth.role.manage"));
            error(CommonErrorCode.FORBIDDEN, () -> gateway.execute(query, forged));
        }
        var selfReported = new QueryContext.Snapshot("warehouse-query", Optional.of(user.toString()), Set.of("auth.role.manage", "auth.administration"));
        assertEquals(List.of(false), gateway.execute(query, selfReported).getItems());
        assertThrows(IllegalStateException.class, () -> work.execute(() -> gateway.execute(query, context(user))));
        assertThrows(NullPointerException.class, () -> WarehouseAccessQuery.create(null, target));
        assertThrows(NullPointerException.class, () -> WarehouseAccessQuery.create(user, null));
    }

    /** 两个各1024仓库的角色仍允许访问完整2048并集，不把单配置上限误用到用户并集。 */
    @Test
    void evaluatesUnionBeyondSingleConfigurationCapacityWithoutTruncation() {
        UUID user = account(), left = role(), right = role();
        var ids = IntStream.range(0, WarehouseScope.MAX_WAREHOUSES * 2).mapToObj(index -> UUID.randomUUID()).toList();
        masterIds.addAll(ids.stream().map(UUID::toString).toList());
        work.execute(() -> {
            jdbc.batchUpdate("INSERT INTO erp_master_data(id,kind,code,name) VALUES(?,'WAREHOUSE',?,'并集上界')",
                    ids.stream().map(id -> new Object[]{id.toString(), id.toString()}).toList()); return null;
        });
        configure(left, WarehouseScope.custom(ids.subList(0, WarehouseScope.MAX_WAREHOUSES).stream().collect(Collectors.toSet())));
        configure(right, WarehouseScope.custom(ids.subList(WarehouseScope.MAX_WAREHOUSES, ids.size()).stream().collect(Collectors.toSet())));
        assign(user, Set.of(left, right));
        assertTrue(access(user, ids.getFirst())); assertTrue(access(user, ids.get(WarehouseScope.MAX_WAREHOUSES)));
        assertTrue(access(user, ids.getLast())); assertFalse(access(user, UUID.randomUUID()));
        assign(user, Set.of(left)); assertFalse(access(user, ids.getLast())); assertTrue(access(user, ids.getFirst()));
    }

    /** 缺失配置拒绝访问，底层ORM错误不能变成允许或暴露SQL原因。 */
    @Test
    void deniesMissingScopeAndFailsClosedOnStorageErrors() {
        UUID user = account(), role = role(), target = warehouse(); configure(role, WarehouseScope.all()); assign(user, Set.of(role));
        assertTrue(access(user, target)); jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role.toString());
        assertFalse(access(user, target));
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(AccountManagementView.class)).thenThrow(new EasyQueryException("sensitive-warehouse-access"));
        var handler = new WarehouseAccessQueryHandler(database);
        error(CommonErrorCode.INTERNAL_ERROR, () -> handler.handle(WarehouseAccessQuery.create(user, target), context(user)));
    }

    /**
     * 创建普通测试账号，不创建会话或默认授权。
     * @return 账号UUID
     */
    private UUID account() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("warehouse-query-" + UUID.randomUUID(), passwords.hash(raw), false); userIds.add(account.getId());
            work.execute(() -> { accounts.insert(account); return null; }); return UUID.fromString(account.getId());
        } finally { Arrays.fill(raw, '\0'); }
    }

    /**
     * 创建带默认空范围的角色。
     * @return 角色UUID
     */
    private UUID role() {
        var role = AuthorizationRole.create("warehouse-query-" + UUID.randomUUID(), "仓库查询"); roleIds.add(role.getId());
        work.execute(() -> { administration.lock(); roles.save(role); return null; }); return UUID.fromString(role.getId());
    }

    /**
     * 保存独立测试仓库。
     * @return 仓库UUID
     */
    private UUID warehouse() {
        var value = MasterData.create(MasterData.Kind.WAREHOUSE, UUID.randomUUID().toString(), "范围查询", null); masterIds.add(value.getId());
        work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 经写侧配置完整仓库范围。
     * @param role 角色UUID
     * @param scope 新范围
     */
    private void configure(UUID role, WarehouseScope scope) {
        work.execute(() -> {
            administration.lock(); var configuration = scopes.findByRoleId(role.toString()).orElseThrow();
            configuration.replace(scope); scopes.save(configuration); return null;
        });
    }

    /**
     * 精确替换测试账号角色关系。
     * @param user 账号UUID
     * @param roles 全部角色
     */
    private void assign(UUID user, Set<UUID> roles) { work.execute(() -> { administration.lock(); administration.replaceRoleIds(user, roles); return null; }); }

    /**
     * 通过正式查询网关读取单目标布尔结果。
     * @param user 服务器测试主体
     * @param warehouse 目标仓库UUID
     * @return 是否在范围内
     */
    private boolean access(UUID user, UUID warehouse) {
        var result = gateway.execute(WarehouseAccessQuery.create(user, warehouse), context(user));
        assertEquals(1, result.getTotal()); assertEquals(1, result.getItems().size()); return result.getItems().getFirst();
    }

    /**
     * 创建不携带自报权限的当前主体上下文。
     * @param user 当前账号UUID
     * @return 测试可信上下文
     */
    private QueryContext context(UUID user) { return new QueryContext.Snapshot("warehouse-query", Optional.of(user.toString()), Set.of()); }

    /**
     * 核对错误与脱敏原因，不输出敏感底层数据。
     * @param expected 预期安全错误
     * @param action 被测调用
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
