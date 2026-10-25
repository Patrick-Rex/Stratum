package com.patrick.stratum.starter.integration;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.auth.LocalIdentityApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.LocalAccountRepository;
import com.patrick.stratum.domain.auth.PasswordHasher;
import com.patrick.stratum.domain.auth.TokenCodec;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import com.patrick.stratum.infrastructure.auth.RsaTokenAdapterImpl;
import com.patrick.stratum.interfaceadapter.controller.AuthController;
import com.patrick.stratum.interfaceadapter.response.ApiResponseAdvice;
import com.patrick.stratum.interfaceadapter.security.HttpSecurityConfiguration;
import com.patrick.stratum.interfaceadapter.security.SecurityCurrentIdentityAdapterImpl;
import com.patrick.stratum.interfaceadapter.web.ApiVersionConfiguration;
import com.patrick.stratum.interfaceadapter.web.RequestTraceFilter;
import com.patrick.stratum.starter.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 实际无状态安全链、MVC协议与真实RSA、MariaDB、Redis组合验证。 */
class AuthHttpProtocolIntegrationTest extends MariaDbIntegrationTest {
    private static TokenCodec codec;
    private final AuthSessionApplicationService authentication;
    private final LocalAccountRepository accounts;
    private final PasswordHasher passwords;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> keys = new HashSet<>();
    private final String username = "httpauth" + UUID.randomUUID();
    private final char[] password = UUID.randomUUID().toString().toCharArray();
    private LocalAccount account;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    /**
     * 注入真实端口，构造被HTTP控制器调用的应用服务。
     * @param identities 本地身份
     * @param sessions Redis会话
     * @param permissions 真实账号状态查询
     * @param accounts 账号夹具仓储
     * @param passwords BCrypt散列
     * @param unitOfWork 事务门面
     * @param jdbc 数据清理
     * @param redis 会话清理
     */
    @Autowired
    AuthHttpProtocolIntegrationTest(LocalIdentityApplicationService identities, AuthSessions sessions, AccountPermissions permissions,
            LocalAccountRepository accounts, PasswordHasher passwords, UnitOfWork unitOfWork, JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.authentication = new AuthSessionApplicationService(identities, codec, sessions, permissions);
        this.accounts = accounts; this.passwords = passwords; this.unitOfWork = unitOfWork; this.jdbc = jdbc; this.redis = redis;
    }

    /**
     * 动态创建测试签名密钥。
     * @throws Exception JCA不可用
     */
    @BeforeAll
    static void createKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
        codec = new RsaTokenAdapterImpl((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(), "http-test", "api-test", Clock.systemUTC());
    }

