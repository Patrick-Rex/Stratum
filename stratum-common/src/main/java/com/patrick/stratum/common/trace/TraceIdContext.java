package com.patrick.stratum.common.trace;

import java.util.Optional;
import java.util.UUID;

/**
 * traceId 线程上下文工具，负责在单线程执行链路中存取与清理追踪标识。
 * 适用场景：Filter、AOP、响应封装等横切组件统一传递 traceId。
 */
public final class TraceIdContext {

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    private TraceIdContext() {
    }

    /**
     * 显式设置当前线程 traceId。
     *
     * @param traceId 链路追踪标识，不能为空白。
     * @return 无返回值。
     * @throws IllegalArgumentException 当 traceId 为空白字符串时抛出异常。
     * @apiNote 使用示例：TraceIdContext.setTraceId("trace-001")
     */
    public static void setTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId cannot be blank");
        }
        TRACE_ID_HOLDER.set(traceId);
    }

    /**
     * 获取当前线程 traceId（可选）。
     *
     * @param 无。
     * @return Optional 包裹的 traceId；未设置时返回 Optional.empty()。
     * @throws 无。
     * @apiNote 使用示例：TraceIdContext.getTraceId().orElse("-")
     */
    public static Optional<String> getTraceId() {
        return Optional.ofNullable(TRACE_ID_HOLDER.get());
    }

    /**
     * 获取当前线程 traceId（必填）。
     *
     * @param 无。
     * @return 已存在或新生成的 traceId。
     * @throws 无。
     * @apiNote 使用示例：String traceId = TraceIdContext.getRequiredTraceId()
     */
    public static String getRequiredTraceId() {
        String current = TRACE_ID_HOLDER.get();
        if (current == null || current.isBlank()) {
            current = UUID.randomUUID().toString();
            TRACE_ID_HOLDER.set(current);
        }
        return current;
    }

    /**
     * 清理当前线程 traceId，避免线程复用导致上下文污染。
     *
     * @param 无。
     * @return 无返回值。
     * @throws 无。
     * @apiNote 使用示例：finally { TraceIdContext.clear(); }
     */
    public static void clear() {
        TRACE_ID_HOLDER.remove();
    }
}
