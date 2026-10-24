package com.patrick.stratum.starter.integration;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.TokenCodec;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import com.patrick.stratum.infrastructure.auth.RsaTokenAdapterImpl;
import com.patrick.stratum.interfaceadapter.security.BearerAuthenticationFilter;
import com.patrick.stratum.interfaceadapter.security.SecurityCurrentIdentityAdapterImpl;
import com.patrick.stratum.interfaceadapter.security.SecurityErrorWriter;
import com.patrick.stratum.interfaceadapter.web.RequestTraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 真实RSA和Redis验证请求头、会话权威校验及过滤器请求结束清理。 */
class BearerAuthenticationFilterIntegrationTest extends MariaDbIntegrationTest {
    private static TokenCodec codec;
    private final AuthSessions sessions;
    private final StringRedisTemplate redis;
    private final Set<String> keys = new HashSet<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecurityErrorWriter errors;
    private final BearerAuthenticationFilter filter;

    /**
     * 装配真实会话和生产错误输出，不使用伪造认证结果。
     * @param sessions 权威会话端口
     * @param redis 测试清理客户端
     */
    @Autowired
    BearerAuthenticationFilterIntegrationTest(AuthSessions sessions, StringRedisTemplate redis) {
        this.sessions = sessions; this.redis = redis;
        var messages = new ResourceBundleMessageSource(); messages.setBasename("messages"); messages.setDefaultEncoding("UTF-8");
        errors = new SecurityErrorWriter(json, messages);
        filter = new BearerAuthenticationFilter(() -> new AuthSessionApplicationService(null, codec, sessions), errors);
    }

    /**
     * 在进程内生成测试RSA密钥。
     * @throws Exception JCA不可用
     */
    @BeforeAll
    static void createKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
        codec = new RsaTokenAdapterImpl((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(), "filter-test", "api-test", Clock.systemUTC());
    }

