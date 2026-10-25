package com.patrick.stratum.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.junit.jupiter.api.Assertions.*;

/** 独立网关运行时边界验收，避免ERP聚合或Servlet依赖意外改变WebFlux应用类型。 */
class GatewayDependencyTest {
    /** 官方Gateway及Netty可加载，应用自动识别为响应式运行时。 */
    @Test
    void resolvesReactiveGatewayAndNettyRuntime() {
        assertEquals(WebApplicationType.REACTIVE, new SpringApplication(GatewayDependencyTest.class).getWebApplicationType());
        assertDoesNotThrow(() -> Class.forName("org.springframework.cloud.gateway.route.RouteDefinition"));
        assertDoesNotThrow(() -> Class.forName("reactor.netty.http.server.HttpServer"));
    }

    /** 网关不得聚合MVC、ERP业务或业务数据库访问类。 */
    @Test
    void excludesServletBusinessAndDatabaseRuntime() {
        for (String name : java.util.List.of("org.springframework.web.servlet.DispatcherServlet",
                "com.patrick.stratum.application.command.CommandGateway", "com.patrick.stratum.domain.trade.TradeOrder",
                "org.springframework.jdbc.core.JdbcTemplate")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name));
        }
    }
}
