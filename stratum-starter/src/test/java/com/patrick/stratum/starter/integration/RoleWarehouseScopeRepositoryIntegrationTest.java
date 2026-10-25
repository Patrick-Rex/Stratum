package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import com.patrick.stratum.infrastructure.persistence.auth.RoleRecord;
import com.patrick.stratum.infrastructure.persistence.auth.RoleWarehouseScopeRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实MariaDB验证独立仓库配置的当前读、原子替换、乐观并发、有界读取及故障关闭。 */
class RoleWarehouseScopeRepositoryIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private RoleWarehouseScopeRepository scopes;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private MasterDataRepository masters;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> roleIds = new ArrayList<>();
    private final List<String> masterIds = new ArrayList<>();

    /** 仅清理本测试角色及主数据，从属仓库配置随角色级联清理。 */
    @AfterEach
    void clean() {
        for (String id : roleIds) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", id);
            jdbc.update("DELETE FROM auth_role WHERE id=?", id);
        }
        if (!masterIds.isEmpty()) jdbc.batchUpdate("DELETE FROM erp_master_data WHERE id=?", masterIds.stream().map(id -> new Object[]{id}).toList());
    }

    /** 指定、全部和空配置完整往返，独立版本推进但角色名称/API权限版本保持原值。 */
    @Test
    void roundTripsWholeScopesAndKeepsRoleVersionIndependent() {
        String role = role(); UUID first = master(MasterData.Kind.WAREHOUSE), second = master(MasterData.Kind.WAREHOUSE);
        var configuration = load(role); assertEquals(WarehouseScope.custom(Set.of()), configuration.getScope());
        configuration.replace(WarehouseScope.custom(Set.of(first, second))); save(configuration);
        assertEquals(1L, configuration.getVersion()); assertEquals(Set.of(first, second), load(role).getScope().warehouseIds());
        configuration.replace(WarehouseScope.all()); save(configuration);
        assertEquals(WarehouseScope.all(), load(role).getScope()); assertEquals(2L, load(role).getVersion());
        configuration.replace(WarehouseScope.custom(Set.of())); save(configuration);
        assertEquals(3L, load(role).getVersion()); assertTrue(load(role).getScope().warehouseIds().isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_role WHERE id=?", Long.class, role));
        assertEquals("范围仓储", jdbc.queryForObject("SELECT name FROM auth_role WHERE id=?", String.class, role));
    }

    /** 旧版本和无效目标不能覆盖先前范围，外键异常在工作单元回滚后不遗留部分关联。 */
    @Test
    void rejectsStaleVersionsAndRollsBackInvalidReferences() {
        String role = role(); UUID allowed = master(MasterData.Kind.WAREHOUSE), wrongKind = master(MasterData.Kind.MATERIAL);
        var original = load(role); var stale = load(role);
        original.replace(WarehouseScope.custom(Set.of(allowed))); save(original);
        stale.replace(WarehouseScope.all()); error(CommonErrorCode.CONFLICT, () -> save(stale));
        for (UUID invalid : Set.of(UUID.randomUUID(), wrongKind)) {
            var replacement = load(role); replacement.replace(WarehouseScope.custom(Set.of(invalid)));
            error(CommonErrorCode.CONFLICT, () -> save(replacement));
            assertEquals(1L, load(role).getVersion()); assertEquals(Set.of(allowed), load(role).getScope().warehouseIds());
        }
    }

    /** 保存后的提交前失败整体回滚，原范围及版本不受影响。 */
    @Test
    void rollsBackScopeVersionAndRelationsTogether() {
        String role = role(); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        assertThrows(IllegalStateException.class, () -> work.execute(() -> {
            administration.lock(); var configuration = scopes.findByRoleId(role).orElseThrow();
            configuration.replace(WarehouseScope.custom(Set.of(warehouse))); scopes.save(configuration);
            throw new IllegalStateException("测试回滚");
        }));
        assertEquals(0L, load(role).getVersion()); assertTrue(load(role).getScope().warehouseIds().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_role_warehouse WHERE role_id=?", Integer.class, role));
    }

    /** 写事务外、伪造角色编码及缺失配置均拒绝；未知角色区分为空或保存404。 */
    @Test
    void requiresWriteTransactionAndExactRoleIdentity() {
        String role = role(); var configuration = load(role);
        assertThrows(IllegalStateException.class, () -> scopes.findByRoleId(role));
        assertThrows(IllegalStateException.class, () -> scopes.save(configuration));
        assertTrue(work.execute(() -> { administration.lock(); return scopes.findByRoleId(UUID.randomUUID().toString()); }).isEmpty());
        error(CommonErrorCode.NOT_FOUND, () -> save(RoleWarehouseScope.initial(UUID.randomUUID().toString(), "missing")));
        error(CommonErrorCode.CONFLICT, () -> save(RoleWarehouseScope.initial(role, "administrator")));
        jdbc.update("DELETE FROM auth_role_warehouse_scope WHERE role_id=?", role);
        error(CommonErrorCode.INTERNAL_ERROR, () -> load(role)); error(CommonErrorCode.INTERNAL_ERROR, () -> save(configuration));
    }

    /** 恰好1024个指定仓库完整读取，历史异常数据超界时失败而不截断。 */
    @Test
    void readsExactBoundAndRejectsOversizedStoredCollection() {
        String role = role();
        var ids = IntStream.range(0, WarehouseScope.MAX_WAREHOUSES + 1).mapToObj(index -> UUID.randomUUID()).toList();
        masterIds.addAll(ids.stream().map(UUID::toString).toList());
        work.execute(() -> {
            jdbc.batchUpdate("INSERT INTO erp_master_data(id,kind,code,name) VALUES(?,'WAREHOUSE',?,'范围上界')",
                    ids.stream().map(id -> new Object[]{id.toString(), id.toString()}).toList()); return null;
        });
        var maximum = ids.subList(0, WarehouseScope.MAX_WAREHOUSES).stream().collect(Collectors.toSet());
        var configuration = load(role); configuration.replace(WarehouseScope.custom(maximum)); save(configuration);
        assertEquals(maximum, load(role).getScope().warehouseIds());
        jdbc.update("INSERT INTO auth_role_warehouse(role_id,warehouse_id) VALUES(?,?)", role, ids.getLast().toString());
        error(CommonErrorCode.INTERNAL_ERROR, () -> load(role));
    }

    /**
     * 两份同版本配置竞争只能提交一份，版本只推进一次且集合不混合。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentReplacementsCommitOnlyOneCompleteCollection() throws Exception {
        String role = role(); UUID first = master(MasterData.Kind.WAREHOUSE), second = master(MasterData.Kind.WAREHOUSE);
        var left = load(role); left.replace(WarehouseScope.custom(Set.of(first)));
        var right = load(role); right.replace(WarehouseScope.custom(Set.of(second)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(executor.submit(() -> outcome(left)), executor.submit(() -> outcome(right)));
            var results = new ArrayList<Integer>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result == 200).count());
            assertEquals(1, results.stream().filter(result -> result == 409).count());
        }
        var current = load(role); assertEquals(1L, current.getVersion());
        assertTrue(Set.of(Set.of(first), Set.of(second)).contains(current.getScope().warehouseIds()));
    }

    /** MariaDB旧事务快照不能返回过期授权，快照冲突后必须整个工作单元重新读取。 */
    @Test
    void rejectsObsoleteSnapshotsAndReadsLatestAfterRetry() {
        String role = role();
        error(CommonErrorCode.CONFLICT, () -> work.execute(() -> {
            assertEquals(0L, jdbc.queryForObject("SELECT version FROM auth_role_warehouse_scope WHERE role_id=?", Long.class, role));
            CompletableFuture.runAsync(() -> {
                var replacement = load(role); replacement.replace(WarehouseScope.all()); save(replacement);
            }).orTimeout(10, TimeUnit.SECONDS).join();
            administration.lock(); return scopes.findByRoleId(role);
        }));
        assertEquals(WarehouseScope.all(), load(role).getScope()); assertEquals(1L, load(role).getVersion());
    }

    /** ORM错误脱敏为500，版本溢出409且不删除既有关系。 */
    @Test
    void hidesStorageFailuresAndRejectsVersionOverflow() {
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(RoleRecord.class)).thenThrow(new EasyQueryException("sensitive-scope-storage"));
        var unavailable = new RoleWarehouseScopeRepositoryImpl(database);
        error(CommonErrorCode.INTERNAL_ERROR, () -> work.execute(() -> unavailable.findByRoleId(UUID.randomUUID().toString())));
        String role = role(); UUID warehouse = master(MasterData.Kind.WAREHOUSE);
        var configuration = load(role); configuration.replace(WarehouseScope.custom(Set.of(warehouse))); save(configuration);
        jdbc.update("UPDATE auth_role_warehouse_scope SET version=? WHERE role_id=?", Long.MAX_VALUE, role);
        var exhausted = load(role); exhausted.replace(WarehouseScope.all()); error(CommonErrorCode.CONFLICT, () -> save(exhausted));
        assertEquals(Long.MAX_VALUE, load(role).getVersion()); assertEquals(Set.of(warehouse), load(role).getScope().warehouseIds());
    }

    /**
     * 新建测试角色及默认仓库配置。
     * @return 角色UUID
     */
    private String role() {
        var role = AuthorizationRole.create("scope-store-" + UUID.randomUUID(), "范围仓储"); roleIds.add(role.getId());
        work.execute(() -> { administration.lock(); roles.save(role); return null; }); return role.getId();
    }

    /**
     * 保存测试主数据。
     * @param kind 仓库或错误类别物料
     * @return 主数据UUID
     */
    private UUID master(MasterData.Kind kind) {
        var value = MasterData.create(kind, UUID.randomUUID().toString(), "范围仓储", kind == MasterData.Kind.MATERIAL ? "件" : null);
        masterIds.add(value.getId()); work.execute(() -> { masters.save(value); return null; }); return UUID.fromString(value.getId());
    }

    /**
     * 在持管理锁的独立工作单元读取配置。
     * @param role 角色UUID
     * @return 完整配置
     */
    private RoleWarehouseScope load(String role) { return work.execute(() -> { administration.lock(); return scopes.findByRoleId(role).orElseThrow(); }); }

    /**
     * 在独立Application工作单元保存配置。
     * @param configuration 新配置
     */
    private void save(RoleWarehouseScope configuration) { work.execute(() -> { administration.lock(); scopes.save(configuration); return null; }); }

    /**
     * 返回并发提交的状态，不吞掉非冲突错误。
     * @param configuration 待提交配置
     * @return 200或409
     */
    private int outcome(RoleWarehouseScope configuration) {
        try { save(configuration); return 200; }
        catch (StratumException failure) { assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 409; }
    }

    /**
     * 核对安全错误码与脱敏原因，不输出底层连接信息。
     * @param expected 预期错误
     * @param action 被测操作
     */
    private void error(CommonErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(StratumException.class, action); assertEquals(expected, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