    /** 清理本用例会话及线程上下文。 */
    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) { redis.delete(keys); }
        SecurityContextHolder.clearContext(); TraceIdContext.clear();
    }

    /**
     * 合法Access产生可信身份，无JWT凭据存入Authentication，完成后清空。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void establishesAndClearsTrustedIdentity() throws Exception {
        var claims = session();
        String token = codec.issue(claims.subject(), claims.sessionId(), claims.version(), Purpose.ACCESS);
        var request = request("bEaReR " + token);
        var invoked = new AtomicBoolean();
        invoke(filter, request, (incoming, outgoing) -> {
            invoked.set(true);
            CurrentIdentity.Snapshot identity = new SecurityCurrentIdentityAdapterImpl().require();
            assertEquals(claims.subject(), identity.getName()); assertEquals(claims.sessionId(), identity.sessionId());
            assertNull(SecurityContextHolder.getContext().getAuthentication().getCredentials());
            assertNull(request.getSession(false));
        });
        assertTrue(invoked.get()); assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /**
     * 缺失头保留匿名并交链决定；异常下游结束后也不泄漏身份。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void anonymousAndExceptionalRequestsDoNotLeakIdentity() throws Exception {
        invoke(filter, request(null), (incoming, outgoing) -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
        var claims = session(); var request = request("Bearer " + codec.issue(claims.subject(), claims.sessionId(), 0, Purpose.ACCESS));
        assertThrows(ServletException.class, () -> invoke(filter, request, (incoming, outgoing) -> { throw new ServletException("测试下游失败"); }));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 重复头、其他方案、额外空白、超长/损坏JWT、Refresh用途均在业务前拒绝。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void rejectsAmbiguousMalformedAndWrongPurposeHeaders() throws Exception {
        var claims = session();
        String refresh = codec.issue(claims.subject(), claims.sessionId(), 0, Purpose.REFRESH);
        for (String header : new String[]{"", "Basic abc", "Bearer ", "Bearer  a.b.c", "Bearer a.b.c, a.b.c", "Bearer a.b.c",
                "Bearer " + "x".repeat(16385), "Bearer " + refresh}) {
            rejected(filter, request(header), 401);
        }
        var duplicate = request("Bearer a.b.c"); duplicate.addHeader("Authorization", "Bearer d.e.f");
        rejected(filter, duplicate, 401);
        var request = request(null); request.addParameter("access_token", refresh);
        invoke(filter, request, (incoming, outgoing) -> assertNull(SecurityContextHolder.getContext().getAuthentication()));
    }

    /**
     * Redis状态失效与服务未配置拒绝；权威存储损坏使用脱敏500，不允许进入业务。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void failsClosedForRevokedMissingAndCorruptAuthority() throws Exception {
        var claims = session(); String token = codec.issue(claims.subject(), claims.sessionId(), 0, Purpose.ACCESS);
        var disabled = new BearerAuthenticationFilter(() -> null, errors);
        rejected(disabled, request("Bearer " + token), 401);
        var unavailable = new BearerAuthenticationFilter(() -> { throw new IllegalStateException("invalid-authority"); }, errors);
        rejected(unavailable, request("Bearer " + token), 500);
        String userKey = "auth:user:" + claims.subject() + ":ver";
        redis.opsForValue().set(userKey, "1"); rejected(filter, request("Bearer " + token), 401);
        redis.opsForValue().set(userKey, "invalid-authority"); rejected(filter, request("Bearer " + token), 401);
        redis.delete(userKey); redis.opsForList().leftPush(userKey, "invalid-authority");
        rejected(filter, request("Bearer " + token), 500);
    }

    /**
     * 安全链外的403保持独立语义、trace及标准错误模型。
     * @throws Exception 输出失败
     */
    @Test
    void writesStandardForbiddenWithoutAuthenticationChallenge() throws Exception {
        var request = request(null); var response = new MockHttpServletResponse();
        new RequestTraceFilter().doFilter(request, response, (incoming, outgoing) -> errors.write(request, response, CommonErrorCode.FORBIDDEN));
        assertEquals(403, response.getStatus());
        var body = json.readTree(response.getContentAsString());
        assertEquals(40300, body.path("code").asInt()); assertEquals("bearer-filter-test", body.path("traceId").asText());
        assertNull(response.getHeader("WWW-Authenticate")); assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /**
     * 创建随机权威会话，只记录其有限键用于清理。
     * @return 已登记Refresh声明
     */
    private TokenCodec.Claims session() {
        String user = UUID.randomUUID().toString(); UUID sid = UUID.randomUUID();
        sessions.userVersion(user);
        var claims = codec.verify(codec.issue(user, sid, 0, Purpose.REFRESH), Purpose.REFRESH);
        keys.add("auth:user:" + user + ":ver"); keys.add("auth:sess:" + sid); keys.add("auth:rt:" + claims.tokenId());
        sessions.create(claims); return claims;
    }

    /**
     * 构造仅用于测试的业务请求。
     * @param header Authorization值，空引用表示缺失
     * @return 带安全trace的请求
     */
    private static MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest("GET", "/api/erp/stock-balances");
        request.addHeader("X-Trace-Id", "bearer-filter-test");
        if (header != null) { request.addHeader("Authorization", header); }
        return request;
    }

    /**
     * 执行实际trace与认证过滤器顺序。
     * @param selected 待测认证过滤器
     * @param request 输入
     * @param chain 下游断言
     * @return 响应
     * @throws Exception 过滤器执行失败
     */
    private static MockHttpServletResponse invoke(BearerAuthenticationFilter selected, MockHttpServletRequest request, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        new RequestTraceFilter().doFilter(request, response, (incoming, outgoing) -> selected.doFilter(incoming, outgoing, chain));
        return response;
    }

    /**
     * 断言业务未执行、状态正确且没有凭据回显。
     * @param selected 待测过滤器
     * @param request 输入
     * @param status 预期状态
     * @throws Exception 过滤器执行失败
     */
    private void rejected(BearerAuthenticationFilter selected, MockHttpServletRequest request, int status) throws Exception {
        var response = invoke(selected, request, (incoming, outgoing) -> fail("无效认证不能进入业务"));
        assertEquals(status, response.getStatus());
        var body = json.readTree(response.getContentAsString());
        assertEquals(status * 100, body.path("code").asInt()); assertEquals("bearer-filter-test", body.path("traceId").asText());
        assertTrue(body.path("details").isEmpty()); assertFalse(response.getContentAsString().contains("invalid-authority"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        if (status == 401) { assertEquals("Bearer", response.getHeader("WWW-Authenticate")); }
    }
}
