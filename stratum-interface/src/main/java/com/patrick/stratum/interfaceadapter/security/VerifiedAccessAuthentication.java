package com.patrick.stratum.interfaceadapter.security;

import java.util.List;
import java.util.Objects;

import com.patrick.stratum.application.auth.CurrentIdentity;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** 安全包内创建的Access验证结果，不保存JWT、密码或可由调用者提升的认证标记。 */
public final class VerifiedAccessAuthentication extends AbstractAuthenticationToken {
    private final CurrentIdentity.Snapshot identity;

    /**
     * 仅供同包认证过滤器在完整校验后创建。
     * @param identity 已验证的不可变身份
     */
    VerifiedAccessAuthentication(CurrentIdentity.Snapshot identity) {
        super(List.of());
        this.identity = Objects.requireNonNull(identity, "可信身份不能为空");
        super.setAuthenticated(true);
    }

    /** {@inheritDoc} */
    @Override public Object getCredentials() { return null; }

    /** {@inheritDoc} */
    @Override public CurrentIdentity.Snapshot getPrincipal() { return identity; }

    /**
     * 只允许清除认证状态，禁止通过setter提升为认证成功。
     * @param authenticated 只能为false
     * @throws IllegalArgumentException 尝试提升认证状态
     * @apiNote 示例：{@code authentication.setAuthenticated(false)}使当前对象失效。
     */
    @Override public void setAuthenticated(boolean authenticated) {
        if (authenticated) { throw new IllegalArgumentException("认证状态只能由Access过滤器建立"); }
        super.setAuthenticated(false);
    }
}
