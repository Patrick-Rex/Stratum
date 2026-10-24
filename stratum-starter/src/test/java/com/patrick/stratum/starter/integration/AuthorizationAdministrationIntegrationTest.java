package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 验证管理协调、当前管理员状态及角色关系原子替换，最后管理员约束由领域规则执行。 */
class AuthorizationAdministrationIntegrationTest extends MariaDbIntegrationTest {
    private final AuthorizationAdministrationRepository administration;
    private final AuthorizationRoleRepository roles;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork work;
    private final JdbcTemplate jdbc;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> createdRoles = new ArrayList<>();
    private UUID administratorRole;

    /**
     * 注入管理写依赖及独立验证客户端。
     * @param administration 管理协调仓储
     * @param roles 角色仓储
     * @param accounts 账号仓储
     * @param passwords 凭据散列器
     * @param work Application工作单元
     * @param jdbc 断言与清理客户端
     */
    @Autowired
    AuthorizationAdministrationIntegrationTest(AuthorizationAdministrationRepository administration, AuthorizationRoleRepository roles,
            LocalAccountRepository accounts, PasswordHasher passwords, UnitOfWork work, JdbcTemplate jdbc) {
        this.administration = administration; this.roles = roles; this.accounts = accounts;
        this.passwords = passwords; this.work = work; this.jdbc = jdbc;
    }

