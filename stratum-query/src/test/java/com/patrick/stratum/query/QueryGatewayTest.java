package com.patrick.stratum.query;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.query.dto.QueryResult;
import com.patrick.stratum.query.spec.QuerySpec;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.*;

/** 验证查询路由、分页结果约束、事务隔离入口及 Spring 装配。 */
class QueryGatewayTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private static final Duration SLOW = Duration.ofMillis(300);

    /** 两种请求精确路由到各自处理器，显式 trace 在处理期间生效并在结束后恢复。 */
    @Test
    void routesExactTypeAndRestoresTrace() {
        var first = handler("test.first", FirstQuery.class, (query, context) -> {
            assertEquals("query-trace", TraceIdContext.getTraceId().orElseThrow());
            assertEquals("query-trace", context.traceId());
            return page("第一类");
        });
        var second = handler("test.second", SecondQuery.class, (query, context) -> page("第二类"));
        var gateway = new QueryGateway(List.of(first, second), metrics, SLOW);
        TraceIdContext.setTraceId("outer-trace");
        assertEquals("第一类", gateway.execute(new FirstQuery(spec()), QueryContext.anonymous("query-trace")).getItems().getFirst());
        assertEquals("outer-trace", TraceIdContext.getTraceId().orElseThrow());
        assertEquals("第二类", gateway.execute(new SecondQuery(spec()), QueryContext.anonymous("query-trace")).getItems().getFirst());
        assertEquals(1, metrics.get("stratum.query.duration").tags("query", "test.first", "result", "success").timer().count());
    }

    /** 启动时拒绝重复类型或重复 code，不允许最后一个处理器静默覆盖。 */
    @Test
    void rejectsDuplicateRegistrations() {
        var first = handler("test.first", FirstQuery.class, (query, context) -> page("x"));
        assertThrows(IllegalArgumentException.class, () -> new QueryGateway(List.of(first,
                handler("test.other", FirstQuery.class, (query, context) -> page("x"))), metrics, SLOW));
        assertThrows(IllegalArgumentException.class, () -> new QueryGateway(List.of(first,
                handler("test.first", SecondQuery.class, (query, context) -> page("x"))), metrics, SLOW));
    }

    /** 未注册类型及空结果被拒绝，失败也记录指标且不泄漏线程 trace。 */
    @Test
    void rejectsUnknownAndNullResults() {
        var gateway = new QueryGateway(List.of(handler("test.first", FirstQuery.class, (query, context) -> null)), metrics, SLOW);
        assertThrows(IllegalArgumentException.class,
                () -> gateway.execute(new SecondQuery(spec()), QueryContext.anonymous("trace")));
        assertThrows(NullPointerException.class,
                () -> gateway.execute(new FirstQuery(spec()), QueryContext.anonymous("trace")));
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        assertEquals(1, metrics.get("stratum.query.duration").tags("query", "test.first", "result", "error").timer().count());
    }

    /** 处理器异常原样传播，查询入口不把查询失败伪装成空列表。 */
    @Test
    void preservesHandlerFailure() {
        var failure = new IllegalStateException("查询失败");
        var gateway = new QueryGateway(List.of(handler("test.first", FirstQuery.class,
                (query, context) -> { throw failure; })), metrics, SLOW);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> gateway.execute(new FirstQuery(spec()), QueryContext.anonymous("trace"))));
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 处理器不能私自扩展请求的分页大小，也不能让读链路隐式加入活动写事务。 */
    @Test
    void enforcesPaginationAndTransactionBoundary() {
        var gateway = new QueryGateway(List.of(handler("test.first", FirstQuery.class,
                (query, context) -> new QueryResult<>(1, 200, 1, List.of("x")))), metrics, SLOW);
        assertThrows(IllegalStateException.class,
                () -> gateway.execute(new FirstQuery(spec()), QueryContext.anonymous("trace")));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class,
                    () -> gateway.execute(new FirstQuery(spec()), QueryContext.anonymous("trace")));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    /** Spring 从处理器 bean 装配可调用路由，而不是只验证手工构造。 */
    @Test
    void springConfigurationCollectsHandlers() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, () -> metrics);
            context.registerBean("probeHandler", QueryHandler.class,
                    () -> handler("test.first", FirstQuery.class, (query, queryContext) -> page("Spring")));
            context.register(QueryConfiguration.class);
            context.refresh();
            var gateway = context.getBean(QueryGateway.class);
            assertEquals("Spring", gateway.execute(new FirstQuery(spec()), QueryContext.anonymous("trace")).getItems().getFirst());
        }
    }

    /** 每项测试关闭自身注册表并清理线程上下文。 */
    @AfterEach
    void cleanup() { metrics.close(); TraceIdContext.clear(); }

    /**
     * 构造无过滤的测试查询规范。
     * @return 规范
     */
    private static QuerySpec<Fields> spec() { return QuerySpec.create(1, 20, List.of(), List.of(), Map.of()); }

    /**
     * 构造一条标准分页结果。
     * @param text 输出值
     * @return 分页结果
     */
    private static QueryResult<String> page(String text) { return new QueryResult<>(1, 20, 1, List.of(text)); }

    /**
     * 创建类型明确的测试处理器。
     * @param <Q> 请求类型
     * @param code 查询标识
     * @param type 请求类
     * @param reader 读取行为
     * @return 处理器
     */
    private static <Q extends Query<String>> QueryHandler<Q, String> handler(String code, Class<Q> type,
            BiFunction<Q, QueryContext, QueryResult<String>> reader) {
        return new QueryHandler<>() {
            /** {@inheritDoc} */
            @Override public String queryCode() { return code; }
            /** {@inheritDoc} */
            @Override public Class<Q> queryType() { return type; }
            /** {@inheritDoc} */
            @Override public QueryResult<String> handle(Q query, QueryContext context) { return reader.apply(query, context); }
        };
    }

    /** 测试字段类型，不需要业务字段或数据库。 */
    private enum Fields { ID }
    /**
     * 第一类测试请求。
     * @param spec 查询规范
     */
    private record FirstQuery(QuerySpec<Fields> spec) implements Query<String> { }
    /**
     * 第二类测试请求。
     * @param spec 查询规范
     */
    private record SecondQuery(QuerySpec<Fields> spec) implements Query<String> { }
}
