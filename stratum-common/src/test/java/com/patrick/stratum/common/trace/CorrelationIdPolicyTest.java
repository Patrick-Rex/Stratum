package com.patrick.stratum.common.trace;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证跨入口共享的关联标识边界、生成规则及无线程状态副作用。 */
class CorrelationIdPolicyTest {
    /** 所有已允许字符及长度边界保持原值，不做裁剪或大小写转换。 */
    @Test
    void preservesValidAsciiValuesAtLengthBoundaries() {
        for (String value : List.of("a", "request-01_A.b", "A".repeat(64))) {
            assertTrue(CorrelationIdPolicy.isValid(value)); assertEquals(value, CorrelationIdPolicy.resolve(value));
        }
        assertEquals("X-Trace-Id", CorrelationIdPolicy.TRACE_HEADER); assertEquals("X-Request-Id", CorrelationIdPolicy.REQUEST_HEADER);
    }

    /** 缺失、超长、空白、控制字符和非ASCII值均替换成规范UUID，不能回显原输入。 */
    @Test
    void replacesInvalidOrMissingValuesWithCanonicalUuid() {
        assertFalse(CorrelationIdPolicy.isValid(null)); assertEquals(4, UUID.fromString(CorrelationIdPolicy.resolve(null)).version());
        for (String value : List.of("", "a".repeat(65), "bad value", "中文", "bad\r\nvalue", "../path", "trace:1", " trace ")) {
            assertFalse(CorrelationIdPolicy.isValid(value)); String generated = CorrelationIdPolicy.resolve(value);
            assertEquals(generated, UUID.fromString(generated).toString()); assertTrue(CorrelationIdPolicy.isValid(generated)); assertNotEquals(value, generated);
        }
    }

    /** 生成策略不会改变当前线程已有上下文，响应式调用无需借助ThreadLocal。 */
    @Test
    void leavesExistingThreadContextUntouched() {
        var previous = TraceIdContext.getTraceId(); TraceIdContext.setTraceId("before-policy");
        try {
            assertEquals("independent", CorrelationIdPolicy.resolve("independent"));
            assertTrue(CorrelationIdPolicy.isValid(CorrelationIdPolicy.resolve(null)));
            assertEquals("before-policy", TraceIdContext.getTraceId().orElseThrow());
        } finally { if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow()); else TraceIdContext.clear(); }
    }
}
