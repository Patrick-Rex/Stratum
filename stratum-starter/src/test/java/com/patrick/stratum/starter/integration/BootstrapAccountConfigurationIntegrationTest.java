package com.patrick.stratum.starter.integration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.application.auth.BootstrapAccountApplicationService;
import com.patrick.stratum.application.auth.BootstrapAuthorizationApplicationService;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.PermissionCatalog;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.starter.auth.BootstrapAccountConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实启动生命周期、环境属性源和数据库验证管理员配置，不生成默认密码。 */
@ExtendWith(OutputCaptureExtension.class)
class BootstrapAccountConfigurationIntegrationTest extends MariaDbIntegrationTest {
    private final BootstrapAccountApplicationService bootstrap;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final AccountCredentials credentials;
    private final BootstrapAuthorizationApplicationService authorization;
    private final AccountPermissions permissions;
    private final String username = "environment" + UUID.randomUUID();

    /**
     * 复用父测试上下文的真实应用入口，子上下文只负责启动装配。
     * @param bootstrap 应用入口
     * @param accounts 写仓储
     * @param passwords 密码适配器
     * @param unitOfWork 事务门面
     * @param jdbc 测试清理客户端
     * @param credentials 查询端口，用于验证开启SQL日志时也不会泄漏登录标识
     * @param authorization 角色初始化应用入口
     * @param permissions 权限查询端口
     */
    @Autowired
    BootstrapAccountConfigurationIntegrationTest(BootstrapAccountApplicationService bootstrap, LocalAccountRepository accounts,
            PasswordHasher passwords, UnitOfWork unitOfWork, JdbcTemplate jdbc, AccountCredentials credentials,
            BootstrapAuthorizationApplicationService authorization, AccountPermissions permissions) {
        this.bootstrap = bootstrap; this.accounts = accounts; this.passwords = passwords;
        this.unitOfWork = unitOfWork; this.jdbc = jdbc;
        this.credentials = credentials; this.authorization = authorization; this.permissions = permissions;
    }

    /**
     * 故意开启ORM日志，验证凭据读写显式保护不依赖全局默认值。
     * @param registry 测试配置注册器
     */
    @DynamicPropertySource
    static void enableSqlLogging(DynamicPropertyRegistry registry) { registry.add("easy-query.print-sql", () -> "true"); }

