package com.patrick.stratum.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立网关启动入口，仅扫描入口治理组件，不装配ERP业务或数据库。 */
@SpringBootApplication
public class GatewayApplication {
    /**
     * 使用独立WebFlux/Netty进程启动网关，配置来自环境及应用配置文件。
     * @param args Spring Boot启动参数
     * @apiNote 示例：{@code java -jar stratum-gateway-0.0.1-SNAPSHOT.jar}。
     */
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}
