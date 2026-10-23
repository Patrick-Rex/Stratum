package com.patrick.stratum.query;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.query.dto.QueryResult;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/** 精确类型查询路由，统一上下文快照、分页结果校验、耗时指标与链路日志。 */
@Slf4j
public final class QueryGateway {
    private final Map<Class<?>, Registration> handlers;
    private final MeterRegistry metrics;
    private final long slowNanos;

    /**
     * 冻结处理器注册表，拒绝重复类型与 queryCode，防止路由覆盖和指标混淆。
     * @param handlers 查询处理器集合
     * @param metrics 指标注册表
     * @param slowThreshold 慢查询阈值，正数
     * @throws IllegalArgumentException 注册信息重复或阈值不合法时抛出
     * @apiNote 示例：new QueryGateway(List.of(handler), registry, Duration.ofMillis(300))。
     */
    public QueryGateway(Collection<? extends QueryHandler<?, ?>> handlers, MeterRegistry metrics, Duration slowThreshold) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (slowThreshold.isNegative() || slowThreshold.isZero()) { throw new IllegalArgumentException("慢查询阈值必须为正数"); }
        this.slowNanos = slowThreshold.toNanos();
        var registrations = new HashMap<Class<?>, Registration>();
        var codes = new HashSet<String>();
        for (var handler : Objects.requireNonNull(handlers, "handlers")) {
            Objects.requireNonNull(handler, "handler");
            String code = handler.queryCode();
            if (code == null || !code.matches("[a-z][a-z0-9_.-]{0,127}") || !codes.add(code)) {
                throw new IllegalArgumentException("queryCode 非法或重复");
            }
            Class<?> type = Objects.requireNonNull(handler.queryType(), "queryType");
            if (registrations.putIfAbsent(type, new Registration(code, handler)) != null) {
                throw new IllegalArgumentException("查询类型重复注册：" + type.getName());
            }
        }
        this.handlers = Map.copyOf(registrations);
    }

    /**
     * 路由查询并统一观察结果；拒绝在活动事务内调用，避免隐式加入写工作单元。
     * @param <R> 单条读模型类型
     * @param query 请求及白名单规范
     * @param context 服务器构造的可信上下文
     * @return 分页与请求匹配的非空结果
     * @throws IllegalArgumentException 未注册请求时抛出
     * @throws IllegalStateException 活动事务内调用或处理器违反结果契约时抛出
     * @apiNote 示例：gateway.execute(query, QueryContext.anonymous(traceId))。
     */
    public <R> QueryResult<R> execute(Query<R> query, QueryContext context) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(context, "context");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("QueryGateway 不能加入活动工作单元");
        }
        var registration = handlers.get(query.getClass());
        if (registration == null) { throw new IllegalArgumentException("未注册查询类型：" + query.getClass().getName()); }
        var spec = Objects.requireNonNull(query.spec(), "query spec");
        var snapshot = new QueryContext.Snapshot(context.traceId(), context.operatorId(), context.permissions());
        String previousTrace = TraceIdContext.getTraceId().orElse(null);
        long start = System.nanoTime();
        int resultCount = 0;
        String outcome = "error";
        try {
            TraceIdContext.setTraceId(snapshot.traceId());
            var result = Objects.requireNonNull(invoke(registration.handler(), query, snapshot), "query result");
            if (result.getPage() != spec.getPageRequest().getPage()
                    || result.getPageSize() != spec.getPageRequest().getPageSize()) {
                throw new IllegalStateException("查询处理器返回的分页与请求不一致");
            }
            resultCount = result.getItems().size();
            outcome = "success";
            return result;
        } finally {
            try { observe(registration.code(), snapshot.traceId(), System.nanoTime() - start, resultCount, outcome); }
            finally {
                if (previousTrace == null) { TraceIdContext.clear(); }
                else { TraceIdContext.setTraceId(previousTrace); }
            }
        }
    }

    /**
     * 仅以启动时注册的 queryCode 作为标签，不记录查询参数或权限数据。
     * @param code 稳定查询标识
     * @param traceId 链路标识
     * @param elapsed 纳秒耗时
     * @param resultCount 当前页条数
     * @param outcome 固定成功/失败标签
     */
    private void observe(String code, String traceId, long elapsed, int resultCount, String outcome) {
        metrics.timer("stratum.query.duration", "query", code, "result", outcome).record(elapsed, TimeUnit.NANOSECONDS);
        var event = elapsed >= slowNanos || outcome.equals("error") ? log.atWarn() : log.atInfo();
        long millis = TimeUnit.NANOSECONDS.toMillis(elapsed);
        event.addKeyValue("queryCode", code).addKeyValue("durationMs", millis).addKeyValue("resultCount", resultCount)
                .addKeyValue("traceId", traceId).addKeyValue("result", outcome)
                .log("查询结束 queryCode={} durationMs={} resultCount={} traceId={} result={}",
                        code, millis, resultCount, traceId, outcome);
    }

    /**
     * 在精确请求类型已匹配之后恢复泛型关联。
     * @param <R> 读模型类型
     * @param handler 匹配处理器
     * @param query 查询请求
     * @param context 只读上下文快照
     * @return 查询结果
     */
    @SuppressWarnings("unchecked")
    private static <R> QueryResult<R> invoke(QueryHandler<?, ?> handler, Query<R> query, QueryContext context) {
        return ((QueryHandler<Query<R>, R>) handler).handle(query, context);
    }

    /**
     * 启动时固定的路由元数据，不重复读取可变处理器属性。
     * @param code 查询标识
     * @param handler 处理器
     */
    private record Registration(String code, QueryHandler<?, ?> handler) { }
}