    /** 只清理本用例账号及角色绑定，复位独立测试库的初始化标记。 */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM auth_user_role WHERE user_id IN (SELECT id FROM auth_user WHERE username=?)", username);
        int removed = jdbc.update("DELETE FROM auth_user WHERE username = ?", username);
        if (removed == 1) jdbc.update("UPDATE auth_administration_guard SET initialized=0 WHERE singleton_id=1");
    }

    /**
     * 环境密码保持字面值，首次启动存散列，日志不输出环境凭据。
     * @param output 启动日志捕获
     */
    @Test
    void initializesFromLiteralEnvironmentValues(CapturedOutput output) {
        String raw = " ${literal}" + UUID.randomUUID() + " ";
        try (var context = start(true, Map.of("STRATUM_AUTH_ADMIN_USERNAME", username, "STRATUM_AUTH_ADMIN_PASSWORD", raw), Map.of())) {
            assertNotNull(context.getBean("bootstrapAccountRunner"));
            var account = unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow();
            assertTrue(credentials.findByUsername(username, "bootstrap-log-test").isPresent());
            assertEquals(PermissionCatalog.permissions(), permissions.findByUserId(UUID.fromString(account.getId()), "bootstrap-grants-test")
                    .orElseThrow().permissions());
            char[] password = raw.toCharArray();
            try { assertTrue(passwords.matches(password, account.getPasswordHash())); }
            finally { Arrays.fill(password, '\0'); }
            assertFalse(output.getAll().contains(raw));
            assertFalse(output.getAll().contains(account.getPasswordHash()));
            assertFalse(output.getAll().contains(username));
        }
    }

    /** 已存在初始账号时，重启可移除环境凭据，改变配置也不重置。 */
    @Test
    void restartDoesNotRequireOrReplaceCredentials() {
        var id = bootstrap.initialize(username, UUID.randomUUID().toString().toCharArray());
        var hash = unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow().getPasswordHash();
        try (var ignored = start(true, Map.of(), Map.of())) {
            assertEquals(id.toString(), unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow().getId());
        }
        try (var ignored = start(true, Map.of("STRATUM_AUTH_ADMIN_USERNAME", "replacement", "STRATUM_AUTH_ADMIN_PASSWORD", UUID.randomUUID().toString()), Map.of())) {
            var account = unitOfWork.execute(accounts::findBootstrapAdministrator).orElseThrow();
            assertEquals(username, account.getUsername());
            assertEquals(hash, account.getPasswordHash());
        }
    }

    /**
     * 首次缺失任一凭据或输入不合法均使启动失败，异常和日志不包含原值。
     * @param output 启动日志捕获
     */
    @Test
    void missingOrInvalidEnvironmentStopsStartup(CapturedOutput output) {
        String secret = UUID.randomUUID().toString();
        for (Map<String, Object> values : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("STRATUM_AUTH_ADMIN_USERNAME", username),
                Map.<String, Object>of("STRATUM_AUTH_ADMIN_PASSWORD", secret),
                Map.<String, Object>of("STRATUM_AUTH_ADMIN_USERNAME", "invalid user", "STRATUM_AUTH_ADMIN_PASSWORD", secret),
                Map.<String, Object>of("STRATUM_AUTH_ADMIN_USERNAME", username, "STRATUM_AUTH_ADMIN_PASSWORD", ""))) {
            var failure = assertThrows(IllegalStateException.class, () -> start(true, values, Map.of()));
            for (Throwable current = failure; current != null; current = current.getCause()) {
                assertFalse(String.valueOf(current.getMessage()).contains(secret));
                assertFalse(String.valueOf(current.getMessage()).contains("invalid user"));
            }
            assertTrue(unitOfWork.execute(accounts::findBootstrapAdministrator).isEmpty());
        }
        assertFalse(output.getAll().contains(secret));
    }

    /**
     * 账号创建已完成但角色初始化失败时，真实启动拒绝就绪且不暴露凭据。
     * @param output 启动日志
     */
    @Test
    void authorizationFailurePreventsStartup(CapturedOutput output) {
        String secret = UUID.randomUUID().toString();
        var id = bootstrap.initialize(username, secret.toCharArray());
        jdbc.update("UPDATE auth_user SET enabled=0 WHERE id=?", id.toString());
        var failure = assertThrows(IllegalStateException.class, () -> start(true, Map.of(), Map.of()));
        assertFalse(output.getAll().contains(secret));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) assertFalse(String.valueOf(cause.getMessage()).contains(secret));
        assertEquals(0, jdbc.queryForObject("SELECT initialized FROM auth_administration_guard WHERE singleton_id=1", Integer.class));
        assertTrue(permissions.findByUserId(id, "bootstrap-failed").orElseThrow().permissions().isEmpty());
    }

    /** 未启用JWT组件时不装配初始化；普通属性不能替代环境变量提供密码。 */
    @Test
    void requiresExplicitEnableAndEnvironmentSource() {
        try (var context = start(false, Map.of(), Map.of())) {
            assertFalse(context.containsBean("bootstrapAccountRunner"));
            assertTrue(unitOfWork.execute(accounts::findBootstrapAdministrator).isEmpty());
        }
        assertThrows(IllegalStateException.class, () -> start(true, Map.of(), Map.of(
                "STRATUM_AUTH_ADMIN_USERNAME", username, "STRATUM_AUTH_ADMIN_PASSWORD", UUID.randomUUID().toString())));
        assertTrue(unitOfWork.execute(accounts::findBootstrapAdministrator).isEmpty());
    }

    /**
     * 启动真实SpringApplication并替换测试环境源，不读取开发者的管理员凭据。
     * @param enabled 是否启用JWT组件初始化
     * @param variables 模拟操作系统环境变量
     * @param other 普通属性来源，用于验证不能伪装环境凭据
     * @return 调用方必须关闭的上下文
     */
    private ConfigurableApplicationContext start(boolean enabled, Map<String, Object> variables, Map<String, Object> other) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        var settings = new HashMap<String, Object>(other);
        settings.put("stratum.auth.jwt.enabled", Boolean.toString(enabled));
        environment.getPropertySources().addFirst(new MapPropertySource("bootstrap-test-settings", settings));
        return new SpringApplicationBuilder(BootstrapAccountConfiguration.class)
                .environment(environment).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("bootstrapAccountApplicationService", bootstrap);
                    context.getBeanFactory().registerSingleton("bootstrapAuthorizationApplicationService", authorization);
                }).run();
    }
}
