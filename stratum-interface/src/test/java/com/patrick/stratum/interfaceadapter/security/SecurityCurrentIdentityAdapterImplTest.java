package com.patrick.stratum.interfaceadapter.security;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/** 验证专用身份类型、拒绝伪造上下文与线程间默认隔离。 */
class SecurityCurrentIdentityAdapterImplTest {
    private final CurrentIdentity identities = new SecurityCurrentIdentityAdapterImpl();

    /** 清理本测试线程的安全状态。 */
    @AfterEach
    void cleanUp() { SecurityContextHolder.clearContext(); }

    /** 只有专用认证类型携带的已验证身份能够被应用读取，日志不含会话或凭据。 */
    @Test
    void returnsOnlyVerifiedAccessIdentity() {
        var identity = new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE);
        var authentication = new VerifiedAccessAuthentication(identity);
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        assertEquals(identity, identities.require());
        assertEquals(identity.userId().toString(), authentication.getName());
        assertNull(authentication.getCredentials());
        assertFalse(authentication.toString().contains(identity.sessionId().toString()));
        assertTrue(authentication.getAuthorities().isEmpty());
    }

    /** 缺失、匿名和普通用户名认证对象均不能伪装成本项目可信Access身份。 */
    @Test
    void rejectsMissingAnonymousAndUnrelatedAuthentication() {
        unauthorized();
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test-key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        unauthorized();
        var identity = new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), 0);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
        unauthorized();
    }

    /** 失效认证不能通过setter恢复，非法身份元数据在边界被拒绝。 */
    @Test
    void refusesAuthenticationPromotionAndInvalidMetadata() {
        var identity = new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), 0);
        var authentication = new VerifiedAccessAuthentication(identity);
        authentication.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        unauthorized();
        assertThrows(IllegalArgumentException.class, () -> authentication.setAuthenticated(true));
        assertThrows(IllegalArgumentException.class, () -> new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), -1));
        assertThrows(NullPointerException.class, () -> new CurrentIdentity.Snapshot(null, UUID.randomUUID(), 0));
    }

    /**
     * 新线程不能自动继承请求身份，调用方需要显式传递不可变快照。
     * @throws Exception 线程任务失败
     */
    @Test
    void doesNotInheritAuthenticationAcrossThreads() throws Exception {
        var identity = new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), 0);
        SecurityContextHolder.getContext().setAuthentication(new VerifiedAccessAuthentication(identity));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(this::unauthorized).get(5, TimeUnit.SECONDS);
        }
        assertEquals(identity, identities.require());
    }

    /** 断言可信身份缺失时返回统一401，无附加明细。 */
    private void unauthorized() {
        var failure = assertThrows(StratumException.class, identities::require);
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode()); assertTrue(failure.getDetails().isEmpty());
    }
}
