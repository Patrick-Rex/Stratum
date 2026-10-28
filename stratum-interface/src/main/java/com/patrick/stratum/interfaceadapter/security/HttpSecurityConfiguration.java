package com.patrick.stratum.interfaceadapter.security;

import com.patrick.stratum.application.auth.AuthSessionApplicationService;
import com.patrick.stratum.common.error.CommonErrorCode;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/** 显式无状态安全链，JWT未配置时也不允许匿名业务访问，不提供默认登录或账号。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(MetricsSourcePolicy.Properties.class)
public class HttpSecurityConfiguration {
    /**
     * 创建指标来源策略，并禁止容器或框架依据转发头改写直连地址。
     * @param properties 允许来源配置
     * @param environment 已合并的应用配置
     * @return 已验证的来源策略
     * @throws IllegalStateException 启用了转发头地址改写
     * @apiNote 示例：默认只允许本机回环，来源列表完整替换默认值。
     */
    @Bean
    public MetricsSourcePolicy metricsSourcePolicy(MetricsSourcePolicy.Properties properties, Environment environment) {
        String forwarding = environment.getProperty("server.forward-headers-strategy", "none");
        if (!"none".equalsIgnoreCase(forwarding)) throw new IllegalStateException("指标来源控制要求关闭转发头地址改写");
        return new MetricsSourcePolicy(properties);
    }

    /**
     * 统一MVC外安全错误，保持既有trace与文案。
     * @param json JSON映射器
     * @param messages 文案源
     * @return 安全错误输出器
     * @apiNote 由链内认证入口及拒绝处理器共享。
     */
    @Bean
    public SecurityErrorWriter securityErrorWriter(JsonMapper json, MessageSource messages) { return new SecurityErrorWriter(json, messages); }

    /**
     * 显式公开认证提交、健康和接口文档读取，其余业务必须先认证，未声明路径拒绝。
     * @param http Spring Security构建器
     * @param authentication 可选的已启用认证应用服务
     * @param errors 安全错误输出器
     * @param metricsSources 指标直连来源策略
     * @return 不创建或读取HTTP Session的安全链
     * @throws Exception 安全链配置失败
     * @apiNote API仅接受Authorization Bearer；禁用CSRF以匹配无Cookie认证的JSON协议。
     */
    @Bean
    public SecurityFilterChain stratumSecurityFilterChain(HttpSecurity http,
            ObjectProvider<AuthSessionApplicationService> authentication, SecurityErrorWriter errors,
            MetricsSourcePolicy metricsSources) throws Exception {
        var metrics = PathPatternRequestMatcher.pathPattern("/actuator/prometheus/**");
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, failure) -> errors.write(request, response,
                                metrics.matches(request) ? CommonErrorCode.FORBIDDEN : CommonErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, failure) -> errors.write(request, response, CommonErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/v3/api-docs/**",
                                "/doc.html", "/webjars/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(metrics).access((authenticationSupplier, context) ->
                                new AuthorizationDecision(metricsSources.allows(context.getRequest().getRemoteAddr())))
                        .requestMatchers("/api/**", "/auth/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new BearerAuthenticationFilter(authentication::getIfAvailable, errors), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 注册框架安全代理，确保trace过滤器先执行；不另行注册Bearer过滤器。
     * @return 覆盖请求、异步及错误分派的单个安全代理
     * @apiNote Starter的Servlet容器自动应用此注册，测试可直接调用同一个springSecurityFilterChain。
     */
    @Bean
    public DelegatingFilterProxyRegistrationBean securityFilterRegistration() {
        var registration = new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