    /** 在协调锁内准备保留角色，不占用任何用户已有账号。 */
    @BeforeEach
    void prepare() {
        administratorRole = work.execute(() -> {
            administration.lock(); roles.registerCatalog();
            var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                var created = AuthorizationRole.administrator(); createdRoles.add(created.getId()); roles.save(created); return created;
            });
            assertEquals(0, administration.countEnabledAdministrators(), "独立测试库不应遗留有效管理员夹具");
            return UUID.fromString(role.getId());
        });
    }

    /** 清理本用例创建的账号和角色，保留共享目录与原有保留角色。 */
    @AfterEach
    void clean() {
        for (UUID user : users) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", user.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", user.toString());
        }
        for (String role : createdRoles) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", role);
            jdbc.update("DELETE FROM auth_role WHERE id=?", role);
        }
    }

    /** 只有启用且真正绑定管理员角色的账号才计入管理员，未知账号不获得默认权限。 */
    @Test
    void resolvesEnabledAdministratorFromCurrentRelationships() {
        UUID first = account(); UUID second = account();
        assign(first, Set.of(administratorRole)); assign(second, Set.of(administratorRole));
        assertEquals(2, work.execute(administration::countEnabledAdministrators));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", second.toString());
        work.execute(() -> {
            assertEquals(1, administration.countEnabledAdministrators());
            assertTrue(administration.isEnabledAdministrator(first)); assertFalse(administration.isEnabledAdministrator(second));
            assertFalse(administration.isEnabledAdministrator(UUID.randomUUID()));
            assertEquals(Set.of(administratorRole), administration.findRoleIds(first)); return null;
        });
    }

    /** 关系替换/清空精确隔离；无效外键导致整个替换回滚，不丢失原授权。 */
    @Test
    void replacesMembershipAtomicallyAndBoundsInput() {
        UUID first = account(); UUID second = account();
        assign(first, Set.of(administratorRole)); assign(second, Set.of(administratorRole));
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class,
                () -> assign(first, Set.of(UUID.randomUUID()))).getErrorCode());
        assertEquals(Set.of(administratorRole), work.execute(() -> administration.findRoleIds(first)));
        assign(first, Set.of()); assertTrue(work.execute(() -> administration.findRoleIds(first)).isEmpty());
        assertEquals(Set.of(administratorRole), work.execute(() -> administration.findRoleIds(second)));
        var oversized = java.util.stream.IntStream.range(0, 65).mapToObj(i -> UUID.randomUUID()).collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> assign(first, oversized));
        assertThrows(UnsupportedOperationException.class, () -> work.execute(() -> administration.findRoleIds(second)).clear());
    }

    /** 初始化标记只在事务提交后生效，所有入口均拒绝活动写事务之外的调用。 */
    @Test
    void initializationMarkerRollsBackAndOperationsRequireTransaction() {
        boolean before = work.execute(administration::isInitialized);
        assertThrows(IllegalArgumentException.class, () -> work.execute(() -> {
            jdbc.update("UPDATE auth_administration_guard SET initialized=0 WHERE singleton_id=1");
            administration.markInitialized(); administration.markInitialized(); assertTrue(administration.isInitialized());
            throw new IllegalArgumentException("强制回滚");
        }));
        assertEquals(before, work.execute(administration::isInitialized));
        UUID user = UUID.randomUUID();
        assertThrows(IllegalStateException.class, administration::lock);
        assertThrows(IllegalStateException.class, administration::isInitialized);
        assertThrows(IllegalStateException.class, administration::markInitialized);
        assertThrows(IllegalStateException.class, administration::countEnabledAdministrators);
        assertThrows(IllegalStateException.class, () -> administration.isEnabledAdministrator(user));
        assertThrows(IllegalStateException.class, () -> administration.findRoleIds(user));
        assertThrows(IllegalStateException.class, () -> administration.replaceRoleIds(user, Set.of()));
    }

    /** 并发移除两名管理员时只能成功一次，后到事务必须看见前次提交并保留最后一人。
     * @throws Exception 并发任务失败或超过有界等待
     */
    @Test
    void serializedChangesPreserveTheLastAdministrator() throws Exception {
        UUID first = account(); UUID second = account(); assign(first, Set.of(administratorRole)); assign(second, Set.of(administratorRole));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(executor.submit(() -> remove(start, first)), executor.submit(() -> remove(start, second)));
            start.countDown();
            assertEquals(1, results.getFirst().get(10, TimeUnit.SECONDS) + results.getLast().get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, work.execute(administration::countEnabledAdministrators));
    }

    /** 旧快照不能把已停用管理员继续计为有效：冲突后新事务返回实际状态。 */
    @Test
    void staleSnapshotCannotReturnFormerAdministratorCount() {
        UUID user = account(); assign(user, Set.of(administratorRole));
        var failure = assertThrows(StratumException.class, () -> work.execute(() -> {
            assertEquals(1, jdbc.queryForObject("SELECT enabled FROM auth_user WHERE id=?", Integer.class, user.toString()));
            CompletableFuture.runAsync(() -> jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString())).join();
            return administration.countEnabledAdministrators();
        }));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertEquals(0, work.execute(administration::countEnabledAdministrators));
    }

    /**
     * 执行一个受协调保护的领域规则与关系修改组合，不代表HTTP管理已经接入。
     * @param start 同步开始信号
     * @param user 待移除成员
     * @return 成功为1，保护冲突为0
     * @throws InterruptedException 等待被中断
     */
    private int remove(CountDownLatch start, UUID user) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发启动超时");
        try {
            return work.execute(() -> {
                administration.lock();
                AdministrationPolicy.requireAdministratorRemaining(administration.countEnabledAdministrators(), administration.isEnabledAdministrator(user));
                administration.replaceRoleIds(user, Set.of()); return 1;
            });
        } catch (StratumException failure) {
            assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 0;
        }
    }

    /**
     * 创建无授权普通账号，及时清零凭据。
     * @return 已保存账号UUID
     */
    private UUID account() {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("admin-it-" + UUID.randomUUID(), passwords.hash(raw), false);
            UUID id = UUID.fromString(account.getId()); users.add(id);
            work.execute(() -> { accounts.insert(account); return null; }); return id;
        } finally { Arrays.fill(raw, '\0'); }
    }

    /**
     * 在工作单元内替换夹具角色。
     * @param user 账号UUID
     * @param ids 角色集合
     */
    private void assign(UUID user, Set<UUID> ids) { work.execute(() -> { administration.replaceRoleIds(user, ids); return null; }); }
}
