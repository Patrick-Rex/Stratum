package com.patrick.stratum.interfaceadapter.security;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只从单个Authorization Bearer头建立可信身份，完整校验JWT和Redis后才继续请求。 */
public final class BearerAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern COMPACT_JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private final Supplier<AuthSessionApplicationService> authentication;
    private final SecurityErrorWriter errors;

    /**
     * 使用延迟服务供应者，JWT未配置时拒绝Bearer而不是启用默认身份。
     * @param authentication 已启用时返回认证服务，未启用可返回空
     * @param errors 标准错误输出器
     * @apiNote 由安全链创建，不能另作为Servlet过滤器重复注册。
     */
    public BearerAuthenticationFilter(Supplier<AuthSessionApplicationService> authentication, SecurityErrorWriter errors) {
        this.authentication = authentication; this.errors = errors;
    }

    /** {@inheritDoc} */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        try {
            try {
                var identity = authenticate(request);
                if (identity != null) {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new VerifiedAccessAuthentication(identity));
                    SecurityContextHolder.setContext(context);
                }
            } catch (StratumException failure) {
                var code = failure.getErrorCode() == CommonErrorCode.UNAUTHORIZED
                        ? CommonErrorCode.UNAUTHORIZED : CommonErrorCode.INTERNAL_ERROR;
                errors.write(request, response, code);
                return;
            } catch (RuntimeException failure) {
                errors.write(request, response, CommonErrorCode.INTERNAL_ERROR);
                return;
            }
            chain.doFilter(request, response);
        } finally { SecurityContextHolder.clearContext(); }
    }

    /**
     * 校验有界单头输入，只接受Access用途；不存在头时交由安全链决定公开或拒绝。
     * @param request 当前请求
     * @return 已验证身份，未提供头时为空
     */
    private CurrentIdentity.Snapshot authenticate(HttpServletRequest request) {
        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (headers == null || !headers.hasMoreElements()) { return null; }
        String header = headers.nextElement();
        if (headers.hasMoreElements() || header == null || header.length() > 16391 || header.length() <= 7
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) { throw unauthorized(); }
        String token = header.substring(7);
        if (!COMPACT_JWT.matcher(token).matches()) { throw unauthorized(); }
        var service = authentication.get();
        if (service == null) { throw unauthorized(); }
        var claims = service.authenticateAccess(token);
        try {
            UUID userId = UUID.fromString(claims.subject());
            if (!userId.toString().equals(claims.subject())) { throw unauthorized(); }
            return new CurrentIdentity.Snapshot(userId, claims.sessionId(), claims.version());
        } catch (IllegalArgumentException invalid) { throw unauthorized(); }
    }

    /**
     * 构造无输入信息的统一认证错误。
     * @return 401业务异常
     */
    private static StratumException unauthorized() { return new StratumException(CommonErrorCode.UNAUTHORIZED); }

    /** {@inheritDoc} */
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }

    /** {@inheritDoc} */
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
}
