package com.patrick.stratum.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

import com.patrick.stratum.application.command.CommandGateway;
import com.patrick.stratum.application.command.CommandHandler;
import com.patrick.stratum.application.transaction.UnitOfWork;

/**
 * Stratum Starter 启动类，负责装配并启动整个多模块应用。
 */
@SpringBootApplication(scanBasePackages = "com.patrick.stratum")
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

    /**
     * 将跨模块命令处理器注册到统一入口，空注册表允许基础骨架单独启动。
     *
     * @param handlers 当前容器的命令处理器提供者
     * @param unitOfWork 受 Spring 事务代理管理的工作单元
     * @return 不可变路由表的命令入口
     * @apiNote 由 Spring 自动装配，业务适配层注入 CommandGateway 调用。
     */
    @Bean
    public CommandGateway commandGateway(ObjectProvider<CommandHandler<?, ?>> handlers, UnitOfWork unitOfWork) {
        return new CommandGateway(handlers.orderedStream().toList(), unitOfWork);
    }
}
