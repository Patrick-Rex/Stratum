package com.patrick.stratum.common.trace;

import java.util.Optional;

/**
 * 标准追踪上下文边界，不包含业务关联ID或baggage。
 * <p>示例：捕获有效上下文供持久化，在异步线程中用try-with-resources恢复。</p>
 */
public interface StandardTraceContext {
    /**
     * 捕获当前有效标准上下文；没有有效跨度时返回空值，不伪造标识。
     * @return 可保存的规范字段或空值
     * @apiNote 示例：{@code Optional<Carrier> trace = context.capture()}。
     */
    Optional<Carrier> capture();

    /**
     * 规范化不可信持久字段；无效parent为空，无效state只丢弃state。
     * @param traceparent 可空标准父上下文
     * @param tracestate 可空标准状态
     * @return 规范化上下文或空值
     * @apiNote 示例：{@code context.normalize(parent, state)}。
     */
    Optional<Carrier> normalize(String traceparent, String tracestate);

    /**
     * 从根上下文恢复指定上下文；空值或无效值不继承当前线程上下文。
     * 作用域必须在创建它的线程按后进先出顺序关闭，不跨线程传递。
     * @param carrier 可空的规范上下文
     * @return 关闭时恢复原线程上下文的作用域
     * @apiNote 示例：{@code try (var scope = context.restore(carrier)) { work.run(); }}。
     */
    TraceScope restore(Optional<Carrier> carrier);

    /**
     * 有界标准上下文字段，state的协议语义由normalize校验，不输出原文到字符串摘要。
     * @param traceparent 非零、规范版本00父上下文
     * @param tracestate 可为空的有界ASCII状态文本
     */
    record Carrier(String traceparent, String tracestate) {
        /**
         * 验证边界和安全字符；协议解析在上下文适配器完成。
         * @param traceparent 规范父上下文
         * @param tracestate 可为空的标准状态
         * @throws IllegalArgumentException 父标识或状态不合法
         * @apiNote 示例：使用{@code context.normalize(parent, state)}构造规范值。
         */
        public Carrier {
            if (traceparent == null || !traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                    || traceparent.substring(3, 35).equals("0".repeat(32)) || traceparent.substring(36, 52).equals("0".repeat(16))) {
                throw new IllegalArgumentException("标准追踪父上下文不合法");
            }
            if (tracestate == null) tracestate = "";
            if (tracestate.length() > 512 || tracestate.chars().anyMatch(c -> c < 32 || c > 126)) {
                throw new IllegalArgumentException("标准追踪状态不合法");
            }
        }

        /**
         * 隐藏上下文原值，避免将标准状态或标识意外写入日志。
         * @return 固定脱敏摘要
         */
        @Override public String toString() { return "StandardTraceContext.Carrier[已隐藏]"; }
    }

    /**
     * 当前线程标准上下文作用域，必须在创建线程按后进先出顺序关闭并恢复进入前状态。
     * <p>示例：{@code try (var scope = context.restore(carrier)) { work.run(); }}。</p>
     */
    interface TraceScope extends AutoCloseable {
        /** 退出当前作用域并恢复先前上下文。 */
        @Override void close();
    }
}
