package com.patrick.stratum.application.command;

/**
 * 单个写命令的处理契约；统一入口负责工作单元，处理器编排领域行为。
 *
 * @param <C> 支持的命令类型
 * @param <R> 结果数据类型
 */
public interface CommandHandler<C extends Command<R>, R> {

    /**
     * 返回精确支持的命令类型，供启动时建立唯一的路由表。
     *
     * @return 非空命令类型
     * @apiNote 示例：{@code return CreateNote.class;}
     */
    Class<C> commandType();

    /**
     * 在调用方提供的工作单元中执行写用例，失败时抛出异常触发回滚。
     *
     * @param command 已完成输入适配的非空命令
     * @return 非空结果容器，数据本身可以为空
     * @throws RuntimeException 用例执行失败，必须传播到工作单元
     * @apiNote 示例：{@code return new UseCaseResult<>(aggregate.getId());}
     */
    UseCaseResult<R> handle(C command);
}
