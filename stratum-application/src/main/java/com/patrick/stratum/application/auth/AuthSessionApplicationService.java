package com.patrick.stratum.application.auth;

import java.util.UUID;

import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.TokenCodec;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import lombok.RequiredArgsConstructor;

/** 编排密码身份、JWT与Redis权威会话，仅在会话操作确认成功后返回令牌。 */
@RequiredArgsConstructor
public class AuthSessionApplicationService {
    private final LocalIdentityApplicationService identities;
    private final TokenCodec tokens;
    private final AuthSessions sessions;
    private final AccountPermissions permissions;

    /**
     * 验证本地账号后创建独立会话，失败不返回已签发但未登记的令牌。
     * @param username 登录标识
     * @param password 消费后清零的密码；调用方仍须保证外层清理
     * @param traceId 服务器链路标识
     * @return 已登记会话的令牌对
     * @apiNote 示例：{@code authentication.login(username, password, traceId)}。
     */
    public Tokens login(String username, char[] password, String traceId) {
        String subject = identities.authenticate(username, password, traceId).toString();
        long version = sessions.userVersion(subject);
        var result = issue(subject, UUID.randomUUID(), version);
        sessions.create(tokens.verify(result.refreshToken(), Purpose.REFRESH));
        return result;
    }

    /**
     * 消费旧Refresh并生成同一会话的唯一后继，旧令牌重放由权威存储拒绝。
     * @param refreshToken 敏感Refresh原文，不得记录日志
     * @return 原子旋转确认成功后的令牌对
     * @apiNote 示例：{@code authentication.refresh(refreshToken)}；失败禁止将候选令牌返回客户端。
     */
    public Tokens refresh(String refreshToken) {
        var previous = tokens.verify(refreshToken, Purpose.REFRESH);
        requireEnabled(previous.subject());
        var result = issue(previous.subject(), previous.sessionId(), previous.version());
        sessions.rotate(previous, tokens.verify(result.refreshToken(), Purpose.REFRESH));
        return result;
    }

    /**
     * 按Access用途验证JWT，并逐请求校验Redis会话、撤销版本和账号启用状态。
     * @param accessToken 敏感Access原文
     * @return 服务器验证的声明，可构造HTTP可信主体，不代表已通过RBAC
     * @apiNote 示例：{@code authentication.authenticateAccess(bearer)}。
     */
    public TokenCodec.Claims authenticateAccess(String accessToken) {
        var claims = tokens.verify(accessToken, Purpose.ACCESS);
        sessions.requireActive(claims);
        requireEnabled(claims.subject());
        return claims;
    }

    /**
     * 经权威只读端口逐次检查账号状态，不缓存启用结果，不信任客户端主体。
     * @param subject 已通过密码学验证的标准UUID
     */
    private void requireEnabled(String subject) {
        UUID userId = UUID.fromString(subject);
        String traceId = TraceIdContext.getTraceId().orElseGet(() -> UUID.randomUUID().toString());
        permissions.findByUserId(userId, traceId).filter(snapshot -> snapshot.userId().equals(userId) && snapshot.enabled())
                .orElseThrow(() -> new StratumException(CommonErrorCode.UNAUTHORIZED));
    }

    /**
     * 生成候选令牌，调用者必须完成权威会话操作后才能返回。
     * @param subject 已验证用户标识
     * @param session 会话UUID
     * @param version 用户撤销版本
     * @return 尚待会话确认的候选令牌
     */
    private Tokens issue(String subject, UUID session, long version) {
        return new Tokens(tokens.issue(subject, session, version, Purpose.ACCESS),
                tokens.issue(subject, session, version, Purpose.REFRESH), Purpose.ACCESS.lifetime().toSeconds());
    }

    /**
     * 仅用于受保护响应的敏感令牌对，日志描述始终脱敏。
     * @param accessToken Access原文
     * @param refreshToken Refresh原文
     * @param expiresIn Access有效秒数
     */
    public record Tokens(String accessToken, String refreshToken, long expiresIn) {
        /**
         * 隐藏所有令牌内容。
         * @return 固定脱敏描述
         * @apiNote 示例：{@code result.toString()}不输出JWT。
         */
        @Override public String toString() { return "AuthTokens[内容已隐藏]"; }
    }
}
