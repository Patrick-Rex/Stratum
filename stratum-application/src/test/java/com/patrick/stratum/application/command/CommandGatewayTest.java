package com.patrick.stratum.application.command;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.patrick.stratum.application.transaction.UnitOfWork;

import static org.junit.jupiter.api.Assertions.*;

/** 验证命令路由和工作单元调用边界，不替代真实数据库事务测试。 */
class CommandGatewayTest {

    /** 成功用例在工作单元内只执行一次，并保留结果类型。 */
    @Test
    void executesHandlerInsideWorkUnit() {
        var work = new TrackingWork();
        var calls = new AtomicInteger();
        var gateway = new CommandGateway(List.of(handler(command -> {
            assertTrue(work.active);
            calls.incrementAndGet();
            return new UseCaseResult<>(command.value());
        })), work);
        assertEquals("result", gateway.execute(new SampleCommand("result")).data());
        assertEquals(1, calls.get());
        assertEquals(1, work.commits);
        assertFalse(work.active);
    }

    /** 重复注册应在启动构建路由表时失败。 */
    @Test
    void rejectsDuplicateHandlers() {
        var handler = handler(command -> new UseCaseResult<>(command.value()));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandGateway(List.of(handler, handler), new TrackingWork()));
    }

    /** 未注册的命令不应开启工作单元。 */
    @Test
    void rejectsUnregisteredCommandBeforeStartingWork() {
        var work = new TrackingWork();
        var gateway = new CommandGateway(List.of(), work);
        assertThrows(IllegalArgumentException.class, () -> gateway.execute(new SampleCommand("unknown")));
        assertEquals(0, work.starts);
    }

    /** 用例失败应以同一个异常对象离开工作单元，不返回伪成功结果。 */
    @Test
    void propagatesFailureThroughWorkUnit() {
        var work = new TrackingWork();
        var failure = new IllegalStateException("failure");
        var gateway = new CommandGateway(List.of(handler(command -> { throw failure; })), work);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> gateway.execute(new SampleCommand("failure"))));
        assertEquals(1, work.rollbacks);
        assertEquals(0, work.commits);
        assertFalse(work.active);
    }

    /** 非法空结果在工作单元内部失败，避免检查发生于提交之后。 */
    @Test
    void rejectsNullResultBeforeCommit() {
        var work = new TrackingWork();
        var gateway = new CommandGateway(List.of(handler(command -> null)), work);
        assertThrows(NullPointerException.class, () -> gateway.execute(new SampleCommand("null")));
        assertEquals(1, work.rollbacks);
        assertEquals(0, work.commits);
    }

    /** 空命令在进入工作单元之前被拒绝。 */
    @Test
    void rejectsNullCommand() {
        var work = new TrackingWork();
        var gateway = new CommandGateway(List.of(), work);
        assertThrows(NullPointerException.class, () -> gateway.execute(null));
        assertEquals(0, work.starts);
    }

    /** 构造只用于路由测试的不可变命令。
     * @param value 期望返回值
     */
    private record SampleCommand(String value) implements Command<String> { }

    /**
     * 创建可注入不同行为的测试处理器。
     * @param action 测试行为
     * @return 命令处理器
     */
    private static CommandHandler<SampleCommand, String> handler(Function<SampleCommand, UseCaseResult<String>> action) {
        return new CommandHandler<>() {
            /** {@inheritDoc} */
            @Override
            public Class<SampleCommand> commandType() { return SampleCommand.class; }

            /** {@inheritDoc} */
            @Override
            public UseCaseResult<String> handle(SampleCommand command) { return action.apply(command); }
        };
    }

    /** 记录调用顺序的测试工作单元，无数据库事务语义。 */
    private static final class TrackingWork implements UnitOfWork {
        private boolean active;
        private int starts;
        private int commits;
        private int rollbacks;

        /** {@inheritDoc} */
        @Override
        public <T> T execute(Supplier<T> action) {
            starts++;
            active = true;
            try {
                T result = action.get();
                commits++;
                return result;
            } catch (RuntimeException | Error failure) {
                rollbacks++;
                throw failure;
            } finally {
                active = false;
            }
        }
    }
}
