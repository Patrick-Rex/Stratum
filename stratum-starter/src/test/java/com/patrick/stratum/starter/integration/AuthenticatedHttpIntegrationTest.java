package com.patrick.stratum.starter.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.domain.auth.TokenCodec;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实HTTP认证夹具：进程内密钥和随机初始化账号，通过生产登录入口获取Access。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ContextConfiguration(initializers = AuthenticatedHttpIntegrationTest.CredentialsEnvironment.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AuthenticatedHttpIntegrationTest extends MariaDbIntegrationTest {
    private static final String INITIAL_USERNAME = "http-admin-" + UUID.randomUUID();
    private static final String INITIAL_PASSWORD = UUID.randomUUID().toString();
    private static final KeyPair SIGNING_KEYS = generateKeys();
    @LocalServerPort private int authenticationPort;
    @Autowired private TokenCodec tokens;
    @Autowired private StringRedisTemplate authenticationRedis;
    private final Set<String> authenticationKeys = new HashSet<>();
    private Session defaultSession;

    /**
     * 为测试应用启用真实JWT，凭据仅来自测试环境源，不读取开发者管理员配置。
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void configureAuthentication(DynamicPropertyRegistry registry) {
        registry.add("stratum.auth.jwt.enabled", () -> "true");
        registry.add("stratum.auth.jwt.public-key", () -> pem("PUBLIC KEY", SIGNING_KEYS.getPublic().getEncoded()));
        registry.add("stratum.auth.jwt.private-key", () -> pem("PRIVATE KEY", SIGNING_KEYS.getPrivate().getEncoded()));
        registry.add("stratum.auth.jwt.issuer", () -> "stratum-http-test");
        registry.add("stratum.auth.jwt.audience", () -> "stratum-api-test");
    }

    /**
     * 每个用例获得独立会话，实际经过用户名/BCrypt验证与Redis登记。
     * @throws Exception HTTP登录失败
     */
    @BeforeEach
    void authenticateTestRequest() throws Exception { defaultSession = login(INITIAL_USERNAME, INITIAL_PASSWORD); }

    /** 清理本用例的有限认证键，不影响用户原有Redis数据。 */
    @AfterEach
    void cleanAuthenticationSessions() { if (!authenticationKeys.isEmpty()) { authenticationRedis.delete(authenticationKeys); } }

    /**
     * 当前测试类完成后清理其初始化账号，让其他启动初始化测试保持隔离。
     * @param jdbc 仍存活上下文的清理客户端
     */
    @AfterAll
    static void cleanInitialAccount(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM auth_user WHERE username = ? AND bootstrap_slot = 1", INITIAL_USERNAME);
    }

    /**
     * 返回默认测试身份Access。
     * @return 仅用于HTTP请求的敏感令牌
     */
    protected String accessToken() { return defaultSession.accessToken(); }

    /**
     * 返回默认会话Refresh以验证实际网络旋转。
     * @return 仅用于刷新请求的敏感令牌
     */
    protected String refreshToken() { return defaultSession.refreshToken(); }

    /**
     * 返回默认测试身份，用于独立核对幂等作用域。
     * @return 已通过登录确定的用户ID
     */
    protected String subjectId() { return defaultSession.subjectId(); }

    /**
     * 经真实登录为默认账号创建另一个独立会话。
     * @return 由夹具统一清理的会话
     * @throws Exception HTTP登录失败
     */
    protected Session additionalSession() throws Exception { return login(INITIAL_USERNAME, INITIAL_PASSWORD); }

    /**
     * 从生产HTTP端点登录任意本用例账号，记录会话键供清理。
     * @param username 已创建的测试用户名
     * @param password 只在测试进程中保留的随机凭据
     * @return 不输出令牌内容的测试会话
     * @throws Exception HTTP登录失败
     */
    protected Session login(String username, String password) throws Exception {
        var json = JsonMapper.builder().build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + authenticationPort + "/auth/login"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("X-Trace-Id", "authenticated-http-test")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", password)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "HTTP认证夹具登录失败");
            var data = json.readTree(response.body()).path("data");
            var claims = tokens.verify(data.path("refreshToken").asText(), Purpose.REFRESH);
            authenticationKeys.add("auth:user:" + claims.subject() + ":ver");
            authenticationKeys.add("auth:sess:" + claims.sessionId()); authenticationKeys.add("auth:rt:" + claims.tokenId());
            assertEquals(claims.subject(), tokens.verify(data.path("accessToken").asText(), Purpose.ACCESS).subject());
            return new Session(data.path("accessToken").asText(), data.path("refreshToken").asText(), claims.subject());
        }
    }

    /**
     * 动态生成进程内RSA密钥。
     * @return 2048位RSA密钥对
     */
    private static KeyPair generateKeys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("测试密钥生成失败"); }
    }

    /**
     * 编码测试PEM，不写入文件。
     * @param label PEM类型
     * @param encoded DER数据
     * @return PEM字符串
     */
    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n" + Base64.getEncoder().encodeToString(encoded) + "\n-----END " + label + "-----";
    }

    /**
     * 测试会话，只允许显式取出令牌用于HTTP，不使用默认记录日志。
     * @param accessToken 敏感Access
     * @param refreshToken 敏感Refresh
     * @param subjectId 已验证用户ID
     */
    protected record Session(String accessToken, String refreshToken, String subjectId) {
        /** {@inheritDoc} */
        @Override public String toString() { return "TestSession[内容已隐藏]"; }
    }

    /** 注入测试专用原始环境值，防止使用开发者真实管理员凭据。 */
    public static class CredentialsEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        /** {@inheritDoc} */
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("STRATUM_AUTH_ADMIN_USERNAME", INITIAL_USERNAME, "STRATUM_AUTH_ADMIN_PASSWORD", INITIAL_PASSWORD)));
        }
    }
}
