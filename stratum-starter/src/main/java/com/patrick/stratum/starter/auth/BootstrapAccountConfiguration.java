package com.patrick.stratum.starter.auth;

import java.util.Arrays;

import com.patrick.stratum.application.auth.BootstrapAccountApplicationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/** 启用JWT组件时，在应用就绪前通过环境变量完成初始账号创建，不提供默认凭据。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stratum.auth.jwt.enabled", havingValue = "true")
public class BootstrapAccountConfiguration {
    /**
     * 在迁移和组件装配完成后运行初始化，失败阻止应用进入就绪状态。
     * @param bootstrap Application初始化入口
     * @param environment 环境变量属性源，不从配置文件或命令行读取密码
     * @return 启动任务，执行后不持有密码副本
     * @apiNote 设置STRATUM_AUTH_ADMIN_USERNAME与STRATUM_AUTH_ADMIN_PASSWORD后启动应用。
     */
    @Bean
    public ApplicationRunner bootstrapAccountRunner(BootstrapAccountApplicationService bootstrap, ConfigurableEnvironment environment) {
        return arguments -> {
            char[] password = null;
            try {
                String raw = environmentVariable(environment, "STRATUM_AUTH_ADMIN_PASSWORD");
                password = raw == null ? null : raw.toCharArray();
                bootstrap.initialize(environmentVariable(environment, "STRATUM_AUTH_ADMIN_USERNAME"), password);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("初始账号初始化失败，请检查环境凭据与数据库状态");
            } finally {
                if (password != null) { Arrays.fill(password, '\0'); }
            }
        };
    }

    /**
     * 直接读取环境属性源的原始字符串，保留密码中的占位符、空格和特殊字符。
     * @param environment Spring环境
     * @param name 已知环境变量名
     * @return 原始值，缺失时为空
     */
    private static String environmentVariable(ConfigurableEnvironment environment, String name) {
        var source = environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (source == null) { return null; }
        Object value = source.getProperty(name);
        return value instanceof String text ? text : null;
    }
}
