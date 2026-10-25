package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AuthorizationRole;
import com.patrick.stratum.domain.auth.AuthorizationRoleRepository;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryGateway;
import com.patrick.stratum.query.auth.AccountCredentialView;
import com.patrick.stratum.query.auth.AdministrationQueryAccess;
import com.patrick.stratum.query.auth.AdministratorQuery;
import com.patrick.stratum.query.auth.AdministratorQueryHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证管理查询资格来自真实启用状态和管理员角色，伪造权限和旧资格均不能放行。 */
class AdministratorQueryIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private QueryGateway gateway;
    @Autowired private AdministrationQueryAccess access;
    @Autowired private UnitOfWork work;
    @Autowired private LocalAccountRepository accounts;
    @Autowired private AuthorizationRoleRepository roles;
    @Autowired private PasswordHasher passwords;
    @Autowired private JdbcTemplate jdbc;
    private final List<UUID> users = new ArrayList<>();
    private UUID administrator;
    private boolean createdRole;

    /** 只补充测试需要的管理员角色，保留其他测试已登记的目录和角色。 */
    @BeforeEach
    void prepareRole() {
        work.execute(() -> {
            roles.registerCatalog();
            var role = roles.findByCode(AuthorizationRole.ADMINISTRATOR_CODE).orElseGet(() -> {
                createdRole = true; var created = AuthorizationRole.administrator(); roles.save(created); return created;
            });
            administrator = UUID.fromString(role.getId()); return null;
        });
    }

    /** 仅清理本测试账号关系与本测试创建的角色，不修改既有管理员。 */
    @AfterEach
    void clean() {
        for (UUID user : users) {
            jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", user.toString());
            jdbc.update("DELETE FROM auth_user WHERE id=?", user.toString());
        }
        if (createdRole) {
            jdbc.update("DELETE FROM auth_role_permission WHERE role_id=?", administrator.toString());
            jdbc.update("DELETE FROM auth_role WHERE id=?", administrator.toString());
        }
    }

    /** 当前读反映绑定、停用、重新启用和移除，不保留旧资格。 */
    @Test
    void readsCurrentMembershipAndEnabledState() {
        UUID user = account(); var context = context(user);
        forbidden(() -> access.require(context));
        jdbc.update("INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)", user.toString(), administrator.toString());
        assertDoesNotThrow(() -> access.require(context));
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", user.toString());
        forbidden(() -> access.require(context));
        jdbc.update("UPDATE auth_user SET enabled=1 WHERE id=?", user.toString());
        assertDoesNotThrow(() -> access.require(context));
        jdbc.update("DELETE FROM auth_user_role WHERE user_id=?", user.toString());
        forbidden(() -> access.require(context));
    }

    /** 无角色账号即使伪造管理权限字符串或持初始化槽也不能获得管理员资格。 */
    @Test
    void rejectsDeclaredPermissionsAndBootstrapMarker() {
        UUID user = account();
        var forged = new QueryContext.Snapshot("administrator-query", Optional.of(user.toString()), Set.of("auth.account.manage", "auth.role.manage"));
        forbidden(() -> access.require(forged));
        jdbc.update("UPDATE auth_user SET bootstrap_slot=1 WHERE id=?", user.toString());
        forbidden(() -> access.require(forged));
        forbidden(() -> access.require(context(UUID.randomUUID())));
    }

    /** 匿名及不同查询主体拒绝，畸形操作者标识不能退化为默认身份。 */
    @Test
    void rejectsAnonymousMismatchedAndMalformedIdentity() {
        var anonymous = QueryContext.anonymous("administrator-query");
        assertEquals(CommonErrorCode.UNAUTHORIZED, assertThrows(StratumException.class, () -> access.require(anonymous)).getErrorCode());
        UUID user = account();
        forbidden(() -> gateway.execute(AdministratorQuery.create(user), context(UUID.randomUUID())));
        forbidden(() -> access.require(new QueryContext.Snapshot("administrator-query", Optional.of("invalid"), Set.of())));
        assertThrows(NullPointerException.class, () -> AdministratorQuery.create(null));
    }

    /** 真实装配保留网关的写事务拒绝规则。 */
    @Test
    void rejectsActiveUnitOfWork() {
        UUID user = account();
        assertThrows(IllegalStateException.class, () -> work.execute(() -> { access.require(context(user)); return null; }));
    }

    /** 存储故障经正式网关失败关闭，不输出SQL原因或返回默认允许。 */
    @Test
    void failsClosedWithoutStorageDetails() {
        var database = mock(EasyEntityQuery.class);
        when(database.queryable(AccountCredentialView.class)).thenThrow(new EasyQueryException("sensitive-administrator-query"));
        var metrics = new SimpleMeterRegistry();
        try {
            var failed = new QueryGateway(List.of(new AdministratorQueryHandler(database)), metrics, Duration.ofSeconds(1));
            var factory = new StaticListableBeanFactory(); factory.addBean("gateway", failed);
            var checker = new AdministrationQueryAccess(factory.getBeanProvider(QueryGateway.class));
            var failure = assertThrows(StratumException.class, () -> checker.require(context(UUID.randomUUID())));
            assertEquals(CommonErrorCode.INTERNAL_ERROR, failure.getErrorCode()); assertNull(failure.getCause());
            assertFalse(failure.toString().contains("sensitive-administrator-query"));
        } finally { metrics.close(); }
    }

    /**
     * 保存独立普通账号，不附带初始化资格或角色。
     * @return 本测试账号UUID
     */
    private UUID account() {
        char[] secret = UUID.randomUUID().toString().toCharArray();
        try {
            var account = LocalAccount.create("administrator-query-" + UUID.randomUUID(), passwords.hash(secret), false);
            UUID id = UUID.fromString(account.getId()); users.add(id);
            work.execute(() -> { accounts.insert(account); return null; }); return id;
        } finally { Arrays.fill(secret, '\0'); }
    }

    /**
     * 构造服务器内部上下文，不从HTTP输入取得权限。
     * @param user 测试已知账号
     * @return 不携带声明权限的上下文
     */
    private static QueryContext context(UUID user) {
        return new QueryContext.Snapshot("administrator-query", Optional.of(user.toString()), Set.of());
    }

    /**
     * 断言管理资格被拒绝。
     * @param action 资格检查动作
     */
    private static void forbidden(Runnable action) {
        assertEquals(CommonErrorCode.FORBIDDEN, assertThrows(StratumException.class, action::run).getErrorCode());
    }
}
