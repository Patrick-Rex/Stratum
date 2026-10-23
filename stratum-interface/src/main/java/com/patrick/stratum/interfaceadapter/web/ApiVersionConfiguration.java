package com.patrick.stratum.interfaceadapter.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 使用 Spring MVC 版本条件统一解析 X-API-Version，缺省版本为 1。 */
@Configuration(proxyBeanMethods = false)
public class ApiVersionConfiguration implements WebMvcConfigurer {

    /** {@inheritDoc} */
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader("X-API-Version")
                .setDefaultVersion("1")
                .detectSupportedVersions(false)
                .addSupportedVersions("1");
    }
}
