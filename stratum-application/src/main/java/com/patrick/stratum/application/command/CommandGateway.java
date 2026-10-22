package com.patrick.stratum.application.command;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.patrick.stratum.application.transaction.UnitOfWork;

/**
 * 将命令精确路由到唯一处理器，并在注入的工作单元中执行。
 * 路由表构造后不可变，可由多个请求并发共享，处理器本身须保持无请求态字段。
 */
public final class CommandGateway {

    private final Map<Class<?>, CommandHandler<?, ?>> handlers;
    private final UnitOfWork unitOfWork;

    /**
     * 构建命令路由表；重复命令注册立即失败，防止静默覆盖。
     *
     * @param handlers 非空处理器集合，允许无处理器
     * @param unitOfWork 非空工作单元实现
     * @throws NullPointerException 参数、处理器或命令类型为空
     * @throws IllegalArgumentException 同一命令被重复注册
     * @apiNote 示例：{@code new CommandGateway(List.of(handler), unitOfWork)}。
     */
    public CommandGateway(Collection<? extends CommandHandler<?, ?>> handlers, UnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        var registrations = new HashMap<Class<?>, CommandHandler<?, ?>>();
        for (var handler : Objects.requireNonNull(handlers, "handlers")) {
            var type = Objects.requireNonNull(Objects.requireNonNull(handler, "handler").commandType(), "commandType");
            if (registrations.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException("Duplicate command handler: " + type.getName());
            }
        }
        this.handlers = Map.copyOf(registrations);
    }

    /**
     * 执行已注册命令，不捕获用例异常，使工作单元能够回滚。
     *
     * @param <R> 命令返回的数据类型
     * @param command 非空命令
     * @return 非空用例结果；结果检查也在工作单元内部完成
     * @throws NullPointerException 命令或处理结果为空
     * @throws IllegalArgumentException 命令未注册
     * @throws RuntimeException 用例或事务提交失败
     * @apiNote 示例：{@code gateway.execute(new CreateNote("标题"))}。
     */
    public <R> UseCaseResult<R> execute(Command<R> command) {
        Objects.requireNonNull(command, "command");
        var handler = handlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalArgumentException("No handler for command: " + command.getClass().getName());
        }
        return unitOfWork.execute(() -> Objects.requireNonNull(invoke(handler, command), "handler result"));
    }

    /**
     * 在精确类型路由已确认后恢复处理器的泛型关联。
     *
     * @param <R> 命令结果类型
     * @param handler 与命令实际类型匹配的处理器
     * @param command 当前命令
     * @return 处理器返回的结果
     */
    @SuppressWarnings("unchecked")
    private static <R> UseCaseResult<R> invoke(CommandHandler<?, ?> handler, Command<R> command) {
        return ((CommandHandler<Command<R>, R>) handler).handle(command);
    }
}
