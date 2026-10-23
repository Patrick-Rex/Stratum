package com.patrick.stratum.starter.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 加载完整应用装配，强制集成测试显式连接本机独立 stratum_it 数据库。 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class MariaDbIntegrationTest {

    /**
     * 从测试专用环境变量绑定数据库；缺失配置直接失败，不能静默跳过集成测试。
     * @param registry 测试属性注册器
     * @throws IllegalStateException 未指定独立测试数据库或凭据时抛出
     */
    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        String url = requiredEnvironment("STRATUM_IT_DB_URL");
        if (!url.matches("jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/stratum_it")) {
            throw new IllegalStateException("集成测试仅允许本机独立 stratum_it 数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnvironment("STRATUM_IT_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("STRATUM_IT_DB_PASSWORD"));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
    }

    /**
     * 获取必须显式指定的测试配置，不在异常中输出环境变量值。
     * @param name 环境变量名
     * @return 非空配置
     * @throws IllegalStateException 配置缺失时抛出
     */
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少集成测试环境变量：" + name);
        }
        return value;
    }
}