    /** 创建账号夹具与独立MVC上下文，避免测试探针进入生产扫描。 */
    @BeforeEach
    void setUp() {
        account = LocalAccount.create(username, passwords.hash(password), false);
        unitOfWork.execute(() -> { accounts.insert(account); return null; });
        keys.add("auth:user:" + account.getId() + ":ver");
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("auth-http-test", Map.of("stratum.auth.jwt.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("authentication", authentication));
        context.register(MvcConfiguration.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestTraceFilter(),
                context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }

    /** 关闭MVC上下文，清除自己的账号、会话和密码数组。 */
    @AfterEach
    void cleanUp() {
        if (context != null) { context.close(); }
        Arrays.fill(password, '\0'); redis.delete(keys);
        jdbc.update("DELETE FROM auth_user WHERE username = ?", username);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        SecurityContextHolder.clearContext();
    }

    /**
     * 标准响应、trace和禁止缓存头完整，客户端自报subjectId不会改变签发身份。
     * @throws Exception MVC调用失败
     */
    @Test
    void loginAndRefreshUseStandardProtocol() throws Exception {
        var first = send("/auth/login", Map.of("username", username, "password", new String(password), "subjectId", "forged-user"), 200);
        var firstData = body(first).path("data");
        assertEquals(account.getId(), codec.verify(firstData.path("accessToken").asText(), Purpose.ACCESS).subject());
        assertEquals(900, firstData.path("expiresIn").asInt());
        assertEquals("no-store", first.getResponse().getHeader("Cache-Control"));
        assertEquals("no-cache", first.getResponse().getHeader("Pragma"));
        assertNull(first.getRequest().getSession(false));
        var next = send("/auth/refresh", Map.of("refreshToken", firstData.path("refreshToken").asText()), 200);
        assertEquals("no-store", next.getResponse().getHeader("Cache-Control"));
        assertEquals(40100, body(send("/auth/refresh", Map.of("refreshToken", firstData.path("refreshToken").asText()), 401)).path("code").asInt());
        send("/auth/refresh", Map.of("refreshToken", body(next).path("data").path("accessToken").asText()), 401);
    }

    /**
     * 未知和错误密码具有相同错误结构，无密码或令牌回显。
     * @throws Exception MVC调用失败
     */
    @Test
    void rejectsInvalidCredentialsWithoutEchoingInput() throws Exception {
        String wrong = UUID.randomUUID().toString();
        var failed = body(send("/auth/login", Map.of("username", username, "password", wrong), 401));
        var unknown = body(send("/auth/login", Map.of("username", "unknown", "password", wrong), 401));
        assertEquals(failed, unknown);
        assertFalse(failed.toString().contains(wrong));
        assertTrue(failed.path("details").isMissingNode() || failed.path("details").isEmpty());
        assertFalse(failed.toString().contains("accessToken"));
        assertFalse(redis.hasKey("auth:user:" + account.getId() + ":ver"));
    }

    /**
     * 缺失和超长输入由MVC校验拒绝，错误响应不泄漏字段原值。
     * @throws Exception MVC调用失败
     */
    @Test
    void validatesRequestBoundsAndVersion() throws Exception {
        send("/auth/login", Map.of("username", username), 400);
        send("/auth/login", Map.of("username", username, "password", "x".repeat(73)), 400);
        send("/auth/refresh", Map.of("refreshToken", "x".repeat(16385)), 400);
        var result = mvc.perform(post("/auth/login").header("X-API-Version", "2").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", new String(password))))).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(40001, body(result).path("code").asInt());
    }

    /** 输入对象的日志与序列化不输出密码和Refresh原文。 */
    @Test
    void requestObjectsHideSecrets() {
        var login = new AuthController.LoginRequest(username, password);
        var refresh = new AuthController.RefreshRequest(UUID.randomUUID().toString());
        assertEquals("LoginRequest[内容已隐藏]", login.toString());
        assertEquals("RefreshRequest[内容已隐藏]", refresh.toString());
        assertFalse(json.writeValueAsString(login).contains(new String(password)));
        assertFalse(json.writeValueAsString(refresh).contains(refresh.refreshToken()));
    }

    /**
     * 业务只有真实Access可以访问，查询参数、自报头与HTTP Session不能代替认证。
     * @throws Exception MVC调用失败
     */
    @Test
    void securityChainRequiresBearerAndIgnoresSessionIdentity() throws Exception {
        var login = send("/auth/login", Map.of("username", username, "password", new String(password)), 200);
        String token = body(login).path("data").path("accessToken").asText();
        var success = mvc.perform(get("/api/auth-test/identity").header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(200, success.getResponse().getStatus());
        assertEquals(account.getId(), body(success).path("data").path("userId").asText());
        assertNull(success.getRequest().getSession(false)); assertNull(success.getResponse().getHeader("Set-Cookie"));
        var anonymous = mvc.perform(get("/api/auth-test/identity").param("access_token", token).header("X-User-Id", account.getId())).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        var session = new MockHttpSession(); var forged = SecurityContextHolder.createEmptyContext();
        forged.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account.getId(), null, java.util.List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT", forged);
        assertEquals(401, mvc.perform(get("/api/auth-test/identity").session(session)).andReturn().getResponse().getStatus());
        redis.opsForValue().set("auth:user:" + account.getId() + ":ver", "1");
        assertEquals(401, mvc.perform(get("/api/auth-test/identity").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
    }

    /**
     * 不提供默认登录页，匿名拒绝与认证后访问未声明资源分别为401和403。
     * @throws Exception MVC调用失败
     */
    @Test
    void distinguishesUnauthorizedAndForbiddenWithoutRedirects() throws Exception {
        var anonymous = mvc.perform(get("/login")).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus()); assertNull(anonymous.getResponse().getRedirectedUrl());
        assertEquals("Bearer", anonymous.getResponse().getHeader("WWW-Authenticate"));
        var login = send("/auth/login", Map.of("username", username, "password", new String(password)), 200);
        var forbidden = mvc.perform(get("/undeclared-resource").header("Authorization", "Bearer " + body(login).path("data").path("accessToken").asText())).andReturn();
        assertEquals(403, forbidden.getResponse().getStatus()); assertEquals(40300, body(forbidden).path("code").asInt());
        assertFalse(body(forbidden).path("traceId").asText().isBlank());
        assertEquals(401, mvc.perform(get("/auth/login")).andReturn().getResponse().getStatus());
    }

    /**
     * 发起版本1JSON请求，并收集成功会话供清理。
     * @param path 路径
     * @param request 输入对象
     * @param status 预期状态
     * @return MVC结果
     * @throws Exception MVC调用失败
     */
    private MvcResult send(String path, Object request, int status) throws Exception {
        var result = mvc.perform(post(path).header("X-Trace-Id", "auth-http-test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andReturn();
        var data = body(result).path("data");
        if (data.has("refreshToken")) {
            var claims = codec.verify(data.path("refreshToken").asText(), Purpose.REFRESH);
            keys.add("auth:sess:" + claims.sessionId()); keys.add("auth:rt:" + claims.tokenId());
        }
        assertEquals(status, result.getResponse().getStatus());
        assertEquals("auth-http-test", result.getResponse().getHeader("X-Trace-Id"));
        assertEquals("auth-http-test", body(result).path("traceId").asText());
        return result;
    }

    /**
     * 解析标准JSON响应，不把敏感响应内容用于断言消息。
     * @param result MVC结果
     * @return JSON树
     * @throws Exception 响应读取失败
     */
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }

    /** 只装配生产认证控制器、校验、响应包装和错误处理。 */
    @TestComponent
    @EnableWebMvc
    @Import({AuthController.class, ApiVersionConfiguration.class, ApiResponseAdvice.class, GlobalExceptionHandler.class,
            HttpSecurityConfiguration.class, SecurityCurrentIdentityAdapterImpl.class, IdentityProbe.class})
    static class MvcConfiguration {
        /**
         * 提供生产Advice使用的Jackson映射器。
         * @return JSON映射器
         */
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        /**
         * 加载生产错误文案。
         * @return 消息源
         */
        @Bean ResourceBundleMessageSource messageSource() {
            var source = new ResourceBundleMessageSource(); source.setBasename("messages"); source.setDefaultEncoding("UTF-8"); return source;
        }
    }

    /** 仅在专属MVC测试上下文装配的受保护身份探针，不进入生产扫描。 */
    @TestComponent
    @RestController
    static class IdentityProbe {
        private final CurrentIdentity identities;
        /**
         * 注入生产可信身份端口。
         * @param identities 当前身份端口
         */
        IdentityProbe(CurrentIdentity identities) { this.identities = identities; }
        /**
         * 返回已认证用户ID以验证过滤链。
         * @return 无会话或令牌的测试结果
         */
        @GetMapping(path = "/api/auth-test/identity", version = "1")
        com.patrick.stratum.common.api.ApiResponse<Map<String, String>> identity() {
            return com.patrick.stratum.common.api.ApiResponse.success(Map.of("userId", identities.require().getName()));
        }
    }
}
