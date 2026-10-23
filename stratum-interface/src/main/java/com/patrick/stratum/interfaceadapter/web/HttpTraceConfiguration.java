package com.patrick.stratum.interfaceadapter.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 装配请求关联过滤器，使正常、异步和错误分派使用同一请求标识。 */
@Configuration(proxyBeanMethods = false)
public class HttpTraceConfiguration {

    /**
     * 注册先于业务处理的过滤器，异步工作线程仍需由执行器显式传递上下文。
     * @return 支持异步与错误再分派的过滤器注册
     * @apiNote 使用示例：由 Starter 组件扫描自动装配。
     */
    @Bean
    public FilterRegistrationBean<RequestTraceFilter> requestTraceFilter() {
        var registration = new FilterRegistrationBean<>(new RequestTraceFilter());
        registration.setName("requestTraceFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
