package com.patrick.stratum.starter.auth;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.application.auth.LocalIdentityApplicationService;
import com.patrick.stratum.domain.auth.AuthSessions;
import com.patrick.stratum.domain.auth.AccountPermissions;
import com.patrick.stratum.domain.auth.TokenCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只在显式启用JWT及其外部密钥后装配认证会话编排。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stratum.auth.jwt.enabled", havingValue = "true")
public class AuthSessionConfiguration {
    /**
     * 组合本地身份、密码学与权威会话端口，不依赖具体基础设施实现。
     * @param identities 身份入口
     * @param tokens 由密钥配置产生的Token端口
     * @param sessions 权威会话端口
     * @param permissions 权威账号状态与权限端口
     * @return Application会话编排
     * @apiNote 由Spring装配，HTTP入口注入返回的应用服务。
     */
    @Bean
    public AuthSessionApplicationService authSessionApplicationService(LocalIdentityApplicationService identities,
            TokenCodec tokens, AuthSessions sessions, AccountPermissions permissions) {
        return new AuthSessionApplicationService(identities, tokens, sessions, permissions);
    }
}
