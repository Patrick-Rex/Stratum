package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.AccountRoleApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库与Redis验证成员关系、账号版本及撤销的统一管理用例。 */
class AccountRoleApplicationIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private AuthorizationAdministrationRepository administration;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private PasswordHasher passwords;
    @Autowired private AccountPermissions permissions;
    @Autowired private AccountRevocations revocations;
    @Autowired private UnitOfWork work;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> createdRoles = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private UUID administratorRole;
    private UUID ordinaryRole;
    private UUID administrator;

    /** 准备独立管理员与一项已登记权限的普通角色。 */
    @BeforeEach
    void prepare() {
        administratorRole = work.execute(() -> {
            administration.lock(); roles.registerCatalog();
            var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                var created = AuthorizationRole.administrator(); createdRoles.add(created.getId()); roles.save(created); return created;
            });
            assertEquals(0, administration.countEnabledAdministrators());
            var ordinary = AuthorizationRole.create("member-" + UUID.randomUUID(), "测试角色");
            createdRoles.add(ordinary.getId()); ordinary.replacePermissions(Set.of(PermissionCatalog.MATERIAL_READ.permission()));
            roles.save(ordinary); ordinaryRole = UUID.fromString(ordinary.getId());
            return UUID.fromString(role.getId());
        });
        administrator = account(true);
    }

    /** 只清理本测试创建的账号、角色与撤销键。 */
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
        if (!keys.isEmpty()) redis.delete(keys);
    }

    /** 关系、账号版本与撤销同步前进；同集合无副作用，旧版本被拒绝，固定账号属性保持。 */
    @Test
    void replacesMembershipWithAccountVersionAndImmediatePermissions() {
        UUID target = account(false); var before = load(target); var service = service(administrator, revocations);
        assertEquals(1, service.replace(target, 0, Set.of(ordinaryRole)));
        assertEquals(Set.of(PermissionCatalog.MATERIAL_READ.permission()), permissions.findByUserId(target, "member-test").orElseThrow().permissions());
        assertEquals("1", redis.opsForValue().get(key(target)));
        assertEquals(1, service.replace(target, 1, Set.of(ordinaryRole)));
        assertEquals("1", redis.opsForValue().get(key(target)));
        error(CommonErrorCode.CONFLICT, () -> service.replace(target, 0, Set.of()));
        assertEquals(2, service.replace(target, 1, Set.of()));
        assertTrue(permissions.findByUserId(target, "member-test").orElseThrow().permissions().isEmpty());
        var after = load(target);
        assertEquals(before.getUsername(), after.getUsername()); assertEquals(before.getPasswordHash(), after.getPasswordHash());
        assertEquals(before.isEnabled(), after.isEnabled()); assertEquals(before.isBootstrapAdministrator(), after.isBootstrapAdministrator());
        assertEquals(2L, after.getVersion()); assertEquals("2", redis.opsForValue().get(key(target)));
    }

    /** 最后一名管理员不能被移除；可先授权继任者，原管理员移除后立即失去管理资格。 */
    @Test
    void protectsLastAdministratorAndChecksCurrentMembership() {
        var service = service(administrator, revocations); UUID successor = account(false);
        error(CommonErrorCode.FORBIDDEN, () -> service(successor, revocations).replace(successor, 0, Set.of(administratorRole)));
        error(CommonErrorCode.CONFLICT, () -> service.replace(administrator, 0, Set.of()));
        service.replace(successor, 0, Set.of(administratorRole));
        assertEquals(1, service.replace(administrator, 0, Set.of()));
        error(CommonErrorCode.FORBIDDEN, () -> service.replace(administrator, 1, Set.of(administratorRole)));
        assertEquals(1, work.execute(administration::countEnabledAdministrators));
        assertTrue(permissions.findByUserId(administrator, "member-test").orElseThrow().permissions().isEmpty());
    }

    /** 无效角色外键、超界集合及缺失目标在失败后不留下关系、版本或撤销副作用。 */
    @Test
    void rejectsInvalidMembershipWithoutPartialChanges() {
        UUID target = account(false); var service = service(administrator, revocations);
        service.replace(target, 0, Set.of(ordinaryRole));
        error(CommonErrorCode.CONFLICT, () -> service.replace(target, 1, Set.of(UUID.randomUUID())));
        var tooMany = java.util.stream.IntStream.range(0, 65).mapToObj(index -> UUID.randomUUID()).collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> service.replace(target, 1, tooMany));
        error(CommonErrorCode.NOT_FOUND, () -> service.replace(UUID.randomUUID(), 0, Set.of()));
        assertEquals(Set.of(ordinaryRole), work.execute(() -> administration.findRoleIds(target)));
        assertEquals(1L, load(target).getVersion()); assertEquals("1", redis.opsForValue().get(key(target)));
    }

    /** Redis失败时成员关系与账号版本整体回滚，原启用状态和权限保持。 */
    @Test
    void revocationFailureRollsBackMembershipAndVersion() {
        UUID target = account(false); service(administrator, revocations).replace(target, 0, Set.of(ordinaryRole));
        AccountRevocations failing = id -> { throw new StratumException(CommonErrorCode.INTERNAL_ERROR); };
        error(CommonErrorCode.INTERNAL_ERROR, () -> service(administrator, failing).replace(target, 1, Set.of()));
        assertEquals(Set.of(ordinaryRole), work.execute(() -> administration.findRoleIds(target)));
        assertEquals(1L, load(target).getVersion()); assertEquals("1", redis.opsForValue().get(key(target)));
    }

    /** 提交前失败不返回成功，关系与账号回滚，而已成功撤销的Redis版本不恢复。 */
    @Test
    void commitFailureKeepsRevocationButRollsBackMembership() {
        UUID target = account(false);
        AccountRevocations failingCommit = id -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** {@inheritDoc} */
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("测试提交失败"); }
            });
            return revocations.revoke(id);
        };
        assertThrows(IllegalStateException.class, () -> service(administrator, failingCommit).replace(target, 0, Set.of(ordinaryRole)));
        assertTrue(work.execute(() -> administration.findRoleIds(target)).isEmpty());
        assertEquals(0L, load(target).getVersion()); assertEquals("1", redis.opsForValue().get(key(target)));
    }

    /**
     * 两个管理员并发移除自身角色只能成功一次，不能留下无管理员状态。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentRemovalsPreserveLastAdministrator() throws Exception {
        UUID second = account(true); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = List.of(administrator, second).stream().map(user -> executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { service(user, revocations).replace(user, 0, Set.of()); return 1; }
                catch (StratumException failure) { assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode()); return 0; }
            })).toList();
            start.countDown();
            assertEquals(1, jobs.getFirst().get(15, TimeUnit.SECONDS) + jobs.getLast().get(15, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertEquals(1, work.execute(administration::countEnabledAdministrators));
    }

    /**
     * 创建无初始化标记的独立账号。
     * @param admin 是否授予管理员角色
     * @return 账号UUID
     */
    private UUID account(boolean admin) {
        char[] raw = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("membership-" + UUID.randomUUID(), passwords.hash(raw), false);
            UUID id = UUID.fromString(account.getId()); users.add(id); keys.add(key(id));
            work.execute(() -> {
                administration.lock(); accounts.insert(account);
                if (admin) administration.replaceRoleIds(id, Set.of(administratorRole));
                return null;
            }); return id;
        } finally { Arrays.fill(raw, '\0'); }
    }

    /**
     * 创建可信身份下的成员管理用例。
     * @param actor 操作者UUID
     * @param revoke 真实或故障撤销端口
     * @return 管理应用用例
     */
    private AccountRoleApplicationService service(UUID actor, AccountRevocations revoke) {
        return new AccountRoleApplicationService(() -> new CurrentIdentity.Snapshot(actor, UUID.randomUUID(), 0), administration, roles, accounts, revoke, work);
    }

    /**
     * 用新事务读取当前账号。
     * @param id 账号UUID
     * @return 最新账号聚合
     */
    private LocalAccount load(UUID id) { return work.execute(() -> accounts.findById(id.toString()).orElseThrow()); }

    /**
     * 生成账号权威版本键。
     * @param id 账号UUID
     * @return Redis键
     */
    private static String key(UUID id) { return "auth:user:" + id + ":ver"; }

    /**
     * 检查统一业务错误且无敏感原因链。
     * @param code 预期错误
     * @param action 被拒绝操作
     */
    private static void error(CommonErrorCode code, Runnable action) {
        var failure = assertThrows(StratumException.class, action::run);
        assertEquals(code, failure.getErrorCode()); assertNull(failure.getCause());
    }
}
