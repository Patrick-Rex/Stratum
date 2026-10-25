package com.patrick.stratum.common.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/** 无状态关联标识策略，供Servlet与响应式入口共享安全字符及缺省生成规则，不绑定线程上下文。 */
public final class CorrelationIdPolicy {
    /** 应用链路关联头。 */
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** HTTP请求关联头。 */
    public static final String REQUEST_HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** 仅提供无状态策略，不创建实例。 */
    private CorrelationIdPolicy() { }

    /**
     * 检查关联值是否满足1至64个ASCII安全字符，避免日志/响应头注入。
     * @param value 可空外部值
     * @return 非空且格式合法时为true
     * @apiNote 示例：{@code CorrelationIdPolicy.isValid("request-001")}。
     */
    public static boolean isValid(String value) { return value != null && SAFE_ID.matcher(value).matches(); }

    /**
     * 保留合法外部值，缺失或非法时生成新UUID，不读写ThreadLocal或全局请求状态。
     * @param supplied 可空外部值
     * @return 有界安全关联标识
     * @apiNote 示例：{@code String traceId = CorrelationIdPolicy.resolve(requestHeader)}。
     */
    public static String resolve(String supplied) { return isValid(supplied) ? supplied : UUID.randomUUID().toString(); }
}
