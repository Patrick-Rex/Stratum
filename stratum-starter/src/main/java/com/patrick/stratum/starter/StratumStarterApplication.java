package com.patrick.stratum.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stratum Starter 启动类，负责装配并启动整个多模块应用。
 */
@SpringBootApplication
public class StratumStarterApplication {

    /**
     * 应用主入口方法，用于初始化 Spring Boot 容器并启动 Web 服务。
     *
     * @param args 启动参数，例如 --server.port=8080。
     * @return 无返回值。
     * @throws RuntimeException 当启动过程发生不可恢复错误时抛出运行时异常。
     */
    public static void main(String[] args) {
        SpringApplication.run(StratumStarterApplication.class, args);
    }
}
